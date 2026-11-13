package sh.haven.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import sh.haven.core.data.preferences.UserPreferencesRepository
import javax.inject.Inject

@HiltAndroidApp
class HavenApp : Application(), Configuration.Provider {
    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sshTerminalEmulatorOwner: sh.haven.feature.terminal.SshTerminalEmulatorOwner
    @Inject lateinit var updateNotifier: sh.haven.app.update.UpdateNotifier
    @Inject lateinit var launcherShortcutManager: sh.haven.app.launcher.LauncherShortcutManager
    @Inject lateinit var portForwardRuleDao: sh.haven.core.data.db.PortForwardRuleDao
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
    override fun onCreate() {
        super.onCreate()
        sshTerminalEmulatorOwner.start()
        val theme = runBlocking { preferencesRepository.theme.first() }
        AppCompatDelegate.setDefaultNightMode(theme.toNightMode())
        sh.haven.core.platform.PlatformShell.initShizukuListeners()
        val migration = getSharedPreferences("client-scope", MODE_PRIVATE)
        if (!migration.getBoolean("pruned-v1", false)) {
            // Cancel scheduled work belonging to the removed local/agent/backup features.
            // Keep the database and saved credentials for import/export compatibility.
            WorkManager.getInstance(this).cancelAllWork()
            runBlocking(Dispatchers.IO) { portForwardRuleDao.disableLegacyAgentForwards() }
            migration.edit().putBoolean("pruned-v1", true).apply()
        }
        launcherShortcutManager.start()
        appScope.launch {
            runCatching { sh.haven.core.data.NativeCrashLog(this@HavenApp).refresh() }
            runCatching { sh.haven.core.data.ProcessExitLog(this@HavenApp).refresh() }
        }
        updateNotifier.start(appScope)
    }
}

internal fun UserPreferencesRepository.ThemeMode.toNightMode(): Int = when (this) {
    UserPreferencesRepository.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    UserPreferencesRepository.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    UserPreferencesRepository.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}
