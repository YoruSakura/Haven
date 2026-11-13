package sh.haven.core.ssh.backup

import org.junit.Assert.*
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshIdentity
import sh.haven.core.data.db.entities.SshKey

class SshPilotOverwriteTest {
    private val server = ConnectionProfile(id = "source-server", label = "web", host = "web.example.test",
        username = "alice", sshPassword = "backup-password")
    private val key = SshKey(id = "source-key", label = "backup-key", keyType = "ED25519",
        privateKeyBytes = byteArrayOf(1, 2, 3), publicKeyOpenSsh = "ssh-ed25519 fixture",
        fingerprintSha256 = "fixture-fingerprint", isEncrypted = true)
    private val group = ConnectionGroup(id = "source-group", label = "servers", sortOrder = 3)
    private fun plan(profiles: List<ConnectionProfile> = listOf(server), groups: List<ConnectionGroup> = emptyList(),
        keys: List<SshKey> = emptyList(), passphrases: Map<String, String> = emptyMap()) =
        SshPilotServerMapper.Plan(profiles, groups, keys, passphrases, emptySet())

    @Test fun `default merge keeps local credentials while overwrite replaces portable fields`() {
        val local = server.copy(label = "phone-label", host = "phone.example.test", port = 2222,
            username = "phone-user", sshPassword = "phone-password", identityId = "local-identity",
            tunnelConfigId = "phone-vpn", proxyType = "SOCKS5", proxyHost = "proxy.example.test")
        val source = plan()
        val merge = SshPilotServerMerge.merge(source, listOf(local), emptyList(), emptyList())
        assertTrue(merge.plan.connections.isEmpty())
        assertEquals(1, merge.preserved)
        assertEquals(0, merge.updatedServers)

        val overwrite = SshPilotServerMerge.merge(source, listOf(local), emptyList(), emptyList(), true)
        val restored = overwrite.plan.connections.single()
        assertEquals(local.id, restored.id)
        assertEquals(server.host, restored.host)
        assertEquals(server.port, restored.port)
        assertEquals(server.label, restored.label)
        assertEquals(server.username, restored.username)
        assertEquals("backup-password", restored.sshPassword)
        assertEquals(SshIdentity.NONE_ID, restored.identityId)
        assertEquals(local.tunnelConfigId, restored.tunnelConfigId)
        assertEquals(local.proxyType, restored.proxyType)
        assertEquals(local.proxyHost, restored.proxyHost)
        assertEquals(1, overwrite.updatedServers)
        assertEquals(0, overwrite.preserved)
    }

    @Test fun `overwrite remaps all foreign ids including key passphrases and jump references`() {
        val jump = server.copy(id = "source-jump", label = "jump", host = "jump.example.test")
        val localJump = jump.copy(id = "local-jump", sshPassword = "phone-password")
        val localServer = server.copy(id = "local-server")
        val localKey = key.copy(id = "local-key", label = "phone-key", privateKeyBytes = byteArrayOf(9),
            passphraseEncrypted = "ENC:old", biometricProtected = true, enabledForAuth = false)
        val localGroup = group.copy(id = "local-group", colorTag = 7, collapsed = true, identityId = "group-identity")
        val source = plan(listOf(jump, server.copy(groupId = group.id, keyId = key.id,
            jumpProfileId = jump.id, authMethods = "KEY:${key.id}\nPASSWORD")),
            listOf(group), listOf(key), mapOf(key.id to "backup-key-password"))
        val overwrite = SshPilotServerMerge.merge(source, listOf(localJump, localServer),
            listOf(localGroup), listOf(localKey), true)
        val restored = overwrite.plan.connections.single { it.label == "web" }
        assertEquals("local-server", restored.id)
        assertEquals("local-jump", restored.jumpProfileId)
        assertEquals("local-group", restored.groupId)
        assertEquals("local-key", restored.keyId)
        assertEquals("KEY:local-key\nPASSWORD", restored.authMethods)
        assertEquals(SshIdentity.NONE_ID, restored.identityId)
        val restoredKey = overwrite.plan.keys.single()
        assertEquals("local-key", restoredKey.id)
        assertArrayEquals(key.privateKeyBytes, restoredKey.privateKeyBytes)
        assertNull(restoredKey.passphraseEncrypted)
        assertTrue(restoredKey.biometricProtected)
        assertFalse(restoredKey.enabledForAuth)
        assertEquals(mapOf("local-key" to "backup-key-password"), overwrite.plan.passphrases)
        assertEquals("local-group", overwrite.plan.groups.single().id)
        assertEquals(7, overwrite.plan.groups.single().colorTag)
        assertTrue(overwrite.plan.groups.single().collapsed)
        assertEquals(2, overwrite.updatedServers)
        assertEquals(1, overwrite.updatedKeys)
        assertEquals(1, overwrite.updatedGroups)
    }

