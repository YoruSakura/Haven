package sh.haven.core.redact

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Short, stable stand-ins for values that must not reach logcat (#518).
 *
 * Haven logged hostnames, ports, usernames, profile labels and SSH key labels in
 * plain text. Since Android 4.1 another app cannot read our logcat, so the
 * exposure is not silent harvesting — it is **sharing**. `adb logcat` and
 * `adb bugreport` capture it, Haven shows logcat in Settings and over MCP, and
 * v5.87.3 actively asks users to paste crash reports into bug tickets. #518 was
 * filed by someone who had to hand-redact their own log to file it safely, which
 * is the whole argument in one sentence.
 *
 * Deleting the log lines was the alternative and is worse: knowing *which*
 * connection failed, and in what order, is most of what makes a support log
 * usable. A token preserves that and reveals nothing.
 *
 * ★ **Salted per process, on purpose.** Hostnames have very little entropy —
 * an unsalted hash of "192.168.1.10" or "github.com" falls to a wordlist in
 * seconds, so a bare digest would be redaction in appearance only. A random
 * per-process salt means tokens correlate within one log (which is what a
 * debugger needs) and are useless outside it. The cost is that the same host
 * reads differently across app restarts; that is the right trade.
 *
 * This is not anonymisation of a data set. It stops values being *transcribed*
 * into a log that gets shared. Someone who already knows the candidate hosts and
 * has the same running process could confirm a guess — offline, after the fact,
 * they cannot.
 */
object LogRedact {

    private val salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /**
     * An 8-character token for [value], stable for this process.
     *
     * Null and blank map to fixed markers rather than a token, because "there was
     * no hostname" and "there was a hostname I am not showing you" are different
     * facts and a log that conflates them wastes the reader's time.
     */
    fun of(value: String?): String {
        if (value == null) return "<null>"
        if (value.isBlank()) return "<blank>"
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(value.toByteArray())
        }.digest()
        val hex = StringBuilder(TOKEN_CHARS)
        for (i in 0 until TOKEN_CHARS / 2) {
            hex.append(HEX[(digest[i].toInt() shr 4) and 0xF])
            hex.append(HEX[digest[i].toInt() and 0xF])
        }
        return "~$hex"
    }

    /**
     * A host, and its port only when it is not the well-known one for the scheme.
     *
     * The port is included because it is weak on its own and genuinely useful
     * when diagnosing a connection — but it is attached to a redacted host, so it
     * does not identify a machine by itself.
     */
    fun host(host: String?, port: Int? = null): String =
        if (port == null) of(host) else "${of(host)}:$port"

    private const val TOKEN_CHARS = 8
    private val HEX = "0123456789abcdef".toCharArray()
}
