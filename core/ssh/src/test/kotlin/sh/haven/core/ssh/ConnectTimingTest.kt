package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #519 — an SSH connect that went from under 100 ms to over a second, with no
 * way to say which phase grew.
 *
 * The clock is injected so these assert exact numbers rather than sleeping and
 * hoping. A timing helper that can only be tested by waiting is a timing helper
 * nobody re-tests.
 */
class ConnectTimingTest {

    /** A fake clock advanced explicitly, in milliseconds. */
    private class Clock {
        var nanos = 1_000_000_000L
        fun advanceMs(ms: Long) { nanos += ms * 1_000_000 }
    }

    @Test
    fun `each phase reports its own duration`() {
        val clock = Clock()
        val t = ConnectTiming { clock.nanos }

        clock.advanceMs(980); t.mark("resolve")
        clock.advanceMs(8); t.mark("setup")
        clock.advanceMs(0); t.mark("knock")
        clock.advanceMs(90); t.mark("handshake")

        val summary = t.summary()
        assertTrue(summary, summary.contains("resolve=980ms"))
        assertTrue(summary, summary.contains("setup=8ms"))
        assertTrue(summary, summary.contains("knock=0ms"))
        assertTrue(summary, summary.contains("handshake=90ms"))
        assertTrue(summary, summary.contains("total=1078ms"))
    }

    /** The reported symptom: the total is dominated by one phase, and it says which. */
    @Test
    fun `a slow resolve is attributable`() {
        val clock = Clock()
        val t = ConnectTiming { clock.nanos }
        clock.advanceMs(1500); t.mark("resolve")
        clock.advanceMs(40); t.mark("handshake")

        assertTrue(t.summary(), t.summary().startsWith("resolve=1500ms"))
    }

    /**
     * Time between marks must not vanish. In #466 the cost that mattered was
     * precisely the part nobody was measuring, and it only surfaced because the
     * total was compared against the sum of the parts.
     */
    @Test
    fun `time outside any phase is reported as unaccounted`() {
        val clock = Clock()
        val t = ConnectTiming { clock.nanos }
        clock.advanceMs(10); t.mark("resolve")
        clock.advanceMs(500) // nobody marks this
        val summary = t.summary()

        assertTrue("500ms went missing: $summary", summary.contains("unaccounted=500ms"))
        assertTrue(summary, summary.contains("total=510ms"))
    }

    /** Rounding noise across several marks must not be reported as a finding. */
    @Test
    fun `sub-millisecond drift is not reported as unaccounted`() {
        val clock = Clock()
        val t = ConnectTiming { clock.nanos }
        repeat(4) { clock.advanceMs(3); t.mark("p$it") }

        assertFalse("noise reported as a gap: ${t.summary()}", t.summary().contains("unaccounted"))
    }

    /** A phase entered twice reports its total, not just the last visit. */
    @Test
    fun `repeating a phase accumulates`() {
        val clock = Clock()
        val t = ConnectTiming { clock.nanos }
        clock.advanceMs(100); t.mark("resolve")
        clock.advanceMs(200); t.mark("resolve")

        assertTrue(t.summary(), t.summary().contains("resolve=300ms"))
    }

    /** Phases read in the order they happened, so the line matches the sequence. */
    @Test
    fun `phases keep their order`() {
        val clock = Clock()
        val t = ConnectTiming { clock.nanos }
        clock.advanceMs(1); t.mark("resolve")
        clock.advanceMs(1); t.mark("setup")
        clock.advanceMs(1); t.mark("handshake")

        val order = Regex("(\\w+)=").findAll(t.summary()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("resolve", "setup", "handshake", "total"), order)
    }

    /**
     * #518: this line goes into logs users attach to issues, so it must carry
     * durations and nothing else — no hostname, no username.
     */
    @Test
    fun `the summary contains only phase names and numbers`() {
        val clock = Clock()
        val t = ConnectTiming { clock.nanos }
        clock.advanceMs(5); t.mark("resolve")

        assertTrue(
            "unexpected content in the timing line: ${t.summary()}",
            t.summary().matches(Regex("[a-z]+=\\d+ms( [a-z]+=\\d+ms)*")),
        )
    }
}
