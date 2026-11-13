package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #494: a granted-then-dropped battery exemption must re-open the offer —
 * Realme UI resets it on app update, and the old one-shot dismissal meant the
 * one prompt that would have fixed the disconnects never came back.
 */
class BatteryPromptPolicyTest {

    @Test
    fun `exempt now - nothing to do regardless of history`() {
        for (last in listOf(null, true, false)) {
            for (dismissed in listOf(true, false)) {
                assertEquals(
                    BatteryPromptAction.NONE,
                    batteryPromptAction(true, last, dismissed, neverAsk = false),
                )
            }
        }
    }

    @Test
    fun `first run offers`() {
        assertEquals(
            BatteryPromptAction.OFFER,
            batteryPromptAction(exemptNow = false, lastKnownExempt = null, dismissed = false, neverAsk = false),
        )
    }

    @Test
    fun `episodic dismissal holds while nothing changed`() {
        assertEquals(
            BatteryPromptAction.NONE,
            batteryPromptAction(exemptNow = false, lastKnownExempt = false, dismissed = true, neverAsk = false),
        )
    }

    @Test
    fun `a detected drop re-offers even over an old dismissal`() {
        assertEquals(
            BatteryPromptAction.OFFER_DROPPED,
            batteryPromptAction(exemptNow = false, lastKnownExempt = true, dismissed = true, neverAsk = false),
        )
    }

    @Test
    fun `never-ask survives a drop`() {
        assertEquals(
            BatteryPromptAction.NONE,
            batteryPromptAction(exemptNow = false, lastKnownExempt = true, dismissed = false, neverAsk = true),
        )
    }
}
