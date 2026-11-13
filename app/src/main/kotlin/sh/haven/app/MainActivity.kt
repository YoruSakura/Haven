package sh.haven.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import sh.haven.app.navigation.HavenNavHost
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.security.BiometricAuthenticator
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ui.KeyEventInterceptor
import sh.haven.core.ui.theme.HavenTheme
import sh.haven.core.redact.LogRedact
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var biometricAuthenticator: BiometricAuthenticator
    @Inject lateinit var connectionRepository: ConnectionRepository
    @Inject lateinit var sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository
    @Inject lateinit var fidoAuthenticator: FidoAuthenticator
    @Inject lateinit var biometricGate: sh.haven.core.data.keystore.BiometricGate
    @Inject lateinit var agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus
    @Inject lateinit var userMessageBus: sh.haven.core.data.message.UserMessageBus
    private var lastDeepLink: Pair<String, Long>? = null

    override fun onResume() {
        super.onResume()
        exitIfDisconnected()
        fidoAuthenticator.setActiveActivity(this)
        biometricGate.setForegroundActive(true)
    }
    override fun onPause() {
        fidoAuthenticator.clearActiveActivity(this)
        biometricGate.setForegroundActive(false)
        super.onPause()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        exitIfDisconnected()
        handleConnectionShortcut(intent)
        handleConnectDeepLink(intent)
        handleStandardSchemeDeepLink(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstanceRef = java.lang.ref.WeakReference(this)
        enableEdgeToEdge()
        handleConnectionShortcut(intent)
        handleConnectDeepLink(intent)
        handleStandardSchemeDeepLink(intent)
        setContent {
            val screenSecurity by preferencesRepository.screenSecurity.collectAsState(initial = false)
            LaunchedEffect(screenSecurity) {
                if (screenSecurity) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val excludeFromRecents by preferencesRepository.excludeFromRecents.collectAsState(initial = false)
            LaunchedEffect(excludeFromRecents) {
                getSystemService(android.app.ActivityManager::class.java)?.appTasks?.forEach {
                    it.setExcludeFromRecents(excludeFromRecents)
                }
            }
            val theme by preferencesRepository.theme.collectAsState(initial = UserPreferencesRepository.ThemeMode.SYSTEM)
            LaunchedEffect(theme) { AppCompatDelegate.setDefaultNightMode(theme.toNightMode()) }
            val dark = when (theme) {
                UserPreferencesRepository.ThemeMode.DARK -> true
                UserPreferencesRepository.ThemeMode.LIGHT -> false
                UserPreferencesRepository.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            HavenTheme(darkTheme = dark) {
                val biometricEnabled by preferencesRepository.biometricEnabled.collectAsState(initial = false)
                val lockTimeout by preferencesRepository.lockTimeout.collectAsState(initial = UserPreferencesRepository.LockTimeout.IMMEDIATE)
                var unlocked by remember { mutableStateOf(false) }
                var backgroundedAt by remember { mutableStateOf(0L) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner, lockTimeout) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) backgroundedAt = System.currentTimeMillis()
                        if (event == Lifecycle.Event.ON_START && unlocked && backgroundedAt > 0 &&
                            lockTimeout != UserPreferencesRepository.LockTimeout.NEVER &&
                            System.currentTimeMillis() - backgroundedAt >= maxOf(lockTimeout.seconds, 5L) * 1000) unlocked = false
                    }
                    owner.lifecycle.addObserver(observer)
                    onDispose { owner.lifecycle.removeObserver(observer) }
                }
                sh.haven.core.ui.ProvideExternalDisplayProjection {
                    if (biometricEnabled && !unlocked) {
                        BiometricLockScreen(biometricAuthenticator) { unlocked = true }
                    } else {
                        HavenNavHost(preferencesRepository, connectionRepository, sshKeyRepository, agentUiCommandBus, userMessageBus)
                        BiometricGateHost()
                        TunnelAuthHost()
                    }
                }
            }
        }
    }
    override fun onDestroy() {
        if (activeInstanceRef?.get() === this) activeInstanceRef = null
        super.onDestroy()
    }
