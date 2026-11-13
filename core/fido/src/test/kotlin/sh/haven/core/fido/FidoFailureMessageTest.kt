package sh.haven.core.fido

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #449: importing a resident key over NFC surfaced Android's own wording for a
 * lifted tag — "Permission Denial: Tag ( ID: 27 77 A3 97 01 A3 77 ) is out of
 * date" — which reads like a permissions bug in Haven rather than "the key left
 * the field".
 */
class FidoFailureMessageTest {

    /** The exact text from the #449 logcat. */
    private val tagLost = SecurityException(
        "Permission Denial: Tag ( ID: 27 77 A3 97 01 A3 77 ) is out of date",
    )

    @Test
    fun `a lifted NFC tag is explained in the user's terms`() {
        val msg = fidoFailureMessage(tagLost)
        assertNotNull("the #449 error must be recognised", msg)
        assertTrue(
            "should say what to do, not quote Android internals: $msg",
            msg!!.contains("Hold it flat"),
        )
        assertTrue("should not leak the raw wording", !msg.contains("Permission Denial"))
    }

    /**
     * The other wording for the same physical event, seen on-device while
     * verifying the fix: the field breaks mid-transceive rather than the handle
     * going stale. TagLostException carries this message.
     */
    @Test
    fun `a tag lost mid-exchange gets the same explanation`() {
        val msg = fidoFailureMessage(java.io.IOException("Tag was lost."))
        assertNotNull("TagLostException's wording must be recognised too", msg)
        assertTrue(msg!!.contains("Hold it flat"))
    }

    /** Anything else falls through so the caller keeps the raw detail. */
    @Test
    fun `unrelated failures are not rewritten`() {
        assertNull(fidoFailureMessage(java.io.IOException("CTAP error 0x31")))
        assertNull(fidoFailureMessage(SecurityException("USB permission denied")))
    }

    /** A null message must not blow up the mapper. */
    @Test
    fun `a message-less exception is handled`() {
        assertNull(fidoFailureMessage(SecurityException()))
    }
}
