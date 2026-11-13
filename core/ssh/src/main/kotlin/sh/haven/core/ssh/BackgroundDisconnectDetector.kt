package sh.haven.core.ssh

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recognises the one disconnect shape Haven can attribute rather than merely
 * report: a session that dies within seconds of the app going to the background,
 * while the process and its foreground-service notification are both still alive.
 *
 * Why this exists (#495, from #494): a Realme UI / Android 16 user lost SSH
 * sessions about ten seconds after switching away, every time. Haven was not
 * disconnecting them — the ROM was cutting the network out from under a live
 * foreground service and leaving everything else running. Enabling "allow full
 * background operation" in the ROM's own settings fixed it completely.
 *
 * ★ The reason Haven couldn't say so: `PowerManager.isIgnoringBatteryOptimizations()`
 * only knows about *Android's* battery setting. It cannot see the separate
 * vendor switches that ColorOS/Realme UI, MIUI/HyperOS, EMUI and OriginOS keep
 * in their own settings screens — and on that device it was reporting "exempt,
 * all good" the entire time the restriction that actually killed connections was
 * on. Being confidently wrong is worse than saying nothing.
 *
 * No API can read those switches, so this infers from the symptom instead. That
 * is deliberately a *hint*, never a diagnosis: a genuine network change at the
 * moment you pocket the phone produces the same shape.
 */
@Singleton
class BackgroundDisconnectDetector @Inject constructor() {

    /** Test seam: monotonic milliseconds. Real one is `SystemClock.elapsedRealtime`. */
    internal var nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    @Volatile
    private var backgroundedAt: Long? = null

    fun onBackgrounded() {
        backgroundedAt = nowMs()
    }

    fun onForegrounded() {
        backgroundedAt = null
    }

    /**
     * True when a disconnect happening *now* falls inside the suspicious window
     * after backgrounding. False while foregrounded, and false once the window
     * has passed — a session that survived a minute in the background and then
     * dropped is an ordinary drop, not a ROM cutting it off.
     */
    fun looksLikeBackgroundRestriction(): Boolean {
        val at = backgroundedAt ?: return false
        return nowMs() - at <= WINDOW_MS
    }

    companion object {
        /**
         * The reporter measured "about 10 seconds, faster if you bring another
         * app to the front". 20 s leaves margin for a slower device without
         * reaching far enough to catch ordinary drops.
         */
        const val WINDOW_MS = 20_000L

        /**
         * Where the vendor keeps the switch that Android's own battery-optimisation
         * exemption does not cover. Returns null for manufacturers with no known
         * extra restriction, so the caller can fall back to generic wording rather
         * than inventing a settings path that doesn't exist.
         *
         * Named per vendor because "check your battery settings" is what everyone
         * has already tried — the whole value is pointing at the *second* switch.
         */
        fun vendorBackgroundSettingHint(manufacturer: String): String? =
            when (manufacturer.lowercase()) {
                "realme", "oppo", "oneplus" ->
                    "Settings → Battery → App battery management → Haven → " +
                        "allow background activity, and lock Haven in recent apps"
                "xiaomi", "redmi", "poco" ->
                    "Settings → Apps → Haven → Battery saver → No restrictions, " +
                        "and Autostart on"
                "huawei", "honor" ->
                    "Settings → Battery → App launch → Haven → Manage manually, " +
                        "with all three switches on"
                "vivo", "iqoo" ->
                    "Settings → Battery → High background power consumption → " +
                        "allow Haven, and Autostart on"
                "samsung" ->
                    "Settings → Apps → Haven → Battery → Unrestricted"
                else -> null
            }
    }
}
