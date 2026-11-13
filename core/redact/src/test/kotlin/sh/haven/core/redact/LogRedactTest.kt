package sh.haven.core.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #518 — hostnames, ports, usernames and profile labels were written to logcat
 * in plain text, and a user filing a bug had to hand-redact their own log.
 *
 * The properties that matter are that the value does not survive into the token,
 * and that the token is still stable enough to follow a connection through a log.
 */
class LogRedactTest {

    @Test
    fun `the original value never appears in the token`() {
        val secrets = listOf(
            "bastion.internal.example.com",
            "192.168.1.10",
            "root",
            "work — prod db (bob@example.com)",
        )
        for (secret in secrets) {
            val token = LogRedact.of(secret)
            assertFalse("token leaked the value: $token", token.contains(secret))
            // Also guard against a partial leak — a substring long enough to guess from.
            for (part in secret.split(".", " ", "@").filter { it.length >= 4 }) {
                assertFalse(
                    "token leaked the fragment '$part': $token",
                    token.contains(part, ignoreCase = true),
                )
            }
        }
    }

    /** Two log lines about the same host must be tied together, or the log is useless. */
    @Test
    fun `the same value gives the same token within a process`() {
        assertEquals(LogRedact.of("host.example.com"), LogRedact.of("host.example.com"))
    }

    @Test
    fun `different values give different tokens`() {
        assertNotEquals(LogRedact.of("host-a.example.com"), LogRedact.of("host-b.example.com"))
    }

    /**
     * "No hostname" and "a hostname I'm not showing you" are different facts;
     * collapsing them wastes the reader's time.
     */
    @Test
    fun `null and blank are distinguishable and are not tokens`() {
        assertEquals("<null>", LogRedact.of(null))
        assertEquals("<blank>", LogRedact.of(""))
        assertEquals("<blank>", LogRedact.of("   "))
        assertNotEquals(LogRedact.of(null), LogRedact.of("null"))
    }

    @Test
    fun `tokens are short and visibly redacted`() {
        val token = LogRedact.of("something.example.com")
        assertTrue("should be marked as a token: $token", token.startsWith("~"))
        assertEquals("~ plus 8 hex chars", 9, token.length)
    }

    @Test
    fun `host keeps the port but redacts the host`() {
        val withPort = LogRedact.host("db.example.com", 2222)
        assertFalse(withPort.contains("db.example.com"))
        assertTrue("the port is useful and not identifying on its own", withPort.endsWith(":2222"))
        assertEquals(LogRedact.of("db.example.com"), LogRedact.host("db.example.com"))
    }

    /**
     * The salt is what makes this more than decoration: hostnames have so little
     * entropy that an unsalted digest of "192.168.1.10" falls to a wordlist. A
     * token must therefore NOT match the bare SHA-256 of its input.
     */
    @Test
    fun `tokens are not a bare digest of the value`() {
        val value = "192.168.1.10"
        val bare = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
        assertNotEquals("unsalted digest would be trivially reversible", "~$bare", LogRedact.of(value))
    }
}