private fun exitIfDisconnected() {
        // Only the service's own auto-launch (it carries
        // EXTRA_EXIT_AFTER_DISCONNECT_ALL) may finish the activity. When that
        // launch is blocked by background-activity-launch restrictions the
        // flag would otherwise survive to the next manual open from the
        // launcher, which then bounced straight back out (#640) — clear the
        // flag on a plain open instead of finishing.
        val exitExtra = intent?.getBooleanExtra(
            SshConnectionService.EXTRA_EXIT_AFTER_DISCONNECT_ALL, false) ?: false
        if (SshConnectionService.shouldFinishOnResume(
                SshConnectionService.disconnectedAll, exitExtra)) {
            Log.d("MainActivity", "Disconnect All detected — exiting")
            SshConnectionService.clearDisconnectedAll()
            finishAndRemoveTask()
        } else if (SshConnectionService.disconnectedAll) {
            SshConnectionService.clearDisconnectedAll()
        }
    }

private fun handleConnectDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "haven" || data.host != "connect") return
        val params = ConnectDeepLink.parse { data.getQueryParameter(it) } ?: return
        if (!isFreshDeepLink(data)) return
        intent.data = null
        Log.d("MainActivity", "connect deep link host=${LogRedact.of(params.host)} transport=${params.transport}")
        MainScope().launch {
            agentUiCommandBus.emit(ConnectDeepLink.resolve(connectionRepository.getAll(), params))
        }
    }

private fun handleStandardSchemeDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val scheme = data.scheme?.lowercase() ?: return
        if (scheme != "ssh" && scheme != "rdp" && scheme != "vnc") return
        val params = ConnectDeepLink.parseStandardScheme(scheme, data.userInfo, data.host, data.port.takeIf { it > 0 })
            ?: return
        if (!isFreshDeepLink(data)) return
        intent.data = null
        Log.d("MainActivity", "standard-scheme deep link host=${LogRedact.of(params.host)} transport=${params.transport}")
        MainScope().launch {
            agentUiCommandBus.emit(ConnectDeepLink.resolve(connectionRepository.getAll(), params))
        }
    }

private fun isFreshDeepLink(data: android.net.Uri): Boolean {
        val key = data.toString()
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastDeepLink
        if (previous != null && previous.first == key &&
            now - previous.second < DEEP_LINK_DEDUPE_MS
        ) {
            Log.d("MainActivity", "ignoring duplicate deep link within dedupe window")
            return false
        }
        lastDeepLink = key to now
        return true
    }

private fun handleConnectionShortcut(intent: Intent?) {
        if (intent?.action != sh.haven.app.launcher.LauncherShortcutManager.ACTION_LAUNCH_CONNECTION) return
        val profileId = intent.getStringExtra(
            sh.haven.app.launcher.LauncherShortcutManager.EXTRA_CONNECTION_ID,
        ) ?: return
        intent.removeExtra(sh.haven.app.launcher.LauncherShortcutManager.EXTRA_CONNECTION_ID)
        Log.d("MainActivity", "connecting profile $profileId from launcher shortcut")
        MainScope().launch {
            agentUiCommandBus.emit(sh.haven.core.data.agent.AgentUiCommand.ConnectProfile(profileId))
        }
    }

override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyEventInterceptor.handler?.let { interceptor ->
            if (interceptor(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

fun handleDeepLinkUri(uri: android.net.Uri) {
        val intent = Intent().apply { data = uri }
        handleConnectDeepLink(intent)
        handleStandardSchemeDeepLink(intent)
    }
    companion object {
        private const val DEEP_LINK_DEDUPE_MS = 3_000L
        @Volatile private var activeInstanceRef: java.lang.ref.WeakReference<MainActivity>? = null
        fun getActiveInstance(): MainActivity? = activeInstanceRef?.get()
    }
}
