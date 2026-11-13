package sh.haven.core.ssh

/**
 * One transport's contribution to the cross-transport operations in
 * [SessionManagerRegistry].
 *
 * The registry used to name all twelve managers in its constructor, which gave
 * `:core:ssh` a compile dependency on every transport module — including
 * `:core:rdp`, so a build with no remote desktop still pulled the RDP client
 * and its native library in (#510). Transports now contribute themselves with
 * `@IntoSet` from a module that already sees them, and the registry knows only
 * this interface.
 *
 * The defaults are deliberately the *inert* answers, so a transport declares
 * only what it actually participates in. A manager that forgets to override
 * [sessions] disappears from session lists rather than crashing — which is the
 * failure mode this registry exists to prevent, so a new transport should be
 * added with a test that asserts it appears.
 */
interface TransportSessionManager {

    /** Identifies this transport in the unified [Session] view. */
    val transport: Transport

    /** Disconnect every session this transport holds for [profileId]. */
    fun removeAllSessionsForProfile(profileId: String)

    /**
     * Every session this transport knows about as a unified [Session],
     * including inactive ones (DISCONNECTED, ERROR), so consumers can present
     * a full registered-session list rather than only live ones.
     *
     * Empty for handles that are not live sessions: rclone remotes are storage
     * handles, so they are disconnected with everything else but never appear
     * in a session list nor hold the foreground service up (#363).
     */
    val sessions: List<Session> get() = emptyList()

    /** How many sessions are CONNECTED / CONNECTING / RECONNECTING. */
    val activeSessionCount: Int get() = 0

    /**
     * Display name for this transport in terminal-input errors, or null when
     * it has no PTY-like input to write to. Doubles as the opt-in for
     * [sendInput] — [SessionManagerRegistry] only offers a session id to
     * transports that name themselves here.
     */
    val inputName: String? get() = null

    /**
     * Write raw input to [sessionId] if this transport owns it.
     *
     * @throws IllegalStateException when it does not. The message must start
     *   with "No " for the not-mine case — the registry uses that prefix to
     *   tell "never heard of this id" apart from the owner's real diagnosis.
     */
    fun sendInput(sessionId: String, text: String): Unit =
        throw IllegalStateException("No $inputName session: $sessionId")
}
