package sh.haven.feature.connections

/**
 * What the Connections screen should do about the battery-exemption prompt,
 * given the current platform state and the stored history (#494).
 *
 * The old behaviour was a one-shot: any dismissal was forever, so when a ROM
 * quietly re-enabled optimisation on an app update (Realme UI does), the very
 * prompt that would have fixed it never came back. The policy now
 * distinguishes an episodic "not now" from a permanent "don't ask again",
 * and a *detected drop* — exemption previously observed on, now off — starts
 * a new episode.
 */
enum class BatteryPromptAction {
    /** Nothing to do; also record the currently-exempt observation. */
    NONE,
    /** Offer the prompt with the first-run wording. */
    OFFER,
    /**
     * Offer the prompt with the "your exemption was switched off" wording —
     * the drop was detected, and saying why beats asking again cold.
     */
    OFFER_DROPPED,
}

fun batteryPromptAction(
    exemptNow: Boolean,
    lastKnownExempt: Boolean?,
    dismissed: Boolean,
    neverAsk: Boolean,
): BatteryPromptAction {
    if (exemptNow) return BatteryPromptAction.NONE
    if (neverAsk) return BatteryPromptAction.NONE
    // The drop overrides an old episodic dismissal — that dismissal answered
    // a different question, back when the exemption was still granted.
    if (lastKnownExempt == true) return BatteryPromptAction.OFFER_DROPPED
    return if (dismissed) BatteryPromptAction.NONE else BatteryPromptAction.OFFER
}
