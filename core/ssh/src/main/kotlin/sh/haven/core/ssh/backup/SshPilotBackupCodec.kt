package sh.haven.core.ssh.backup

import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire compatibility with SSH Pilot CN's special_backup.py and backup_archive.py. */
object SshPilotBackupCodec {
    const val FORMAT = "sshpilot-cn-special-backup"
    const val MAX_BYTES = 64 * 1024 * 1024
    private val spbkMagic = "SPBK1\n".toByteArray()
    private val base64 = Base64.getEncoder()

    fun read(data: ByteArray, password: String): Map<String, Any?> {
        require(data.size <= MAX_BYTES) { "Backup exceeds the 64 MiB limit" }
        return if (data.take(spbkMagic.size).toByteArray().contentEquals(spbkMagic)) {
            readSpbk(data, password)
        } else {
            val doc = loadYaml(data)
            require(doc["format"] == FORMAT && doc["version"] == 1 && doc["fork_only"] == true) {
                "Not a supported SSH Pilot CN backup"
            }
            val enc = doc["encryption"].asMap()
            require(enc["algorithm"] == "AES-256-GCM" && enc["kdf"] == "scrypt" &&
                enc["n"] == 32768 && enc["r"] == 8 && enc["p"] == 1) {
                "Unsupported backup encryption parameters"
            }
            val salt = decode(enc["salt"]).also { require(it.size == 16) }
            val nonce = decode(enc["nonce"]).also { require(it.size == 12) }
            val ciphertext = decode(doc["payload"])
            require(ciphertext.size in 16..MAX_BYTES)
            val aad = canonicalJson(doc.filterKeys { it != "payload" }).toByteArray(Charsets.UTF_8)
            loadYaml(crypt(Cipher.DECRYPT_MODE, ciphertext, password, salt, nonce, aad))
        }.also { manifest ->
            require(manifest["version"] == 1) { "Unsupported SSH Pilot backup payload version" }
        }
    }

    /** JSON is also YAML. This avoids emitter-specific quoting while remaining PyYAML compatible. */
    fun write(manifest: Map<String, Any?>, password: String): ByteArray {
        require(password.isNotEmpty()) { "A backup password is required" }
        val payload = canonicalJson(manifest).toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_BYTES) { "Backup payload exceeds the 64 MiB limit" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val doc = linkedMapOf<String, Any?>(
            "format" to FORMAT, "version" to 1, "fork_only" to true,
            "created_at" to Instant.now().toString(),
            "encryption" to mapOf(
                "algorithm" to "AES-256-GCM", "kdf" to "scrypt", "n" to 32768,
                "r" to 8, "p" to 1, "salt" to encode(salt), "nonce" to encode(nonce),
            ),
        )
        val encrypted = crypt(Cipher.ENCRYPT_MODE, payload, password, salt, nonce,
            canonicalJson(doc).toByteArray(Charsets.UTF_8))
        doc["payload"] = encode(encrypted)
        return canonicalJson(doc).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_BYTES) { "Encrypted backup exceeds the 64 MiB limit" }
        }
    }

    private fun readSpbk(data: ByteArray, password: String): Map<String, Any?> {
        val newline = (spbkMagic.size until minOf(data.size, 65536)).firstOrNull { data[it] == 10.toByte() }
            ?: error("Missing SPBK header")
        val header = data.copyOfRange(spbkMagic.size, newline)
        val json = JSONObject(String(header, Charsets.UTF_8))
        require(json.getInt("v") == 1) { "Unsupported SPBK version" }
        var payload = data.copyOfRange(newline + 1, data.size)
        if (!json.isNull("enc")) {
            val enc = json.getJSONObject("enc")
            require(enc.getString("algo") == "AES-256-GCM" && enc.getString("kdf") == "scrypt")
            val n = enc.getInt("n")
            val r = enc.getInt("r")
            val p = enc.getInt("p")
            require(n > 1 && n and (n - 1) == 0 && r in 1..32 && p in 1..16 &&
                128L * n * r <= 64L * 1024 * 1024 && n.toLong() * r * p <= 4L * 1024 * 1024) {
                "SPBK scrypt parameters exceed mobile limits"
            }
            val salt = decode(enc.getString("salt")).also { require(it.size in 8..1024) }
            val nonce = decode(enc.getString("nonce")).also { require(it.size == 12) }
            // SPBK authenticates the ORIGINAL header bytes, including whitespace and key order.
            payload = crypt(Cipher.DECRYPT_MODE, payload, password, salt, nonce, header, n, r, p)
        }
        ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 1024) { "Too many ZIP entries" }
                require(!entry.isDirectory && entry.name == "manifest.json") { "Unexpected SPBK ZIP entry" }
                return loadYaml(readBounded(zip))
            }
        }
        error("Missing SPBK manifest")
    }

    private fun crypt(mode: Int, data: ByteArray, password: String, salt: ByteArray, nonce: ByteArray,
        aad: ByteArray, n: Int = 32768, r: Int = 8, p: Int = 1): ByteArray {
        require(password.isNotEmpty()) { "A backup password is required" }
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val key = try { SCrypt.generate(passwordBytes, salt, n, r, p, 32) } finally { passwordBytes.fill(0) }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(data)
            }
        } finally { key.fill(0) }
    }

    private fun loadYaml(data: ByteArray): Map<String, Any?> {
        require(data.size <= MAX_BYTES) { "Backup payload exceeds the 64 MiB limit" }
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = 40
            codePointLimit = MAX_BYTES
        }
        // Python rejects ALL aliases, including scalar aliases. Check events as well as limits.
        val yaml = Yaml(SafeConstructor(options))
        try {
            require(yaml.parse(String(data, Charsets.UTF_8).reader()).none {
                it is org.yaml.snakeyaml.events.AliasEvent
            }) { "YAML aliases are not allowed in backup files" }
            return yaml.load<Any>(String(data, Charsets.UTF_8)).asMap()
        } catch (_: org.yaml.snakeyaml.error.YAMLException) {
            // Parser snippets may contain decrypted passwords: never put them in UI/logs.
            throw IllegalArgumentException("Invalid or unsupported YAML backup data")
        }
    }

    internal fun canonicalJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (ch in value) when (ch) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b")
                '\u000c' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
            append('"')
        }
        is Boolean, is Int, is Long -> value.toString()
        is Map<*, *> -> value.entries.sortedBy { it.key as? String ?: error("Invalid mapping key") }
            .joinToString(",", "{", "}") { canonicalJson(it.key) + ":" + canonicalJson(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { canonicalJson(it) }
        else -> error("Unsupported backup data type")
    }

    internal fun Any?.asMap(): Map<String, Any?> {
        require(this is Map<*, *> && keys.all { it is String }) { "Backup must contain mappings with string keys" }
        @Suppress("UNCHECKED_CAST") return this as Map<String, Any?>
    }

    fun readBounded(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return out.toByteArray()
            require(out.size().toLong() + size <= MAX_BYTES) { "Backup exceeds the 64 MiB limit" }
            out.write(buffer, 0, size)
        }
    }
    internal fun encode(bytes: ByteArray): String = base64.encodeToString(bytes)
    internal fun decode(value: Any?): ByteArray = Base64.getDecoder().decode(value as? String ?: error("Missing base64 data"))
}
