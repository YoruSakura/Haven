package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector decides whether Haven is allowed to *blame the ROM* for a
 * disconnect, so the false-positive cases matter more than the positive one —
 * telling someone their phone is at fault when it isn't sends them into a
 * settings screen to fix nothing.
 */
class BackgroundDisconnectDetectorTest {

    private var clock = 0L
    private fun detector() = BackgroundDisconnectDetector().apply { nowMs = { clock } }

    @Test
    fun `a drop just after backgrounding is attributable`() {
        val d = detector()
        d.onBackgrounded()
        clock += 10_000
        assertTrue(d.looksLikeBackgroundRestriction())
    }

    @Test
    fun `a drop while in the foreground is not`() {
        val d = detector()
        // Never backgrounded at all.
        assertFalse(d.looksLikeBackgroundRestriction())
    }

    @Test
    fun `a drop long after backgrounding is an ordinary drop`() {
        val d = detector()
        d.onBackgrounded()
        clock += BackgroundDisconnectDetector.WINDOW_MS + 1
        assertFalse(
            "a session that survived the window and then died is not the ROM",
            d.looksLikeBackgroundRestriction(),
        )
    }

    @Test
    fun `returning to the foreground clears the window`() {
        val d = detector()
        d.onBackgrounded()
        clock += 1_000
        d.onForegrounded()
        // Still within 20s of the *backgrounding*, but the user is back — a drop
        // now is happening in front of them and is not a background restriction.
        assertFalse(d.looksLikeBackgroundRestriction())
    }

    @Test
    fun `the boundary is inclusive`() {
        val d = detector()
        d.onBackgrounded()
        clock += BackgroundDisconnectDetector.WINDOW_MS
        assertTrue(d.looksLikeBackgroundRestriction())
    }

    @Test
    fun `re-backgrounding restarts the window`() {
        val d = detector()
        d.onBackgrounded()
        clock += BackgroundDisconnectDetector.WINDOW_MS + 5_000
        assertFalse(d.looksLikeBackgroundRestriction())
        d.onForegrounded()
        d.onBackgrounded()
        clock += 500
        assertTrue(d.looksLikeBackgroundRestriction())
    }

    // --- vendor hints ---

    @Test
    fun `known vendors get their own settings path, case-insensitively`() {
        for (m in listOf("realme", "Realme", "OPPO", "OnePlus", "Xiaomi", "Redmi", "POCO", "HUAWEI", "honor", "vivo", "iQOO", "samsung")) {
            assertTrue(
                "expected a hint for $m",
                !BackgroundDisconnectDetector.vendorBackgroundSettingHint(m).isNullOrBlank(),
            )
        }
    }

    @Test
    fun `an unknown vendor gets no invented settings path`() {
        // Better to fall back to generic wording than name a screen that does
        // not exist on the user's phone.
        assertNull(BackgroundDisconnectDetector.vendorBackgroundSettingHint("Google"))
        assertNull(BackgroundDisconnectDetector.vendorBackgroundSettingHint("Fairphone"))
        assertNull(BackgroundDisconnectDetector.vendorBackgroundSettingHint(""))
    }

    @Test
    fun `the realme hint names the switch that actually fixed 494`() {
        val hint = BackgroundDisconnectDetector.vendorBackgroundSettingHint("realme")!!
        assertTrue("must mention background activity", hint.contains("background activity"))
        assertEquals(
            "realme and oppo share ColorOS, so they must give the same guidance",
            hint,
            BackgroundDisconnectDetector.vendorBackgroundSettingHint("oppo"),
        )
    }
}
