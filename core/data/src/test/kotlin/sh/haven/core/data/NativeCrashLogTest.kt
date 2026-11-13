package sh.haven.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #509 and #517 are both native crashes, and both stalled on the same missing
 * evidence: the reporter's log ends at `Fatal signal …` with no backtrace,
 * because Haven captures logcat from inside the process that just died.
 * [NativeCrashLog] recovers the tombstone on the next launch instead.
 *
 * What is worth testing here is the bookkeeping, not the framework call. The
 * system re-reports the same crash on every launch for as long as it keeps it,
 * so the dedup is load-bearing: without it one crash accumulates a copy per app
 * start, and the cap then evicts real history to store duplicates of one event.
 */
class NativeCrashLogTest {

    private fun record(ts: Long, desc: String = "SIGSEGV", trace: String? = "backtrace", signal: Int = 11) =
        NativeCrashRecord(timestampMs = ts, description = desc, signal = signal, trace = trace)

    @Test
    fun `a record survives a JSON round trip`() {
        val original = record(1_700_000_000_000, "SIGABRT", "#00 pc 0000 libjni_cb_term.so")
        val parsed = NativeCrashRecord.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    /** A missing tombstone is normal, not an error, and must not become "null". */
    @Test
    fun `a record with no trace round trips as null rather than a string`() {
        val parsed = NativeCrashRecord.fromJson(record(1, trace = null).toJson())
        assertNotNull(parsed)
        assertNull("absent trace must stay absent", parsed!!.trace)
    }

    @Test
    fun `a corrupt line is dropped rather than throwing`() {
        assertNull(NativeCrashRecord.fromJson("not json at all"))
        assertNull(NativeCrashRecord.fromJson(""))
        assertNull(NativeCrashRecord.fromJson("""{"description":"no timestamp"}"""))
    }

    /**
     * The one that matters. The same crash is re-reported on every launch; seeing
     * it again must not add a second copy.
     */
    @Test
    fun `re-seeing the same crash does not duplicate it`() {
        val stored = listOf(record(100), record(200))
        val reReported = listOf(record(100), record(200))

        val merged = NativeCrashLog.merge(stored, reReported)

        assertEquals("the same two crashes, not four", 2, merged.size)
        assertEquals(listOf(100L, 200L), merged.map { it.timestampMs })
    }

    @Test
    fun `a genuinely new crash is appended`() {
        val merged = NativeCrashLog.merge(listOf(record(100)), listOf(record(100), record(300)))
        assertEquals(listOf(100L, 300L), merged.map { it.timestampMs })
    }

    @Test
    fun `records are kept oldest first`() {
        val merged = NativeCrashLog.merge(emptyList(), listOf(record(300), record(100), record(200)))
        assertEquals(listOf(100L, 200L, 300L), merged.map { it.timestampMs })
    }

    /** The cap must evict the oldest, not the newest — the recent crash is the one being chased. */
    @Test
    fun `the cap drops the oldest crashes`() {
        val many = (1L..15L).map { record(it * 100) }
        val merged = NativeCrashLog.merge(emptyList(), many, max = 10)

        assertEquals(10, merged.size)
        assertEquals("newest must be kept", 1500L, merged.last().timestampMs)
        assertEquals("oldest must be evicted", 600L, merged.first().timestampMs)
    }

    /** A re-report carrying a tombstone should replace an earlier record that lacked one. */
    @Test
    fun `a later report of the same crash wins`() {
        val merged = NativeCrashLog.merge(
            listOf(record(100, trace = null)),
            listOf(record(100, trace = "now with a backtrace")),
        )
        assertEquals(1, merged.size)
        assertEquals("now with a backtrace", merged.single().trace)
    }

    @Test
    fun `merging nothing new leaves the list untouched`() {
        val stored = listOf(record(100), record(200))
        assertTrue(NativeCrashLog.merge(stored, emptyList()) == stored)
    }

    /**
     * #526: a reporter sent a screenshot of ten crashes that all read "— crash".
     * When Android keeps no tombstone the signal is the only thing telling them
     * apart, and SIGABRT (the runtime deliberately aborting, e.g. a failed JNI
     * check) points somewhere completely different from SIGSEGV (a bad access).
     */
    @Test
    fun `the signal is named, not just numbered`() {
        assertEquals("SIGABRT (6)", record(1, signal = 6).signalName)
        assertEquals("SIGSEGV (11)", record(1, signal = 11).signalName)
        assertEquals("SIGBUS (7)", record(1, signal = 7).signalName)
    }

    /** An unmapped signal must still say something, not render as an empty name. */
    @Test
    fun `an unknown signal falls back to its number`() {
        assertEquals("signal 99", record(1, signal = 99).signalName)
    }

    /**
     * A tombstone is protobuf, not text (#517).
     *
     * The real report that exposed this had been read through a `bufferedReader`,
     * which replaced every non-UTF-8 byte with U+FFFD — 108,475 substitutions, a
     * third of the file, and the abort message among them. This models that shape:
     * symbol text embedded in binary framing.
     */
    @Test
    fun `readable text is recovered from a binary tombstone`() {
        val tombstone =
            byteArrayOf(0x0A, 0xEF.toByte(), 0xBF.toByte(), 0x00, 0x12) +
                "JNI ERROR (app bug): local reference table overflow".toByteArray() +
                byteArrayOf(0x00, 0xFF.toByte(), 0x08) +
                "libjni_cb_term.so".toByteArray() +
                byteArrayOf(0xC0.toByte(), 0x80.toByte())

        val summary = NativeCrashLog.printableRuns(tombstone)

        assertTrue("abort message lost: $summary", summary.contains("JNI ERROR (app bug)"))
        assertTrue("library name lost: $summary", summary.contains("libjni_cb_term.so"))
        assertTrue("binary must not be decoded as text: $summary", !summary.contains('�'))
    }

    /** Framing bytes that happen to be printable must not glue unrelated runs together. */
    @Test
    fun `short punctuation runs are dropped rather than joined`() {
        val summary = NativeCrashLog.printableRuns(
            "abort_message_here".toByteArray() + byteArrayOf(0x00, 0x2A, 0x00) + "libc.so".toByteArray(),
        )
        assertEquals(listOf("abort_message_here", "libc.so"), summary.lines())
    }

    /** A huge tombstone must be truncated, never dropped — a partial backtrace still names the library. */
    @Test
    fun `an oversized tombstone is truncated not discarded`() {
        val huge = ("A".repeat(64) + " ").repeat(8000).toByteArray(Charsets.ISO_8859_1)
        val summary = NativeCrashLog.printableRuns(huge, limit = 4096)
        assertTrue("expected truncation, got ${summary.length}", summary.length <= 4096)
        assertTrue("truncated to nothing", summary.contains("AAAAAA"))
    }
}
