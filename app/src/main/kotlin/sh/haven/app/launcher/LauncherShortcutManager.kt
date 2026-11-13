package sh.haven.app.launcher

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import sh.haven.app.MainActivity
import sh.haven.app.R
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LauncherShortcut {
    val label: String
    val shortcutId: String
}

data class ConnectionShortcut(val profileId: String, override val label: String) : LauncherShortcut {
    override val shortcutId = "connection-$profileId"
}

/** Publishes the most recently used remote connections to the Android launcher. */
@Singleton
class LauncherShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionRepository: ConnectionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        connectionRepository.observeAll().distinctUntilChanged()
            .onEach(::rebuildShortcuts).launchIn(scope)
    }

    private fun rebuildShortcuts(connections: List<ConnectionProfile>) {
        try {
            val manifestCount = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).size
            val budget = maxDynamicShortcuts(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context), manifestCount)
            val shortcuts = selectShortcuts(connections, budget).map { spec ->
                ShortcutInfoCompat.Builder(context, spec.shortcutId)
                    .setShortLabel(spec.label.ifBlank { spec.profileId })
                    .setLongLabel(spec.label.ifBlank { spec.profileId })
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(Intent(context, MainActivity::class.java).apply {
                        action = ACTION_LAUNCH_CONNECTION
                        putExtra(EXTRA_CONNECTION_ID, spec.profileId)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }).build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            val retired = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.id.startsWith("workspace-") || it.id == "pull_backup_now" }.map { it.id }
            if (retired.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, retired, "This shortcut is no longer supported")
        } catch (e: Exception) {
            Log.w("LauncherShortcuts", "Could not publish shortcuts", e)
        }
    }

    companion object {
        const val ACTION_LAUNCH_CONNECTION = "sh.haven.action.LAUNCH_CONNECTION"
        const val EXTRA_CONNECTION_ID = "sh.haven.extra.CONNECTION_ID"
        internal const val MAX_SHORTCUTS = 5
        internal const val MANIFEST_SHORTCUT_COUNT = 0

        internal fun maxDynamicShortcuts(maxShortcutCountPerActivity: Int, manifestShortcutCount: Int = MANIFEST_SHORTCUT_COUNT): Int =
            (maxShortcutCountPerActivity - manifestShortcutCount.coerceAtLeast(0)).coerceIn(0, MAX_SHORTCUTS)

        internal fun selectShortcuts(connections: List<ConnectionProfile>, maxShortcuts: Int = MAX_SHORTCUTS): List<ConnectionShortcut> =
            connections.filter { it.isClientSupported && (it.isSsh || it.isVnc || it.isRdp || it.isSmb) }
                .sortedByDescending { it.lastConnected ?: 0L }
                .take(maxShortcuts.coerceIn(0, MAX_SHORTCUTS))
                .map { ConnectionShortcut(it.id, it.label) }
    }
}
