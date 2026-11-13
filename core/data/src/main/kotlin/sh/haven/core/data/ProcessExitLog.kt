package sh.haven.core.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.File

private const val TAG = "ProcessExitLog"

/**
 * How a past Haven process died, in terms a person can act on. Classified
 * from the public `ApplicationExitInfo` fields only — `getSubReason()` is
 * not public API, but the tell-tale detail ("set debug app") appears in the
 * public description.
 */
enum class ProcessExitKind {
    /** Developer options → "Select debug app" points at Haven — Android force-stops it (#494). */
    DEBUG_APP_FORCE_STOP,
    /** Force-stopped — by the user, a settings screen, or ROM policy. */
    FORCE_STOPPED,
    /** The system app freezer / cached-app killer took it. */
    FROZEN,
    /** Killed to reclaim memory. */
    LOW_MEMORY,
    /** Killed for not responding. */
    ANR,
    /** Killed by an unhandled signal that wasn't a native crash. */
    SIGNALED,
    /** Killed for excessive resource use (battery/CPU policy). */
    EXCESSIVE_RESOURCE_USAGE,
    /** Some other system-initiated kill (ROM vendors report through OTHER). */
    OTHER_KILL,
}

/**
 * Classify a death record. Null = benign or handled elsewhere: normal exit,
 * package update, user profile stop, and crashes ([NativeCrashLog] owns
 * native ones; a Java crash leaves its trace in the app log already).
 */
fun classifyProcessExit(reason: Int, description: String?): ProcessExitKind? = when (reason) {
    ApplicationExitInfo.REASON_USER_REQUESTED ->
        if (description?.contains("set debug app") == true) ProcessExitKind.DEBUG_APP_FORCE_STOP
        else ProcessExitKind.FORCE_STOPPED
    ApplicationExitInfo.REASON_FREEZER -> ProcessExitKind.FROZEN
    ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExitKind.LOW_MEMORY
    ApplicationExitInfo.REASON_ANR -> ProcessExitKind.ANR
    ApplicationExitInfo.REASON_SIGNALED -> ProcessExitKind.SIGNALED
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ProcessExitKind.EXCESSIVE_RESOURCE_USAGE
    ApplicationExitInfo.REASON_OTHER -> ProcessExitKind.OTHER_KILL
    else -> null
}

/** One past kill, as the system recorded it. */
data class ProcessExitRecord(
    val timestampMs: Long,
    val kind: ProcessExitKind,
    /** ActivityManager.RunningAppProcessInfo importance at death. */
    val importance: Int,
    val description: String?,
) {
    /**
     * Whether this death took user-visible work with it. The foreground
     * service (the "N active sessions" notification) reports importance
     * 125; anything at or below PERCEPTIBLE (230) was doing something the
     * user could see. A cached-process kill (400) is Android housekeeping.
     */
    val tookActiveWork: Boolean get() = importance <= IMPORTANCE_PERCEPTIBLE

    fun toJson(): String = JSONObject().apply {
        put("timestampMs", timestampMs)
        put("kind", kind.name)
        put("importance", importance)
        put("description", description ?: JSONObject.NULL)
    }.toString()

    companion object {
        const val IMPORTANCE_PERCEPTIBLE = 230

        fun fromJson(line: String): ProcessExitRecord? = try {
            val o = JSONObject(line)
            ProcessExitRecord(
                timestampMs = o.getLong("timestampMs"),
                kind = ProcessExitKind.valueOf(o.getString("kind")),
                importance = o.optInt("importance"),
                description = if (o.isNull("description")) null else o.optString("description"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "dropping unparseable exit record: ${e.message}")
            null
        }
    }
}

/**
 * System-initiated process kills, recovered after the fact — the sibling of
 * [NativeCrashLog] for deaths that are not crashes (#494).
 *
 * Why: every background-disconnect report starts with the same blind spot.
 * The user sees "my sessions died"; whether the *process* died — and who
 * killed it — is knowable only via `adb shell dumpsys activity exit-info`,
 * which most reporters can't run. Android hands every app its own death
 * certificates (`ApplicationExitInfo`); Haven just never read the non-crash
 * ones. A force-stop from the debug-app setting, a ROM freezer kill and a
 * network cut with the process left alive all present identically in the
 * app; they are three different fixes.
 *
 * Same design trade-offs as [NativeCrashLog]: file-backed (no Room schema
 * bump), dedup by the system's own timestamp, API 30+ with silence — not
 * invented records — below that.
 */
class ProcessExitLog(private val context: Context) {

    private val file: File get() = File(context.filesDir, "process_exits.jsonl")
    private val notifiedFile: File get() = File(context.filesDir, "process_exits.notified")

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Fold any kills the system knows about into the log. Safe to call on
     * every launch. @return the records that were new this call.
     */
    fun refresh(): List<ProcessExitRecord> {
        if (!supported) return emptyList()
        val existing = records()
        val known = existing.map { it.timestampMs }.toSet()
        val fresh = try {
            systemKills().filter { it.timestampMs !in known }
        } catch (e: Exception) {
            // Diagnostics must never be the reason the app fails to start.
            Log.w(TAG, "could not read exit reasons: ${e.message}")
            return emptyList()
        }
        if (fresh.isEmpty()) return emptyList()

        val merged = merge(existing, fresh)
        try {
            file.writeText(merged.joinToString("\n") { it.toJson() } + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "could not persist exit records: ${e.message}")
            return emptyList()
        }
        for (r in fresh) {
            Log.w(TAG, "recovered process kill from ${r.timestampMs}: ${r.kind} (${r.description})")
        }
        return fresh
    }

    /** Every recorded kill, oldest first. */
    fun records(): List<ProcessExitRecord> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { ProcessExitRecord.fromJson(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read exit records: ${e.message}")
        emptyList()
    }

    /**
     * The kills the user has not been told about yet — refreshes first, then
     * advances the notified marker past everything returned, so each death is
     * announced once. Only deaths that [ProcessExitRecord.tookActiveWork]
     * qualify; cached-process housekeeping stays in [records] for MCP but is
     * not worth a banner.
     */
    fun consumeUnnotified(): List<ProcessExitRecord> {
        refresh()
        val marker = try {
            notifiedFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
        val fresh = records().filter { it.timestampMs > marker && it.tookActiveWork }
        val newest = records().maxOfOrNull { it.timestampMs } ?: marker
        try {
            notifiedFile.writeText(newest.toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not persist notified marker: ${e.message}")
        }
        return fresh
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun systemKills(): List<ProcessExitRecord> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        return am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
            .mapNotNull { info ->
                val kind = classifyProcessExit(info.reason, info.description) ?: return@mapNotNull null
                ProcessExitRecord(
                    timestampMs = info.timestamp,
                    kind = kind,
                    importance = info.importance,
                    description = info.description,
                )
            }
    }

    internal companion object {
        const val MAX_RECORDS = 16

        /** Same contract as [NativeCrashLog.merge]: dedup by timestamp, oldest first, capped. */
        internal fun merge(
            existing: List<ProcessExitRecord>,
            fresh: List<ProcessExitRecord>,
            max: Int = MAX_RECORDS,
        ): List<ProcessExitRecord> {
            val byTimestamp = LinkedHashMap<Long, ProcessExitRecord>()
            for (r in existing + fresh) byTimestamp[r.timestampMs] = r
            return byTimestamp.values.sortedBy { it.timestampMs }.takeLast(max)
        }
    }
}
