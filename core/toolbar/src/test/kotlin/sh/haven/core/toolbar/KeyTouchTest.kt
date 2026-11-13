package sh.haven.core.toolbar

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #515 — "Arrow buttons stop working after a few taps."
 *
 * The reported symptom was a toolbar arrow that still highlighted on touch but
 * sent nothing, and stayed that way until the session was closed and reopened.
 * Two independent latches produced it, and each gets a test here:
 *
 *  1. The touch filter switched on `MotionEvent.action`. Compose hands the
 *     callback the window's original event, so once a second finger is down the
 *     release arrives as ACTION_POINTER_UP — a *packed* value (0x0106 for pointer
 *     index 1), never equal to ACTION_UP. It fell through to the ignore branch
 *     and neither flag was cleared.
 *  2. `didRepeat` was reset at the top of the repeat effect rather than on
 *     release. A DOWN and UP landing in the same frame leave `pressed` unchanged
 *     as far as composition can see, so the effect never relaunches and the flag
 *     stays true for the life of the composable.
 *
 * With `didRepeat` latched, every later tap takes the "this is the tail of a
 * hold" path and emits no click. The flags live inside `key(sessionId)`, which is
 * why only a new session cleared it.
 */
class KeyTouchTest {

    /** ACTION_POINTER_UP as the platform really delivers it: pointer index 1 packed into bits 8-15. */
    private val pointerUpIndex1 =
        MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private val pointerDownIndex1 =
        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Test
    fun `a plain tap emits one click`() {
        val down = keyTouch(MotionEvent.ACTION_DOWN, pressed = false, didRepeat = false)
        assertTrue(down.pressed)
        assertFalse("pressing must not click; the click belongs to the release", down.emitClick)

        val up = keyTouch(MotionEvent.ACTION_UP, down.pressed, down.didRepeat)
        assertTrue("a tap that never repeated must click on release", up.emitClick)
        assertFalse(up.pressed)
    }

    @Test
    fun `a hold does not add a click on release`() {
        val up = keyTouch(MotionEvent.ACTION_UP, pressed = true, didRepeat = true)
        assertFalse("the repeat loop already delivered these keys", up.emitClick)
    }

    /** Latch 1: the release arrives packed, and must still be treated as a release. */
    @Test
    fun `a packed pointer-up releases the key`() {
        val result = keyTouch(pointerUpIndex1, pressed = true, didRepeat = true)

        assertTrue(
            "ACTION_POINTER_UP must be recognised — masking it off is the whole point",
            result.consumed,
        )
        assertFalse("a release that leaves pressed set never stops the repeat loop", result.pressed)
        assertFalse("a release that leaves didRepeat set kills every later tap", result.didRepeat)
    }

    @Test
    fun `a packed pointer-down presses the key`() {
        val result = keyTouch(pointerDownIndex1, pressed = false, didRepeat = false)
        assertTrue(result.pressed)
        assertTrue(result.consumed)
    }

    /** Latch 2: no terminal event may leave didRepeat set, whatever it was before. */
    @Test
    fun `every release clears didRepeat`() {
        val releases = mapOf(
            "ACTION_UP" to MotionEvent.ACTION_UP,
            "ACTION_POINTER_UP" to pointerUpIndex1,
            "ACTION_CANCEL" to MotionEvent.ACTION_CANCEL,
        )
        for ((name, action) in releases) {
            val result = keyTouch(action, pressed = true, didRepeat = true)
            assertFalse("$name left didRepeat set", result.didRepeat)
            assertFalse("$name left pressed set", result.pressed)
        }
    }

    /**
     * The reported sequence end to end: hold one arrow past the repeat threshold,
     * release it while a second finger is on the toolbar, then tap it again. The
     * second tap has to type.
     */
    @Test
    fun `holding then releasing under multi-touch leaves the key usable`() {
        var pressed = keyTouch(MotionEvent.ACTION_DOWN, false, false).pressed
        var didRepeat = true // the repeat loop has been running for a while

        // Second finger lands elsewhere on the toolbar; this key sees the packed form.
        keyTouch(pointerDownIndex1, pressed, didRepeat).let {
            pressed = it.pressed
            didRepeat = it.didRepeat
        }
        // Lift off this key while the other finger is still down.
        keyTouch(pointerUpIndex1, pressed, didRepeat).let {
            pressed = it.pressed
            didRepeat = it.didRepeat
        }

        val tapDown = keyTouch(MotionEvent.ACTION_DOWN, pressed, didRepeat)
        val tapUp = keyTouch(MotionEvent.ACTION_UP, tapDown.pressed, tapDown.didRepeat)
        assertTrue("the arrow is dead — this is the bug in #515", tapUp.emitClick)
    }

    /**
     * A tap whose DOWN and UP collapse into one frame: composition never observes
     * `pressed` change, so the repeat effect never runs. The transition must not
     * depend on it having run.
     */
    @Test
    fun `taps still click when the repeat effect never gets to run`() {
        var pressed = false
        var didRepeat = false
        repeat(5) { i ->
            val down = keyTouch(MotionEvent.ACTION_DOWN, pressed, didRepeat)
            val up = keyTouch(MotionEvent.ACTION_UP, down.pressed, down.didRepeat)
            assertTrue("tap $i sent nothing", up.emitClick)
            pressed = up.pressed
            didRepeat = up.didRepeat
        }
    }

    @Test
    fun `unrelated events are left alone`() {
        val result = keyTouch(MotionEvent.ACTION_MOVE, pressed = true, didRepeat = true)
        assertFalse("a move must not steal the event from the scrolling parent", result.consumed)
        assertTrue("a move must not disturb the press state", result.pressed)
        assertTrue(result.didRepeat)
    }
}
