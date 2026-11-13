package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #421 reconnect policy.
 *
 * Reconnecting a mosh session that the transport declared dead is what turns a
 * permanently-stuck "retrying" banner into a working session. Doing it without a
 * bound is its own bug: when the server stays unreachable while the phone is
 * online, the replacement session is dead too, escalates ~45s later and
 * reconnects again — a loop by succession, each cycle costing an SSH connect and
 * a fresh mosh-server on the host. That loop was observed on-device with
 * scripts/mosh-fault-rig.py, which is why these bounds exist.
 */
class MoshReconnectPolicyTest {

    private val t0 = 1_000_000L

    @Test
    fun `first death reconnects immediately`() {
        val d = decideMoshReconnect(attempts = 0, lastAttemptMs = 0L, nowMs = t0)
        assertTrue(d.reconnect)
        assertEquals("the first recovery should not be delayed", 0L, d.backoffMs)
        assertEquals(1, d.attempt)
    }

    @Test
    fun `repeated deaths back off instead of hammering`() {
        val second = decideMoshReconnect(attempts = 1, lastAttemptMs = t0, nowMs = t0 + 1_000)
        val third = decideMoshReconnect(attempts = 2, lastAttemptMs = t0, nowMs = t0 + 1_000)
        assertTrue(second.reconnect && third.reconnect)
        assertTrue("backoff must grow", third.backoffMs > second.backoffMs)
        assertTrue("backoff must be non-trivial", second.backoffMs >= 10_000L)
    }

    /** The point of the policy: it stops. A rule that never stops is the bug. */
    @Test
    fun `gives up once the attempt budget is spent`() {
        val d = decideMoshReconnect(
            attempts = MOSH_RECONNECT_MAX_ATTEMPTS,
            lastAttemptMs = t0,
            nowMs = t0 + 1_000,
        )
        assertFalse(
            "an unreachable server must not be retried forever — each cycle is an " +
                "SSH connect plus a new mosh-server on the host",
            d.reconnect,
        )
    }

    /**
     * A session that came back and ran for a long time before failing is a new
     * incident, not the continuation of an old streak — otherwise a profile that
     * hiccups once a day would eventually refuse to reconnect at all.
     */
    @Test
    fun `an old streak is forgotten so a recovered profile still reconnects`() {
        val d = decideMoshReconnect(
            attempts = MOSH_RECONNECT_MAX_ATTEMPTS,
            lastAttemptMs = t0,
            nowMs = t0 + MOSH_RECONNECT_RESET_MS + 1,
        )
        assertTrue("a stale streak must not permanently disable reconnect", d.reconnect)
        assertEquals("and it should start over, not resume mid-backoff", 0L, d.backoffMs)
        assertEquals(1, d.attempt)
    }

    /**
     * #421, second half: a reconnect whose *connect* fails now retries, where it
     * used to log the exception and stop. A reporter lost their session to a
     * single transient `Could not resolve hostname` 15s after the network came
     * back, with nothing scheduled afterwards.
     *
     * Retrying is only safe because a failed attempt still counts. The ViewModel
     * records the attempt *before* trying to connect, so the streak grows whether
     * the connect succeeds or throws, and this loop terminates. Move that
     * assignment after `connectMoshSilent` and a server that is simply gone
     * becomes an infinite reconnect loop — which is what this asserts.
     */
    @Test
    fun `a reconnect chain of pure failures still terminates`() {
        var attempts = 0
        var lastAttemptMs = 0L
        var now = t0
        var connects = 0

        // Mirrors scheduleMoshReconnect: decide, record the attempt, try to
        // connect, and on failure go round again.
        while (true) {
            val d = decideMoshReconnect(attempts, lastAttemptMs, now)
            if (!d.reconnect) break
            now += d.backoffMs
            attempts = d.attempt
            lastAttemptMs = now
            connects++
            // The connect throws every time — a host that will not resolve.
            assertTrue(
                "a failing reconnect chain must not exceed the attempt budget",
                connects <= MOSH_RECONNECT_MAX_ATTEMPTS,
            )
        }

        assertEquals(
            "the chain should spend exactly the budget, then stop",
            MOSH_RECONNECT_MAX_ATTEMPTS,
            connects,
        )
    }

    /** Guard the ordering the policy depends on: give-up must outlast a real stall. */
    @Test
    fun `reset window is longer than the whole attempt sequence`() {
        val worstCase = MOSH_RECONNECT_BACKOFF_MS * MOSH_RECONNECT_MAX_ATTEMPTS
        assertTrue(
            "the streak must not reset while attempts are still being made",
            MOSH_RECONNECT_RESET_MS > worstCase,
        )
    }
}
