package sh.haven.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic kick for every [ForegroundReviveHook] while the foreground service
 * is up.
 *
 * Why this exists: the hooks' other two triggers — return-to-foreground and
 * network-available — are both unreachable for an MCP client running on the
 * *same phone*. Switching to the agent app **is** the backgrounding event, so
 * Haven never comes back to the foreground while the agent is working, and a
 * client on loopback never causes a network transition either. A loopback
 * accept loop killed by a process trim then stays dead for as long as the user
 * keeps using the agent, which reads as "backgrounding Haven disconnects
 * everything" (#494).
 *
 * The tick is cheap by construction: every hook is required to be a no-op while
 * healthy (`McpServer.reviveNow` does a single `isHealthy()` check;
 * `McpTunnelManager.kickNow` an in-memory status read), so a healthy Haven pays
 * one comparison per minute.
 *
 * Not a replacement for the two instant triggers — they still fire, and still
 * beat waiting up to [INTERVAL_MS] for this.
 */
@Singleton
class ForegroundReviveTicker @Inject constructor(
    private val hooks: Set<@JvmSuppressWildcards ForegroundReviveHook>,
) {
    /** Test seam: the scope the tick loop runs on (swap for a TestScope). */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Idempotent — a second start while already ticking is ignored. */
    fun start(intervalMs: Long = INTERVAL_MS) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                // One sick hook must not stop the others from being kicked, or
                // silence the tick entirely by killing the loop.
                hooks.forEach { runCatching { it.reviveNow() } }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * A minute: long enough to be invisible on battery given the hooks are
         * no-ops while healthy, short enough that an agent mid-conversation
         * notices the endpoint coming back rather than giving up.
         */
        const val INTERVAL_MS = 60_000L
    }
}
