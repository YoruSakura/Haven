package sh.haven.feature.keys

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.fido.SkKeyData

/**
 * #449: a reporter with many resident keys had to tick each row to find out
 * what it was called, because the picker showed rpId and the name only
 * appeared once selected. The name now leads the row, which makes the label
 * the picker *shows* and the label the import *saves* the same value —
 * previously two copies of this rule, one of which had already drifted.
 */
class DiscoveredSkCredentialLabelTest {

    private fun cred(userName: String?, rpId: String = "ssh:example.com") =
        DiscoveredSkCredential(
            id = "0",
            rpId = rpId,
            algorithmName = "sk-ecdsa-sha2-nistp256@openssh.com",
            fingerprint = "SHA256:abc",
            data = SkKeyData("sk-ecdsa", ByteArray(0), rpId, ByteArray(0), 0),
            userName = userName,
        )

    /** The whole point of the change: the key's own name is what you see. */
    @Test
    fun `prefers the name stored on the credential`() {
        assertEquals("work laptop", cred("work laptop").defaultLabel)
    }

    /** Keys created without a name still need something to show. */
    @Test
    fun `falls back to the rpId when the credential carries no name`() {
        assertEquals("FIDO2: ssh:example.com", cred(null).defaultLabel)
    }

    /**
     * An authenticator reporting an empty or whitespace name must not
     * produce a blank row — that would be worse than the generated name,
     * since a blank row is indistinguishable from every other blank row.
     */
    @Test
    fun `treats a blank name as absent`() {
        assertEquals("FIDO2: ssh:example.com", cred("").defaultLabel)
        assertEquals("FIDO2: ssh:example.com", cred("   ").defaultLabel)
    }

    /**
     * The collision the per-key label exists to solve (#231): two dongles
     * both exposing a bare `ssh:` rpId. Distinct names must survive.
     */
    @Test
    fun `distinct names disambiguate credentials sharing an rpId`() {
        val a = cred("yubikey blue", rpId = "ssh:")
        val b = cred("yubikey black", rpId = "ssh:")
        assertEquals("yubikey blue", a.defaultLabel)
        assertEquals("yubikey black", b.defaultLabel)
    }
}
