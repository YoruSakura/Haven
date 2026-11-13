package sh.haven.core.fido

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #449: after the PIN exchange succeeded, a YubiKey 5.4.3 rejected credential
 * enumeration with `CTAP2 error 0x01` (CTAP1_ERR_INVALID_COMMAND).
 *
 * Credential management exists in two forms: CTAP 2.1 standardised it as
 * command 0x0A, but it shipped first as the 2.1-PRE prototype 0x41, which is
 * all a YubiKey below firmware 5.5 answers. Haven only ever sent 0x0A.
 */
class CredentialManagementPreviewTest {

    /** GetInfo options map: { 4: { "clientPin": true, <extra> } } as CBOR. */
    private fun getInfoWith(vararg options: Pair<String, Boolean>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // map(1) { 4: map(n) { ... } }
        out.write(0xA1)
        out.write(0x04)
        out.write(0xA0 or options.size)
        options.forEach { (k, v) ->
            out.write(0x60 or k.length)
            out.write(k.toByteArray(Charsets.US_ASCII))
            out.write(if (v) 0xF5 else 0xF4)
        }
        return out.toByteArray()
    }

    @Test
    fun `a CTAP 2_1 key advertises credMgmt`() {
        val info = Ctap2Cbor.decodeGetInfoResponse(getInfoWith("credMgmt" to true))
        assertTrue(info.credMgmtSupported)
        assertFalse(info.credMgmtPreviewSupported)
    }

    /** The #449 key: prototype only. */
    @Test
    fun `a pre-5_5 YubiKey advertises only credentialMgmtPreview`() {
        val info = Ctap2Cbor.decodeGetInfoResponse(getInfoWith("credentialMgmtPreview" to true))
        assertFalse(
            "sending 0x0A to this key returns CTAP1_ERR_INVALID_COMMAND",
            info.credMgmtSupported,
        )
        assertTrue(info.credMgmtPreviewSupported)
    }

    /** A key with neither cannot enumerate at all, and must not be guessed at. */
    @Test
    fun `a key with neither option reports both false`() {
        val info = Ctap2Cbor.decodeGetInfoResponse(getInfoWith("clientPin" to true))
        assertFalse(info.credMgmtSupported)
        assertFalse(info.credMgmtPreviewSupported)
    }

    /** The command byte is the whole fix — the bodies are identical. */
    @Test
    fun `preview changes only the command byte`() {
        val standard = Ctap2Cbor.encodeCredentialManagementCommand(
            subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_RPS_BEGIN,
            preview = false,
        )
        val preview = Ctap2Cbor.encodeCredentialManagementCommand(
            subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_RPS_BEGIN,
            preview = true,
        )
        assertEquals(0x0A.toByte(), standard[0])
        assertEquals(0x41.toByte(), preview[0])
        assertArrayEqualsMsg(
            "only the command byte may differ",
            standard.copyOfRange(1, standard.size),
            preview.copyOfRange(1, preview.size),
        )
    }

    /** Default must stay the standard command, so nothing changes for modern keys. */
    @Test
    fun `default is the standardised command`() {
        val cmd = Ctap2Cbor.encodeCredentialManagementCommand(
            subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_RPS_BEGIN,
        )
        assertEquals(0x0A.toByte(), cmd[0])
    }

    private fun assertArrayEqualsMsg(msg: String, a: ByteArray, b: ByteArray) {
        assertEquals(msg, a.toList(), b.toList())
    }
}
