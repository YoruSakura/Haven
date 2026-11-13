package sh.haven.core.ssh.backup

import sh.haven.core.ssh.backup.SshPilotBackupCodec.asMap

/** Reads only text embedded in the backup. Never follows files or runs Match/ProxyCommand. */
internal class PortableSshConfig(manifest: Map<String, Any?>, val warnings: MutableSet<String>) {
    data class Directive(val patterns: List<String>, val key: String, val values: List<String>)
    private val directives = mutableListOf<Directive>()
    val aliases = linkedSetOf<String>()
    private val root = manifest["ssh_config_root"] as? String ?: ""
    private val home = manifest["source_home"] as? String ?: ""
    private val files = (manifest["ssh_config_files"] as? Map<*, *>)?.entries
        ?.associate { it.key.toString() to (it.value as? String ?: error("Invalid SSH config text")) }.orEmpty()

    init {
        var patterns = listOf("*")
        fun parse(text: String, stack: Set<String>) {
            require(stack.size <= 16) { "Too many nested SSH Includes" }
            text.lineSequence().forEach { line ->
                val tokens = tokenize(line)
                if (tokens.isEmpty()) return@forEach
                val key = tokens.first().lowercase()
                val args = tokens.drop(1)
                when (key) {
                    "include" -> args.forEach { path ->
                        val relative = path.removePrefix("$root/").removePrefix("$home/.ssh/").removePrefix("~/.ssh/")
                        val matched = files.keys.filter { glob(relative, it) }.sorted()
                        if (matched.isEmpty()) warnings += "include"
                        matched.forEach { name ->
                            require(name !in stack) { "Recursive SSH Include" }
                            parse(files.getValue(name), stack + name)
                        }
                    }
                    "host" -> {
                        patterns = args
                        aliases += args.filter { it.none { ch -> ch in "*?!" } }
                    }
                    "match" -> { patterns = emptyList(); warnings += "match" }
                    else -> if (args.isNotEmpty()) directives += Directive(patterns, key, args)
                }
            }
        }
        val main = manifest["ssh_config_main_rel"] as? String ?: ""
        parse(files[main] ?: manifest["ssh_config"] as? String ?: "", setOf(main))
    }

    fun options(alias: String): Map<String, List<String>> {
        val out = linkedMapOf<String, List<String>>()
        for (d in directives) {
            if (d.patterns.none { !it.startsWith('!') && glob(it, alias) } ||
                d.patterns.any { it.startsWith('!') && glob(it.drop(1), alias) }) continue
            if (d.key == "identityfile") out[d.key] = out[d.key].orEmpty() + d.values
            else out.putIfAbsent(d.key, d.values)
        }
        return out
    }

    companion object {
        fun glob(pattern: String, text: String): Boolean = Regex(pattern.map {
            when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) }
        }.joinToString(""), RegexOption.IGNORE_CASE).matches(text)

        fun tokenize(line: String): List<String> {
            val result = mutableListOf<String>()
            val token = StringBuilder()
            var quote: Char? = null
            var escaped = false
            var started = false
            fun flush() { if (started) { result += token.toString(); token.clear(); started = false } }
            for (ch in line) {
                when {
                    escaped -> { token.append(ch); started = true; escaped = false }
                    ch == '\\' -> { escaped = true; started = true }
                    quote != null -> if (ch == quote) quote = null else token.append(ch)
                    ch == '"' || ch == '\'' -> { quote = ch; started = true }
                    ch == '#' -> break
                    ch.isWhitespace() || ch == '=' && result.isEmpty() -> flush()
                    else -> { token.append(ch); started = true }
                }
            }
            require(!escaped && quote == null) { "Invalid SSH config quoting" }
            flush()
            return result
        }
    }
}
