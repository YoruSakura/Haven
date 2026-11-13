package sh.haven.core.ssh.backup

import org.junit.Assert.*
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import java.io.File
import javax.crypto.AEADBadTagException

class SshPilotBackupTest {
    private val password = "备份密码🔒-not-a-real-password"
    private fun fixture(name: String = "python-special.yaml") =
        javaClass.getResourceAsStream("/sshpilot/$name")!!.use { it.readBytes() }
    private fun manifest() = SshPilotBackupCodec.read(fixture(), password)

    @Test fun `decrypts actual Python YAML and both SPBK formats with Unicode passwords`() {
        val expected = manifest()
        assertEquals(expected, SshPilotBackupCodec.read(fixture("python-encrypted.spbk"), password))
        assertEquals(expected, SshPilotBackupCodec.read(fixture("python-plain.spbk"), ""))
        assertEquals(3, (expected["private_keys"] as List<*>).size)
        assertEquals("服务器密码🔐", ((expected["credentials"] as List<*>)[0] as Map<*, *>)["secret"])
    }

    @Test fun `wrong password does not produce any import plan`() {
        for (name in listOf("python-special.yaml", "python-encrypted.spbk")) {
            assertThrows(AEADBadTagException::class.java) { SshPilotBackupCodec.read(fixture(name), "wrong") }
        }
    }

    @Test fun `metadata tampering is authenticated`() {
        val raw = String(fixture(), Charsets.UTF_8)
        val tampered = raw.replaceFirst("created_at:", "untrusted_created_at:")
        assertThrows(AEADBadTagException::class.java) { SshPilotBackupCodec.read(tampered.toByteArray(), password) }
    }

    @Test fun `ciphertext tampering is authenticated`() {
        val data = fixture("python-encrypted.spbk")
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { SshPilotBackupCodec.read(data, password) }
    }

    @Test fun `rejects unsafe YAML aliases tags duplicate keys and unbounded KDF`() {
        for (yaml in listOf("a: &a [one]\nb: *a", "a: &a one\nb: *a", "a: !!java.lang.String danger", "a: 1\na: 2")) {
            assertThrows(Exception::class.java) { SshPilotBackupCodec.read(yaml.toByteArray(), password) }
        }
        val expensive = fixture("python-encrypted.spbk").toString(Charsets.ISO_8859_1)
            .replace("32768", "1048576").toByteArray(Charsets.ISO_8859_1)
        assertThrows(IllegalArgumentException::class.java) { SshPilotBackupCodec.read(expensive, password) }
    }

    @Test fun `imports includes groups password three key forms and jump reference`() {
        val p = SshPilotServerMapper.plan(manifest())
        assertEquals(3, p.connections.size)
        assertEquals(3, p.keys.size)
        assertEquals(1, p.passphrases.size)
        val web = p.connections.single { it.label == "生产服务器" }
        val jump = p.connections.single { it.label == "跳板" }
        assertEquals("web.example.test", web.host)
        assertEquals("测试", web.username)
        assertEquals(2222, web.port)
        assertEquals("服务器密码🔐", web.sshPassword)
        assertEquals(jump.id, web.jumpProfileId)
        assertTrue(web.forwardAgent)
        assertEquals("生产 / 服务器", p.groups.single { it.id == web.groupId }.label)
        assertTrue(web.sshOptions!!.contains("ServerAliveInterval 30"))
        assertNotNull(web.keyId)
        assertEquals(2, p.keys.count { it.isEncrypted })
        assertEquals(setOf("groups", "known_hosts"), p.notices)
        assertEquals(p.connections.map { it.id }, SshPilotServerMapper.plan(manifest()).connections.map { it.id })
    }

    @Test fun `repeated import keeps local changes including rotated passwords`() {
        val p = SshPilotServerMapper.plan(manifest())
        val changed = p.connections.map { it.copy(sshPassword = "rotated-on-phone", host = "new-host.example.test") }
        val merge = SshPilotServerMerge.merge(p, changed, p.groups, p.keys)
        assertEquals(3, merge.preserved)
        assertTrue(merge.plan.connections.isEmpty())
        assertTrue(merge.plan.keys.isEmpty())
        assertTrue(merge.plan.groups.isEmpty())
    }

    @Test fun `merge remaps duplicate jump group and key ids without clobbering secrets`() {
        val p = SshPilotServerMapper.plan(manifest())
        val oldJump = p.connections.single { it.label == "跳板" }
        val localJump = oldJump.copy(id = "local-jump", sshPassword = "new-password")
        val oldKey = p.keys.single { it.id == oldJump.keyId }
        val localKey = oldKey.copy(id = "local-key")
        val localGroup = p.groups.first().copy(id = "local-group")
        val merge = SshPilotServerMerge.merge(p, listOf(localJump), listOf(localGroup), listOf(localKey))
        val web = merge.plan.connections.single { it.label == "生产服务器" }
        assertEquals("local-jump", web.jumpProfileId)
        assertEquals(1, merge.preserved)
        assertEquals(2, merge.plan.keys.size)
        assertEquals(1, merge.plan.groups.size)
    }

