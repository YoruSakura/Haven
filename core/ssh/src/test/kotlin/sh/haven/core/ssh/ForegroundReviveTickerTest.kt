package sh.haven.core.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The point of the ticker is that it fires **without** a return-to-foreground
 * or a network transition, so every test here advances virtual time only —
 * nothing calls a hook directly. A ticker that never fires fails all of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundReviveTickerTest {

    private val hookA = mockk<ForegroundReviveHook>(relaxed = true)
    private val hookB = mockk<ForegroundReviveHook>(relaxed = true)

    @Test
    fun `hooks are kicked once per interval with no foreground or network event`() = runTest {
        val ticker = ForegroundReviveTicker(setOf(hookA, hookB)).apply { scope = this@runTest }
        ticker.start(1_000L)

        advanceTimeBy(3_500L)
        ticker.stop()

        verify(exactly = 3) { hookA.reviveNow() }
        verify(exactly = 3) { hookB.reviveNow() }
    }

    @Test
    fun `nothing is kicked before the first interval elapses`() = runTest {
        val ticker = ForegroundReviveTicker(setOf(hookA)).apply { scope = this@runTest }
        ticker.start(1_000L)

        advanceTimeBy(900L)
        ticker.stop()

        verify(exactly = 0) { hookA.reviveNow() }
    }

    @Test
    fun `stop ends the ticking`() = runTest {
        val ticker = ForegroundReviveTicker(setOf(hookA)).apply { scope = this@runTest }
        ticker.start(1_000L)

        advanceTimeBy(1_500L)
        ticker.stop()
        advanceTimeBy(10_000L)

        verify(exactly = 1) { hookA.reviveNow() }
    }

    @Test
    fun `a second start does not double the tick rate`() = runTest {
        val ticker = ForegroundReviveTicker(setOf(hookA)).apply { scope = this@runTest }
        ticker.start(1_000L)
        ticker.start(1_000L)

        advanceTimeBy(2_500L)
        ticker.stop()

        verify(exactly = 2) { hookA.reviveNow() }
    }

    @Test
    fun `a throwing hook neither blocks the others nor kills the loop`() = runTest {
        every { hookA.reviveNow() } throws IllegalStateException("boom")
        val ticker = ForegroundReviveTicker(setOf(hookA, hookB)).apply { scope = this@runTest }
        ticker.start(1_000L)

        advanceTimeBy(2_500L)
        ticker.stop()

        verify(exactly = 2) { hookB.reviveNow() }
    }
}
