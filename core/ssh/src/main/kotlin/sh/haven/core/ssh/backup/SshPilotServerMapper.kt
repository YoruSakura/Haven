package sh.haven.core.ssh.backup

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.fido.SkKeyData
import sh.haven.core.fido.SkKeyParser
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SshKeyImporter
import sh.haven.core.ssh.backup.SshPilotBackupCodec.asMap
import sh.haven.core.ssh.backup.SshPilotBackupCodec.decode
import sh.haven.core.ssh.backup.SshPilotBackupCodec.encode
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Server-only projection. Desktop UI preferences and local commands never enter Haven. */
internal object SshPilotServerMapper {
    data class Plan(
        val connections: List<ConnectionProfile>, val groups: List<ConnectionGroup>,
        val keys: List<SshKey>, val passphrases: Map<String, String>, val notices: Set<String>,
    )
    private const val ALIAS_MARKER = "# SSHPilot-Alias "
    private val optionNames = listOf("KexAlgorithms", "Ciphers", "MACs", "HostKeyAlgorithms",
        "PubkeyAcceptedAlgorithms", "PubkeyAcceptedKeyTypes", "CASignatureAlgorithms",
        "PubkeyAuthentication", "ServerAliveInterval", "ServerAliveCountMax", "PreferredAuthentications")
        .associateBy { it.lowercase() }
    private val mappedOptions = optionNames.keys + setOf("hostname", "user", "port", "identityfile",
        "proxyjump", "forwardagent", "remotecommand", "requesttty", "identitiesonly")

