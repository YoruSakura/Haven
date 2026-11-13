package sh.haven.core.data

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #494: every background-disconnect report begins with the same unknown —
 * did the *process* die, and who killed it? The system's answer was only
 * reachable over adb. [ProcessExitLog] recovers it in-app; what's worth
 * testing is the classification (the part that turns a reason code into a
 * next step) and the same load-bearing bookkeeping as [NativeCrashLogTest].
 */
class ProcessExitLogTest {

    private fun record(
        ts: Long,
        kind: ProcessExitKind = ProcessExitKind.FORCE_STOPPED,
        importance: Int = 125,
        description: String? = "stop sh.haven.app",
    ) = ProcessExitRecord(timestampMs = ts, kind = kind, importance = importance, description = description)

    // The case that cracked the daily-driver hunt: force-stop with the
    // debug-app marker in the public description.
    @Test
    fun `force stop via the debug-app setting is its own kind`() {
        assertEquals(
            ProcessExitKind.DEBUG_APP_FORCE_STOP,
            classifyProcessExit(
                ApplicationExitInfo.REASON_USER_REQUESTED,
                "stop sh.haven.app due to set debug app",
            ),
        )
    }

    @Test
    fun `a plain force stop stays a force stop`() {
        assertEquals(
            ProcessExitKind.FORCE_STOPPED,
            classifyProcessExit(
                ApplicationExitInfo.REASON_USER_REQUESTED,
                "stop sh.haven.app due to from pid 29753 (com.android.settings)",
            ),
        )
    }

    @Test
    fun `system kill reasons classify to their kinds`() {
        assertEquals(ProcessExitKind.FROZEN, classifyProcessExit(ApplicationExitInfo.REASON_FREEZER, null))
        assertEquals(ProcessExitKind.LOW_MEMORY, classifyProcessExit(ApplicationExitInfo.REASON_LOW_MEMORY, null))
        assertEquals(ProcessExitKind.ANR, classifyProcessExit(ApplicationExitInfo.REASON_ANR, null))
        assertEquals(ProcessExitKind.OTHER_KILL, classifyProcessExit(ApplicationExitInfo.REASON_OTHER, "OEM policy"))
    }

    /** Benign and elsewhere-owned exits must not produce kill records. */
    @Test
    fun `normal exits updates and crashes are not kills`() {
        assertNull(classifyProcessExit(ApplicationExitInfo.REASON_EXIT_SELF, null))
        assertNull(classifyProcessExit(ApplicationExitInfo.REASON_PACKAGE_UPDATED, null))
        assertNull(classifyProcessExit(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, null))
        assertNull(classifyProcessExit(ApplicationExitInfo.REASON_USER_STOPPED, null))
        // Crashes: native ones belong to NativeCrashLog, Java ones to the app log.
        assertNull(classifyProcessExit(ApplicationExitInfo.REASON_CRASH, null))
        assertNull(classifyProcessExit(ApplicationExitInfo.REASON_CRASH_NATIVE, null))
    }

    // The dumpsys capture behind #494's second device: importance 125 =
    // the foreground service (sessions) died with the process; 400 = cached
    // housekeeping nobody needs a banner for.
    @Test
    fun `importance decides whether the death took active work`() {
        assertTrue(record(1, importance = 100).tookActiveWork)
        assertTrue(record(1, importance = 125).tookActiveWork)
        assertFalse(record(1, importance = 400).tookActiveWork)
    }

    @Test
    fun `a record survives a JSON round trip`() {
        val original = record(1_700_000_000_000, ProcessExitKind.DEBUG_APP_FORCE_STOP, 125, "due to set debug app")
        assertEquals(original, ProcessExitRecord.fromJson(original.toJson()))
    }

    @Test
    fun `a record with no description round trips as null`() {
        val parsed = ProcessExitRecord.fromJson(record(1, description = null).toJson())
        assertNull(parsed!!.description)
    }

    @Test
    fun `a corrupt line is dropped rather than throwing`() {
        assertNull(ProcessExitRecord.fromJson("not json"))
        assertNull(ProcessExitRecord.fromJson("""{"kind":"FORCE_STOPPED"}"""))
        assertNull(ProcessExitRecord.fromJson("""{"timestampMs":1,"kind":"NOT_A_KIND"}"""))
    }

    /** Same load-bearing dedup as NativeCrashLog: the system re-reports every launch. */
    @Test
    fun `merge dedups by timestamp and caps oldest-first`() {
        val existing = (1L..16L).map { record(it) }
        val fresh = listOf(record(16), record(17))
        val merged = ProcessExitLog.merge(existing, fresh)
        assertEquals(ProcessExitLog.MAX_RECORDS, merged.size)
        assertEquals(2L, merged.first().timestampMs) // oldest evicted, no dup of 16
        assertEquals(17L, merged.last().timestampMs)
        assertEquals(merged.map { it.timestampMs }.distinct().size, merged.size)
    }
}