    @Test fun `config respects first-value wildcard negation quoted paths and never follows local includes`() {
        val notices = linkedSetOf<String>()
        val config = PortableSshConfig(mapOf("ssh_config" to """
            Host * !excluded
                User first
            Host web
                User second
                HostName web.example.test
                IdentityFile "/not/a/real key"
                Include /etc/ssh/private-local-config
            Match exec "touch /tmp/should-never-run"
                User ignored
        """.trimIndent()), notices)
        assertEquals(listOf("first"), config.options("web")["user"])
        assertEquals(listOf("/not/a/real key"), config.options("web")["identityfile"])
        assertFalse(config.options("excluded").containsKey("user"))
        assertEquals(setOf("include", "match"), notices)
    }

    @Test fun `legacy group membership and display aliases work`() {
        val p = SshPilotServerMapper.plan(mapOf("ssh_config" to "Host web\n HostName web.example.test\n User alice",
            "app_config" to mapOf("connection_groups" to mapOf("groups" to mapOf(
                "prod" to mapOf("name" to "生产", "connections" to listOf("web")))))))
        assertEquals("生产", p.groups.single().label)
        assertEquals(p.groups.single().id, p.connections.single().groupId)
    }

    @Test fun `multi-hop jump chain and inline targets are connected in order`() {
        val p = SshPilotServerMapper.plan(mapOf("ssh_config" to """
            Host jump
              HostName bastion.example.test
              User gateway
            Host web
              HostName web.example.test
              User alice
              ProxyJump jump,bob@[2001:db8::1]:2200
        """.trimIndent()))
        val web = p.connections.single { it.label == "web" }
        val finalHop = p.connections.single { it.id == web.jumpProfileId }
        val firstHop = p.connections.single { it.id == finalHop.jumpProfileId }
        assertEquals("2001:db8::1", finalHop.host)
        assertEquals("bob", finalHop.username)
        assertEquals(2200, finalHop.port)
        assertEquals("bastion.example.test", firstHop.host)
        assertEquals("gateway", firstHop.username)
    }

    @Test fun `shared login profile passwords follow explicit and inherited assignments`() {
        val profiles = mapOf("version" to 1, "profiles" to emptyMap<String, Any?>(),
            "group_links" to mapOf("default" to mapOf("prod" to "lp-parent")))
        val p = SshPilotServerMapper.plan(mapOf(
            "ssh_config" to "Host a b\n HostName host.example.test\n User alice",
            "connection_store" to mapOf("version" to 1, "login_profiles" to profiles,
                "groups" to listOf(mapOf("id" to "prod", "name" to "生产", "connection_ids" to listOf("a", "b"))),
                "metadata" to listOf(
                    mapOf("connection_id" to "a", "values" to mapOf("login_profile" to mapOf("mode" to "explicit", "id" to "lp-a"))),
                    mapOf("connection_id" to "b", "values" to mapOf("login_profile" to mapOf("mode" to "inherit"))))),
            "credentials" to listOf(
                mapOf("type" to "password", "username" to "login", "host" to "sshpilot-login-profile/lp-a", "secret" to "explicit"),
                mapOf("type" to "password", "username" to "login", "host" to "sshpilot-login-profile/lp-parent", "secret" to "inherited"))))
        assertEquals("explicit", p.connections.single { it.label == "a" }.sshPassword)
        assertEquals("inherited", p.connections.single { it.label == "b" }.sshPassword)
    }

    @Test fun `cyclic jumps and invalid ports fail before any database changes`() {
        assertThrows(IllegalArgumentException::class.java) { SshPilotServerMapper.plan(mapOf(
            "ssh_config" to "Host a\n ProxyJump b\nHost b\n ProxyJump a")) }
        assertThrows(IllegalArgumentException::class.java) { SshPilotServerMapper.plan(mapOf(
            "ssh_config" to "Host a\n Port 0")) }
    }

    @Test fun `unsupported non SSH protocols are reported and excluded`() {
        val p = SshPilotServerMapper.plan(mapOf("connection_store" to mapOf("version" to 1,
            "connections" to listOf(mapOf("id" to "telnet", "protocol" to "telnet")))))
        assertTrue(p.connections.isEmpty())
        assertTrue("protocols" in p.notices)
    }

    @Test fun `export remains encrypted and is readable by the actual Python desktop reader`() {
        val plan = SshPilotServerMapper.plan(manifest())
        val notices = linkedSetOf<String>()
        val document = SshPilotServerMapper.manifest(plan.connections, plan.groups, plan.keys, plan.passphrases, notices)
        val bytes = SshPilotBackupCodec.write(document, "安卓导出🔒")
        assertEquals(document, SshPilotBackupCodec.read(bytes, "安卓导出🔒"))
        assertFalse(String(bytes, Charsets.UTF_8).contains("服务器密码"))
        assertTrue(notices.isEmpty())
        val output = File("build/sshpilot-interop/haven-special.yaml")
        output.parentFile!!.mkdirs()
        output.writeBytes(bytes)
    }

    @Test fun `export rejects line injection and conflicting passwords for same account`() {
        fun server(id: String, password: String) = ConnectionProfile(id = id, label = id,
            host = "host.example.test", username = "alice", sshPassword = password)
        assertThrows(IllegalArgumentException::class.java) { SshPilotServerMapper.manifest(
            listOf(server("a", "one"), server("b", "two")), emptyList(), emptyList(), emptyMap(), linkedSetOf()) }
        assertThrows(IllegalArgumentException::class.java) { SshPilotServerMapper.manifest(
            listOf(server("a", "one").copy(host = "host\nProxyCommand malicious")),
            emptyList(), emptyList(), emptyMap(), linkedSetOf()) }
    }
}