    fun plan(manifest: Map<String, Any?>): Plan {
        val notices = linkedSetOf<String>()
        val config = PortableSshConfig(manifest, notices)
        val store = (manifest["connection_store"] as? Map<*, *>)?.asMap().orEmpty()
        require(store.isEmpty() || store["version"] == 1) { "Unsupported connection store version" }
        val summaries = store.maps("connections")
        if (summaries.any { it["protocol"] != "ssh" }) notices += "protocols"
        val sshSummaries = summaries.filter { it["protocol"] == "ssh" }.associateBy { it.string("id") }
        val aliases = (config.aliases + sshSummaries.keys).filter { it.isNotBlank() }.distinct()
        require(aliases.size <= 10000) { "Too many server profiles" }
        val credentials = manifest.maps("credentials")
        val home = manifest.string("source_home").trimEnd('/')
        fun path(value: String): String = Paths.get(if (value.startsWith("~/") && home.isNotEmpty())
            home + value.drop(1) else value).normalize().toString()
        val passphrasesByPath = credentials.filter { it["type"] == "key" }.associate {
            path((it["metadata"] as? Map<*, *>)?.get("key_path") as? String ?: it.string("id")) to it.string("secret")
        }
        val keys = mutableListOf<SshKey>()
        val keysByPath = mutableMapOf<String, SshKey>()
        val passphrases = mutableMapOf<String, String>()
        for (item in manifest.maps("private_keys")) {
            try {
                val raw = decode(item["content_b64"])
                require(raw.size <= 1024 * 1024) { "Key file too large" }
                val keyPath = path(item.string("path"))
                val passphrase = passphrasesByPath[keyPath]
                val pub = (item["public_content_b64"] as? String)?.let(::decode)
                val imported = importKey(raw, passphrase, pub)
                val key = imported.copy(id = id("key:${imported.keyType}:${imported.fingerprintSha256}"),
                    label = keyPath.substringAfterLast('/').ifBlank { "SSH Pilot key" })
                if (keys.none { it.id == key.id }) keys += key
                keysByPath[keyPath] = key
                if (passphrase != null) passphrases[key.id] = passphrase
            } catch (_: Exception) { notices += "keys" }
        }

        val rawGroups = store.maps("groups").ifEmpty {
            val legacy = (manifest["app_config"] as? Map<*, *>)?.get("connection_groups") as? Map<*, *>
            val membership = legacy?.get("connections") as? Map<*, *>
            (legacy?.get("groups") as? Map<*, *>)?.entries?.map { (gid, value) ->
                val g = value.asMap().toMutableMap()
                g["id"] = gid.toString()
                g["name"] = g["name"] ?: gid.toString()
                g["connection_ids"] = ((g["connections"] as? List<*>)?.map { it.toString() }.orEmpty() +
                    membership?.entries?.filter { it.value == gid }?.map { it.key.toString() }.orEmpty()).distinct()
                g
            }.orEmpty()
        }
        val groupById = rawGroups.associateBy { it.string("id") }
        fun groupLabel(g: Map<String, Any?>, visited: Set<String> = emptySet()): String {
            val gid = g.string("id")
            require(gid !in visited && visited.size < 40) { "Cyclic or excessive group hierarchy" }
            val parent = g["parent_id"] as? String
            return if (parent != null && parent in groupById) {
                notices += "groups"
                groupLabel(groupById.getValue(parent), visited + gid) + " / " + g.string("name")
            } else g.string("name")
        }
        val groups = rawGroups.map { ConnectionGroup(id = id("group:${it.string("id")}"),
            label = groupLabel(it), sortOrder = (it["order"] as? Number)?.toInt() ?: 0) }
        val membership = mutableMapOf<String, String>()
        rawGroups.forEachIndexed { index, g ->
            (g["connection_ids"] as? List<*>)?.forEach { alias ->
                if (membership.putIfAbsent(alias.toString(), groups[index].id) != null) notices += "groups"
            }
        }
        val loginProfiles = (store["login_profiles"] as? Map<*, *>)?.asMap().orEmpty()
        val scope = if (manifest["isolated_mode"] == true) "isolated" else "default"
        val groupLinks = ((loginProfiles["group_links"] as? Map<*, *>)?.get(scope) as? Map<*, *>).orEmpty()
        val metadata = store.maps("metadata").associate { it.string("connection_id") to
            ((it["values"] as? Map<*, *>)?.asMap().orEmpty()) }
        fun linkedPassword(alias: String): String? {
            val link = metadata[alias]?.get("login_profile") as? Map<*, *> ?: return null
            val profileId = if (link["mode"] == "explicit") link["id"] as? String else {
                var group = rawGroups.firstOrNull { (it["connection_ids"] as? List<*>)?.contains(alias) == true }
                val seen = mutableSetOf<String>()
                var found: String? = null
                while (group != null && seen.add(group.string("id"))) {
                    found = groupLinks[group.string("id")] as? String
                    if (found != null) break
                    group = groupById[group["parent_id"]]
                }
                found
            } ?: return null
            val secret = credentials.firstOrNull { it["type"] == "password" && it["username"] == "login" &&
                (it["host"] == "sshpilot-login-profile/$profileId" ||
                    (it["metadata"] as? Map<*, *>)?.get("login_profile") == profileId) }?.get("secret") as? String
            if (secret == null) notices += "credentials"
            return secret
        }
        val profiles = linkedMapOf<String, ConnectionProfile>()
        val jumps = mutableMapOf<String, String>()
        aliases.forEachIndexed { index, alias ->
            val o = config.options(alias)
            val summary = sshSummaries[alias].orEmpty()
            fun opt(key: String): String? = o[key]?.joinToString(" ")
            val host = opt("hostname")?.replace("%h", alias) ?: summary.string("hostname").ifBlank { alias }
            val user = opt("user") ?: summary.string("username").ifBlank { home.substringAfterLast('/') }
            require(host.isNotBlank() && !host.contains('\n') && !user.contains('\n')) { "Invalid SSH target" }
            val port = (opt("port") ?: summary["port"]?.toString() ?: "22").toInt()
            require(port in 1..65535) { "Invalid SSH port" }
            val keyPaths = o["identityfile"].orEmpty().filter { it != "none" }.map {
                path(it.replace("%d", home).replace("%h", host).replace("%r", user).replace("%p", port.toString()))
            }
            val selectedKeys = keyPaths.mapNotNull { keysByPath[it] }.distinctBy { it.id }
            if (selectedKeys.size < keyPaths.distinct().size) notices += "keys"
            val password = linkedPassword(alias) ?: credentials.firstOrNull {
                it["type"] == "password" && it["username"] == user && it["host"] in listOf(host, alias)
            }?.get("secret") as? String
            val useKeys = opt("pubkeyauthentication") != "no"
            val methods = (if (useKeys) selectedKeys.map { "KEY:${it.id}" } else emptyList()) +
                listOf("PASSWORD", "KEYBOARD_INTERACTIVE")
            val extras = o.filterKeys { it in optionNames }.map { (k, v) -> "${optionNames.getValue(k)} ${v.joinToString(" ")}" }
            if (o.keys.any { it !in mappedOptions }) notices += "options"
            profiles[alias] = ConnectionProfile(
                id = id("connection:$alias"), label = summary.string("display_name").ifBlank { alias },
                host = host, port = port, username = user, sshPassword = password,
                keyId = selectedKeys.firstOrNull()?.id,
                authType = if (selectedKeys.isEmpty() || !useKeys) ConnectionProfile.AuthType.PASSWORD else ConnectionProfile.AuthType.KEY,
                authMethods = methods.joinToString("\n"), ignoreSavedKeys = !useKeys || opt("identitiesonly") == "yes" || keyPaths.isNotEmpty(),
                groupId = membership[alias], sortOrder = index, forwardAgent = opt("forwardagent") == "yes",
                remoteCommand = opt("remotecommand")?.takeUnless { it == "none" },
                requestPty = opt("requesttty") != "no",
                sshOptions = (listOf(ALIAS_MARKER + encode(alias.toByteArray(Charsets.UTF_8))) + extras).joinToString("\n"),
            )
            opt("proxyjump")?.takeUnless { it == "none" }?.let { jumps[alias] = it }
            if (o.containsKey("proxycommand")) notices += "jumps"
        }
        val generatedHops = mutableListOf<ConnectionProfile>()
        val resolvedJumps = mutableSetOf<String>()
        fun resolveJump(alias: String, resolving: Set<String> = emptySet()): ConnectionProfile {
            require(alias !in resolving && resolving.size < 16) { "Cyclic or excessive ProxyJump chain" }
            if (alias in resolvedJumps || alias !in jumps) return profiles.getValue(alias)
            val jump = jumps.getValue(alias)
            val hops = jump.split(',')
            var previous: String? = null
            for ((index, spec) in hops.withIndex()) {
                val direct = profiles[spec]?.let { resolveJump(spec, resolving + alias) }
                val hop = direct ?: parseJump(spec, profiles, alias, index)
                if (hops.size == 1 && direct != null) previous = direct.id
                else {
                    val copy = hop.copy(id = id("hop:$alias:$index:$spec"), label = "$alias · $spec",
                        jumpProfileId = previous ?: hop.jumpProfileId)
                    generatedHops += copy
                    previous = copy.id
                }
            }
            profiles[alias] = profiles.getValue(alias).copy(jumpProfileId = previous)
            resolvedJumps += alias
            return profiles.getValue(alias)
        }
        jumps.keys.forEach { resolveJump(it) }
        val connections = profiles.values + generatedHops
        val byId = connections.associateBy { it.id }
        connections.forEach { p ->
            var current: ConnectionProfile? = p
            val visited = mutableSetOf<String>()
            while (current != null) {
                require(visited.add(current.id) && visited.size <= 16) { "Cyclic or excessive ProxyJump chain" }
                current = current.jumpProfileId?.let(byId::get)
            }
        }
        if (!(manifest["known_hosts"] as? String).isNullOrBlank()) notices += "known_hosts"
        return Plan(connections, groups, keys, passphrases, notices)
    }