    @Test fun `stable id wins over another row with the same name and target`() {
        val duplicate = server.copy(id = "other-local", sshPassword = "keep-this-password")
        val sameId = server.copy(host = "changed-on-phone.example.test", sshPassword = "replace-this-password")
        val result = SshPilotServerMerge.merge(plan(), listOf(duplicate, sameId), emptyList(), emptyList(), true)
        assertEquals(server.id, result.plan.connections.single().id)
        val persisted = (listOf(duplicate, sameId).associateBy { it.id } + result.plan.connections.associateBy { it.id }).values
        assertEquals("keep-this-password", persisted.single { it.id == duplicate.id }.sshPassword)
        assertEquals("backup-password", persisted.single { it.id == server.id }.sshPassword)
    }

    @Test fun `overwrite adds missing records and retains unrelated SSH desktop and hardware keys`() {
        val unrelated = server.copy(id = "unrelated", label = "other", host = "other.example.test")
        val desktop = server.copy(id = "desktop", connectionType = "RDP")
        val hardwareKey = key.copy(id = "hardware", hardwareAlias = "device-bound-alias", privateKeyBytes = byteArrayOf())
        val source = plan(profiles = listOf(server.copy(keyId = key.id)), keys = listOf(key), groups = listOf(group))
        val result = SshPilotServerMerge.merge(source, listOf(unrelated, desktop), emptyList(), listOf(hardwareKey), true)
        assertEquals(0, result.updatedServers)
        assertEquals(0, result.updatedKeys)
        assertEquals(1, result.plan.connections.size)
        assertEquals(1, result.plan.groups.size)
        assertTrue(result.plan.keys.isEmpty())
        val after = listOf(unrelated, desktop).associateBy { it.id } + result.plan.connections.associateBy { it.id }
        assertEquals(unrelated, after[unrelated.id])
        assertEquals(desktop, after[desktop.id])
        assertEquals("hardware", result.plan.connections.single().keyId)
        assertTrue(SshPilotServerMerge.merge(plan(profiles = emptyList()), after.values.toList(),
            listOf(group), listOf(hardwareKey), true).plan.connections.isEmpty())
    }

    @Test fun `id collisions cannot overwrite a desktop profile or a different key`() {
        val desktop = server.copy(connectionType = "RDP", sshPassword = null)
        val otherKey = key.copy(fingerprintSha256 = "different-fingerprint")
        val source = plan(profiles = listOf(server.copy(keyId = key.id, authMethods = "KEY:${key.id}")),
            keys = listOf(key), passphrases = mapOf(key.id to "imported-passphrase"))
        val result = SshPilotServerMerge.merge(source, listOf(desktop), emptyList(), listOf(otherKey), true)
        val imported = result.plan.connections.single()
        assertNotEquals(desktop.id, imported.id)
        assertNotEquals(otherKey.id, result.plan.keys.single().id)
        assertEquals(result.plan.keys.single().id, imported.keyId)
        assertEquals("KEY:${imported.keyId}", imported.authMethods)
        assertEquals(mapOf(imported.keyId to "imported-passphrase"), result.plan.passphrases)
        assertEquals(0, result.updatedServers)
        assertEquals(0, result.updatedKeys)
    }

    @Test fun `overwrite can clear an absent server password and obsolete key passphrase`() {
        val localKey = key.copy(passphraseEncrypted = "ENC:phone-passphrase")
        val source = plan(profiles = listOf(server.copy(sshPassword = null)), keys = listOf(key))
        val result = SshPilotServerMerge.merge(source, listOf(server), emptyList(), listOf(localKey), true)
        assertNull(result.plan.connections.single().sshPassword)
        assertNull(result.plan.keys.single().passphraseEncrypted)
        assertTrue(result.plan.passphrases.isEmpty())
        assertEquals(1, result.updatedServers)
        assertEquals(1, result.updatedKeys)
    }

    @Test fun `merge never rewrites matching keys or their stored passphrases`() {
        val localKey = key.copy(id = "phone-key", passphraseEncrypted = "ENC:keep-me")
        val result = SshPilotServerMerge.merge(plan(profiles = listOf(server.copy(keyId = key.id)),
            keys = listOf(key), passphrases = mapOf(key.id to "backup-passphrase")), emptyList(), emptyList(), listOf(localKey))
        assertTrue(result.plan.keys.isEmpty())
        assertTrue(result.plan.passphrases.isEmpty())
        assertEquals("phone-key", result.plan.connections.single().keyId)
        assertEquals(SshIdentity.NONE_ID, result.plan.connections.single().identityId)
    }
}
