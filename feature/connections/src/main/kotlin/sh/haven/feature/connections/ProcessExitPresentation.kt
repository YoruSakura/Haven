package sh.haven.feature.connections

import androidx.annotation.StringRes
import sh.haven.core.data.ProcessExitKind

/**
 * User-facing cause for a recovered process kill (#494). Kinds that differ
 * only technically share a string — the user's next step is what matters:
 * the debug-app setting is specifically actionable, a force stop points at
 * settings/ROM policy, and the rest group into memory / not-responding /
 * system policy.
 */
@StringRes
internal fun processExitCauseRes(kind: ProcessExitKind): Int = when (kind) {
    ProcessExitKind.DEBUG_APP_FORCE_STOP -> R.string.process_exit_cause_debug_app
    ProcessExitKind.FORCE_STOPPED -> R.string.process_exit_cause_force_stop
    ProcessExitKind.LOW_MEMORY -> R.string.process_exit_cause_memory
    ProcessExitKind.ANR -> R.string.process_exit_cause_anr
    ProcessExitKind.FROZEN,
    ProcessExitKind.SIGNALED,
    ProcessExitKind.EXCESSIVE_RESOURCE_USAGE,
    ProcessExitKind.OTHER_KILL,
    -> R.string.process_exit_cause_system
}