    private fun parseJump(spec: String, profiles: Map<String, ConnectionProfile>, alias: String, index: Int): ConnectionProfile {
        val match = Regex("^(?:([^@]+)@)?(\\[[^]]+]|[^:]+)(?::([0-9]+))?$").matchEntire(spec)
            ?: error("Unsupported ProxyJump target")
        val host = match.groupValues[2].removeSurrounding("[", "]")
        val existing = profiles[host]
        val user = match.groupValues[1].ifBlank { existing?.username ?: profiles.getValue(alias).username }
        val port = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: existing?.port ?: 22
        require(port in 1..65535)
        return existing?.copy(username = user, port = port) ?: ConnectionProfile(
            id = id("hop:$alias:$index:$spec"), label = spec, host = host, port = port, username = user)
    }

    private fun importKey(raw: ByteArray, passphrase: String?, public: ByteArray?): SshKey {
        if (SkKeyParser.isSkKeyFile(raw)) {
            val sk = SkKeyParser.parse(raw)
            return SshKey(label = "", keyType = sk.algorithmName, privateKeyBytes = SkKeyData.serialize(sk),
                publicKeyOpenSsh = SkKeyParser.formatPublicKeyLine(sk), fingerprintSha256 = SkKeyParser.fingerprintSha256(sk.publicKeyBlob))
        }
        try {
            val imported = SshKeyImporter.import(raw, passphrase)
            return SshKey(label = "", keyType = imported.keyType, privateKeyBytes = imported.privateKeyBytes,
                publicKeyOpenSsh = imported.publicKeyOpenSsh, fingerprintSha256 = imported.fingerprintSha256, isEncrypted = imported.isEncrypted)
        } catch (e: SshKeyImporter.EncryptedKeyException) {
            // Encrypted keys can be moved without knowing their separate passphrase when
            // the backup contains the public half (or the OpenSSH header exposes it).
            val pair = KeyPair.load(JSch(), raw, public)
            try {
                val blob = pair.publicKeyBlob ?: throw e
                val type = String(blob, 4, java.nio.ByteBuffer.wrap(blob).int, Charsets.US_ASCII)
                return SshKey(label = "", keyType = type, privateKeyBytes = raw,
                    publicKeyOpenSsh = "$type ${encode(blob)}", fingerprintSha256 = fingerprint(blob), isEncrypted = true)
            } finally { pair.dispose() }
        }
    }

