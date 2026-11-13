package sh.haven.core.fido

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the CBOR pieces behind the either/or SK auth (#237): a GetAssertion
 * whose allowList carries several credentials, and pulling the
 * authenticator-reported "which credential I used" out of the response so we
 * learn which of the configured keys the user presented.
 */
class Ctap2EitherOrTest {

    private fun ByteArray.containsSub(sub: ByteArray): Boolean {
        if (sub.isEmpty() || sub.size > size) return false
        outer@ for (i in 0..size - sub.size) {
            for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer
            return true
        }
        return false
    }

    /** CBOR-encode a byte string (definite length). */
    private fun cborBytes(b: ByteArray): ByteArray =
        if (b.size < 24) byteArrayOf((0x40 or b.size).toByte()) + b
        else byteArrayOf(0x58.toByte(), b.size.toByte()) + b

    private fun cborText(s: String): ByteArray {
        val t = s.toByteArray()
        return byteArrayOf((0x60 or t.size).toByte()) + t
    }

    @Test
    fun `multi-credential allowList carries every credential`() {
        val c1 = byteArrayOf(1, 2, 3, 4)
        val c2 = byteArrayOf(5, 6, 7, 8)
        val cmd = Ctap2Cbor.encodeGetAssertionCommand(
            rpId = "ssh:",
            clientDataHash = ByteArray(32),
            credentialIds = listOf(c1, c2),
            up = false,
        )
        // Each credential id appears as a 4-byte CBOR byte string (0x44 …).
        assertTrue("c1 in allowList", cmd.containsSub(byteArrayOf(0x44, 1, 2, 3, 4)))
        assertTrue("c2 in allowList", cmd.containsSub(byteArrayOf(0x44, 5, 6, 7, 8)))
    }

    @Test
    fun `single-credential overload still works and reports no used credential`() {
        // Build a minimal response WITHOUT a credential field (key 1 absent).
        val authData = ByteArray(37) { it.toByte() }
        val sig = byteArrayOf(9, 9, 9, 9)
        val resp = byteArrayOf(0xA2.toByte()) +      // map(2)
            byteArrayOf(0x02) + cborBytes(authData) + // 2: authData
            byteArrayOf(0x03) + cborBytes(sig)        // 3: signature
        val decoded = Ctap2Cbor.decodeGetAssertionResponse(resp)
        assertArrayEquals(authData, decoded.authData)
        assertNull(decoded.usedCredentialId)
    }

    @Test
    fun `decode extracts the used credential id from response key 1`() {
        val credId = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val authData = ByteArray(37) { it.toByte() }
        val sig = byteArrayOf(7, 7, 7, 7)
        val credMap = byteArrayOf(0xA2.toByte()) +
            cborText("id") + cborBytes(credId) +
            cborText("type") + cborText("public-key")
        val resp = byteArrayOf(0xA3.toByte()) +       // map(3)
            byteArrayOf(0x01) + credMap +              // 1: credential {id, type}
            byteArrayOf(0x02) + cborBytes(authData) +  // 2: authData
            byteArrayOf(0x03) + cborBytes(sig)         // 3: signature
        val decoded = Ctap2Cbor.decodeGetAssertionResponse(resp)
        assertArrayEquals(credId, decoded.usedCredentialId)
        assertArrayEquals(authData, decoded.authData)
        assertArrayEquals(sig, decoded.signature)
    }
}
