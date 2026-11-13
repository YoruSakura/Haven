package sh.haven.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #477: a reporter deleted three log attachments after noticing his Windows
 * account name was in every one, which stopped a diagnosis dead.
 *
 * The rule these pin is simple and easy to erode: whatever we print, none of
 * the name's characters may appear in it. The shape is kept deliberately —
 * #461 was sspi truncating `me@example.com` at the `@` under NLA, and "does
 * this name contain an @" was the question that found it.
 */
class RedactUsernameTest {

    /**
     * The property that matters: the output tells you nothing about the
     * *content*. Two different names of the same length and qualification
     * style must redact to the same string.
     *
     * My first attempt asserted "no character of the name appears in the
     * output", which is a different and wrong property — the word "chars"
     * contains c, h, a, r and s, so any username using those letters failed a
     * correct implementation. Worth leaving noted: the test was the thing
     * that was broken.
     */
    @Test
    fun `names of the same shape are indistinguishable after redaction`() {
        assertEquals(redactUsername("alice"), redactUsername("bobby"))
        assertEquals(redactUsername("Quickemu"), redactUsername("zzzzzzzz"))
        assertEquals(redactUsername("me@aa.com"), redactUsername("yo@bb.org"))
        assertEquals(redactUsername("CORP\\bob"), redactUsername("ACME\\joe"))
    }

    /** And the name itself never appears. */
    @Test
    fun `the username is never quoted back`() {
        listOf(
            "Quickemu",
            "alice",
            "CORP\\bob",
            "me@example.com",
            "MicrosoftAccount\\someone@outlook.com",
            "Ian Williams",
        ).forEach { name ->
            assertFalse(
                "redaction of '$name' quoted it back",
                redactUsername(name).contains(name),
            )
        }
    }

    @Test
    fun `the qualification style is kept, because it is what found 461`() {
        assertEquals("<14 chars, upn>", redactUsername("me@example.com"))
        assertEquals("<8 chars, domain\\user>", redactUsername("CORP\\bob"))
        assertEquals("<5 chars>", redactUsername("alice"))
    }

    @Test
    fun `an empty username says so rather than printing nothing`() {
        assertEquals("<none>", redactUsername(""))
    }

    /**
     * Length is a weak identifier on its own but a strong one combined with a
     * guess, so it is worth being deliberate that this is the trade: the
     * length is kept because "the server rejected an 18-character UPN" is a
     * usable report and "the server rejected a username" is not.
     */
    @Test
    fun `length is reported`() {
        assertEquals("<3 chars>", redactUsername("abc"))
        assertEquals("<30 chars>", redactUsername("a".repeat(30)))
    }

    /**
     * The follow-up on the same issue: the address and port went out in the
     * clear next to the (redacted) name. Same rule, same reason.
     */
    @Test
    fun `the host is never quoted back`() {
        listOf(
            "192.168.1.100" to 3389,
            "10.0.0.7" to 13389,
            "desktop.lan" to 3389,
            "rdp.example.com" to 3391,
            "fe80::1" to 3389,
        ).forEach { (host, port) ->
            val out = redactHost(host, port)
            assertFalse("redaction of '$host' quoted it back", out.contains(host))
            assertFalse("redaction of '$host:$port' quoted the port back", out.contains("$port"))
        }
    }

    @Test
    fun `the host kind is kept, because DNS and routing fail differently`() {
        assertEquals("<ipv4>", redactHost("192.168.1.100", 3389))
        assertEquals("<hostname>", redactHost("desktop.lan", 3389))
        assertEquals("<ipv6>", redactHost("fe80::1", 3389))
        assertEquals("<no host>", redactHost("", 3389))
    }

    /** A non-default port is a real clue; the number itself is not needed. */
    @Test
    fun `only the fact of a non-default port survives`() {
        assertEquals("<ipv4>", redactHost("10.0.0.7", 3389))
        assertEquals("<ipv4>:<non-default port>", redactHost("10.0.0.7", 13389))
        assertEquals(redactHost("10.0.0.7", 3390), redactHost("10.0.0.7", 33890))
    }
}