    fun manifest(profiles: List<ConnectionProfile>, groups: List<ConnectionGroup>, keys: List<SshKey>,
        passphrases: Map<String, String>, notices: MutableSet<String>): Map<String, Any?> {
        val ssh = profiles.filter { it.connectionType == "SSH" }
        if (ssh.size != profiles.size) notices += "protocols"
        val aliases = ssh.associate { p ->
            val old = p.sshOptions?.lineSequence()?.firstOrNull { it.startsWith(ALIAS_MARKER) }
                ?.removePrefix(ALIAS_MARKER)?.let { runCatching { String(decode(it), Charsets.UTF_8) }.getOrNull() }
            p.id to (old?.takeIf { it.matches(Regex("[A-Za-z0-9_.@-]+")) }
                ?: "haven-" + id(p.id).replace("-", ""))
        }
        require(aliases.values.distinct().size == aliases.size) { "Conflicting SSH aliases" }
        val portableKeys = keys.filter { it.hardwareAlias == null && !SkKeyData.isSkKeyBlob(it.privateKeyBytes) }
        if (portableKeys.size != keys.size) notices += "hardware"
        // A synthetic source home lets the desktop's existing re-homing code move
        // BOTH key files and their keyring passphrases onto the destination home.
        val sourceHome = "/home/haven-portable"
        val paths = portableKeys.associate { it.id to "$sourceHome/.ssh/haven/" + id(it.id) }
        val secrets = mutableListOf<Map<String, Any?>>()
        val seenPasswords = mutableMapOf<Pair<String, String>, String>()
        val text = buildString {
            ssh.forEach { p ->
                val alias = aliases.getValue(p.id)
                appendLine("Host $alias")
                appendLine("    HostName ${quote(p.host)}")
                if (p.username.isNotBlank()) appendLine("    User ${quote(p.username)}")
                appendLine("    Port ${p.port}")
                val selectedIds = p.authMethodSpecs.filterIsInstance<ConnectionProfile.AuthMethodSpec.Key>().mapNotNull { it.keyId }
                    .ifEmpty { listOfNotNull(p.keyId) }.distinct()
                selectedIds.forEach { keyId ->
                    paths[keyId]?.let { appendLine("    IdentityFile ${quote(it)}") } ?: run { notices += "keys" }
                }
                if (p.ignoreSavedKeys || selectedIds.isNotEmpty()) appendLine("    IdentitiesOnly yes")
                p.jumpProfileId?.let { jump ->
                    val target = aliases[jump] ?: error("A ProxyJump profile is missing from the SSH export")
                    appendLine("    ProxyJump $target")
                }
                if (p.forwardAgent) appendLine("    ForwardAgent yes")
                p.remoteCommand?.let { appendLine("    RemoteCommand ${singleLine(it)}") }
                if (!p.requestPty) appendLine("    RequestTTY no")
                ConnectionConfig.parseSshOptions(p.sshOptions).forEach { (k, v) ->
                    if (k.lowercase() in optionNames) appendLine("    ${optionNames.getValue(k.lowercase())} ${singleLine(v)}")
                    else notices += "options"
                }
                if (p.useMosh || p.useEternalTerminal || p.postLoginCommand != null || p.tunnelConfigId != null || p.proxyType != null) notices += "options"
                p.sshPassword?.let { password ->
                    val key = p.host to p.username
                    require(seenPasswords[key] == null || seenPasswords[key] == password) { "SSH Pilot cannot store different passwords for the same host and user" }
                    if (seenPasswords.put(key, password) == null) secrets += mapOf("id" to "${p.username}@${p.host}",
                        "type" to "password", "host" to p.host, "username" to p.username, "secret" to password, "metadata" to emptyMap<String, Any?>())
                }
                appendLine()
            }
        }
        portableKeys.forEach { key -> passphrases[key.id]?.let { password ->
            val path = paths.getValue(key.id)
            secrets += mapOf("id" to path, "type" to "key", "secret" to password, "metadata" to mapOf("key_path" to path))
        } }
        return mapOf(
            "version" to 1, "format" to SshPilotBackupCodec.FORMAT, "fork_only" to true,
            "platform" to "android", "config_version" to 3, "source_home" to sourceHome, "app_config" to emptyMap<String, Any?>(),
            "backup_options" to mapOf("app_settings" to true, "ssh_config" to true, "known_hosts" to false, "private_keys" to true, "secrets" to true),
            "ssh_config" to text, "known_hosts" to null, "credentials" to secrets,
            "private_keys" to portableKeys.map { k ->
                val path = paths.getValue(k.id)
                mapOf("path" to path, "mode" to 384, "content_b64" to encode(SshKeyExporter.toPem(k.privateKeyBytes, k.keyType)),
                    "public_path" to "$path.pub", "public_mode" to 420, "public_content_b64" to encode((k.publicKeyOpenSsh + "\n").toByteArray(Charsets.UTF_8)))
            },
            "connection_store" to mapOf("version" to 1,
                "connections" to ssh.map { p -> mapOf("id" to aliases.getValue(p.id), "nickname" to aliases.getValue(p.id),
                    "protocol" to "ssh", "hostname" to p.host, "username" to p.username, "port" to p.port, "display_name" to p.label) },
                "groups" to groups.filter { g -> ssh.any { it.groupId == g.id } }.map { g ->
                    mapOf("id" to g.id, "name" to g.label, "parent_id" to null, "order" to g.sortOrder, "color" to "",
                        "connection_ids" to ssh.filter { it.groupId == g.id }.map { aliases.getValue(it.id) })
                },
                "root_connection_ids" to ssh.filter { it.groupId == null }.map { aliases.getValue(it.id) },
                "metadata" to emptyList<Any>()),
        )
    }

    private fun fingerprint(blob: ByteArray) = "SHA256:" + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    private fun id(value: String): String = UUID.nameUUIDFromBytes(("haven-sshpilot:$value").toByteArray(Charsets.UTF_8)).toString()
    private fun quote(value: String): String = "\"" + singleLine(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun singleLine(value: String): String {
        require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Invalid line break in SSH profile" }
        return value
    }
    private fun Map<String, Any?>.string(key: String): String = this[key] as? String ?: ""
    private fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> = (this[key] as? List<*>)?.map { it.asMap() }.orEmpty()
}
