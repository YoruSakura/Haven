package sh.haven.feature.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins rdpErrorHint's patterns to the EXACT error strings users reported —
 * hints die by pattern drift, so each case is a verbatim on-screen error.
 */
class RdpErrorHintTest {

    /** #461: NLA off in Haven, required by Windows (HYBRID_REQUIRED_BY_SERVER). */
    @Test
    fun `negotiation FailureCode 5 maps to the NLA-required hint`() {
        val raw = "RDP negotiation failed: Error { context: \"negotiation failure\", " +
            "kind: Negotiation(NegotiationFailure(FailureCode(5))), source: None }"
        assertEquals(R.string.rdp_hint_nla_required_title, rdpErrorHint(raw)?.titleRes)
    }

    /** #461: sspi-rs refuses MicrosoftAccount\you@example.com as MixedFormat. */
    @Test
    fun `MixedFormat invalid username maps to the Microsoft-account hint`() {
        val raw = "RDP connect finalize failed: Error { context: \"invalid username\", " +
            "kind: Custom, source: Some(MixedFormat) }"
        assertEquals(R.string.rdp_hint_mixed_username_title, rdpErrorHint(raw)?.titleRes)
    }

    @Test
    fun `unrelated errors still get no hint`() {
        assertNull(rdpErrorHint("Read PDU error: UnexpectedEof"))
    }
}
