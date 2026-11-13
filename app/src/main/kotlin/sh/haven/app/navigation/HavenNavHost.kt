package sh.haven.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collect
import sh.haven.app.desktop.DesktopViewModel
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.message.UserMessageBus
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.ui.navigation.Screen
import sh.haven.feature.connections.ConnectionAuthDialogs
import sh.haven.feature.connections.ConnectionsScreen
import sh.haven.feature.connections.ConnectionsViewModel
import sh.haven.feature.keys.KeysScreen
import sh.haven.feature.settings.SettingsScreen
import sh.haven.feature.settings.SettingsViewModel
import sh.haven.feature.sftp.SftpScreen
import sh.haven.feature.terminal.TerminalScreen
import sh.haven.feature.terminal.TerminalViewModel
import sh.haven.feature.tunnel.TunnelsScreen

@Composable
fun HavenNavHost(
    preferencesRepository: UserPreferencesRepository,
    connectionRepository: ConnectionRepository,
    sshKeyRepository: SshKeyRepository,
    commandBus: AgentUiCommandBus,
    userMessageBus: UserMessageBus,
) {
    val connections: ConnectionsViewModel = hiltViewModel()
    val terminal: TerminalViewModel = hiltViewModel()
    val desktop: DesktopViewModel = hiltViewModel()
    val settings: SettingsViewModel = hiltViewModel()
    var screen by rememberSaveable { mutableStateOf(Screen.Connections) }
    var tunnels by rememberSaveable { mutableStateOf(false) }
    var terminalProfile by remember { mutableStateOf<String?>(null) }
    var newSession by remember { mutableStateOf<String?>(null) }
    var filesProfile by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    val terminalNavigation by connections.navigateToTerminal.collectAsState()
    val sessionNavigation by connections.newSessionProfileId.collectAsState()
    val vncNavigation by connections.navigateToVnc.collectAsState()
    val rdpNavigation by connections.navigateToRdp.collectAsState()
    val smbNavigation by connections.navigateToSmb.collectAsState()
    val terminalBounce by terminal.navigateToConnections.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(terminalNavigation, sessionNavigation, vncNavigation, rdpNavigation, smbNavigation) {
        terminalNavigation?.let { terminalProfile = it; screen = Screen.Terminal }
        sessionNavigation?.let { newSession = it; screen = Screen.Terminal }
        vncNavigation?.let { n ->
            desktop.addVncSession(host = n.host, port = n.port, password = n.password, username = n.username,
                sshForward = n.sshForward, sshSessionId = n.sshSessionId, profileId = n.profileId, colorDepth = n.colorDepth)
            screen = Screen.Desktop
        }
        rdpNavigation?.let { n ->
            desktop.addRdpSession(host = n.host, port = n.port, username = n.username, password = n.password,
                domain = n.domain, sshForward = n.sshForward, sshSessionId = n.sshSessionId, profileId = n.profileId,
                useNla = n.useNla, colorDepth = n.colorDepth)
            screen = Screen.Desktop
        }
        smbNavigation?.let { filesProfile = it; screen = Screen.Sftp }
        if (terminalNavigation != null || sessionNavigation != null || vncNavigation != null || rdpNavigation != null || smbNavigation != null) connections.onNavigated()
    }
    LaunchedEffect(screen, terminalProfile, newSession) {
        if (screen == Screen.Terminal) {
            terminalProfile?.let { terminal.selectTabByProfileId(it); terminalProfile = null }
            newSession?.let { terminal.addSshTabForProfile(it); newSession = null }
        }
    }
    LaunchedEffect(terminalBounce) {
        if (terminalBounce) { screen = Screen.Connections; terminal.onNavigatedToConnections() }
    }
    LaunchedEffect(commandBus) {
        commandBus.commands.collect { command ->
            when (command) {
                is AgentUiCommand.ConnectProfile, is AgentUiCommand.ConnectFromDeepLink, is AgentUiCommand.PrefillNewConnection -> screen = Screen.Connections
                is AgentUiCommand.NavigateToSftpPath -> { filesProfile = command.profileId; screen = Screen.Sftp }
                else -> {}
            }
        }
    }
    LaunchedEffect(userMessageBus) { userMessageBus.messages.collect { snackbar.showSnackbar(it.text) } }
    LaunchedEffect(screen) { fullscreen = false }
    val fontSize by settings.terminalFontSize.collectAsState()
    val fontPath by settings.terminalFontPath.collectAsState()
    val padding by settings.terminalHorizontalPadding.collectAsState()
    val toolbar by settings.toolbarLayout.collectAsState()
    val showTerminalToolbar by settings.showTerminalToolbar.collectAsState()
    val snippets by settings.snippetLibrary.collectAsState()
    val keepAwake by settings.keepScreenOnInTerminal.collectAsState()
    val autoKeyboard by settings.autoShowKeyboardInTerminal.collectAsState()
    val showTabs by settings.showTerminalTabBar.collectAsState()
    val mouse by settings.mouseInputEnabled.collectAsState()
    val rawKeyboard by settings.rawKeyboardMode.collectAsState()
    val standardKeyboard by settings.allowStandardKeyboard.collectAsState()
    val navMode by settings.navBlockMode.collectAsState()
    val desktopInput by settings.desktopInputMode.collectAsState()
    if (tunnels) {
        TunnelsScreen(onBack = { tunnels = false })
    } else Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (!fullscreen) NavigationBar {
            Screen.entries.forEach { item -> NavigationBarItem(selected = screen == item, onClick = { screen = item },
                icon = { Icon(item.icon, contentDescription = null) }, label = { Text(stringResource(item.labelRes), maxLines = 1) }) }
        } },
    ) { insets -> Box(Modifier.fillMaxSize().padding(insets)) {
        when (screen) {
            Screen.Connections -> ConnectionsScreen(onOpenFiles = { filesProfile = it; screen = Screen.Sftp }, onManageTunnels = { tunnels = true }, viewModel = connections)
            Screen.Terminal -> TerminalScreen(
                isActive = true, fontSize = fontSize, horizontalPaddingDp = padding, terminalFontPath = fontPath,
                toolbarLayout = toolbar, showToolbar = showTerminalToolbar, snippetLibrary = snippets, navBlockMode = navMode,
                keepScreenOnInTerminal = keepAwake, autoShowKeyboardInTerminal = autoKeyboard, showTabBar = showTabs,
                mouseInputEnabled = mouse, rawKeyboardMode = rawKeyboard, allowStandardKeyboard = standardKeyboard,
                onToggleStandardKeyboard = { settings.setAllowStandardKeyboard(!standardKeyboard) },
                onToggleRawKeyboard = { settings.setRawKeyboardMode(!rawKeyboard) },
                onFullscreenChanged = { fullscreen = it }, onNavigateToConnections = { screen = Screen.Connections },
                onNavigateToSftp = { filesProfile = terminal.tabs.value.getOrNull(terminal.activeTabIndex.value)?.profileId; screen = Screen.Sftp },
                onNavigateToVnc = { host, port, user, password, forward, session, depth ->
                    desktop.addVncSession(host, port, password, user, forward, session, colorDepth = depth); screen = Screen.Desktop
                }, onToolbarLayoutChanged = settings::setToolbarLayout, onSnippetLibraryChanged = settings::setSnippetLibrary,
                onOpenToolbarSettings = { screen = Screen.Settings }, viewModel = terminal)
            Screen.Desktop -> DesktopScreen(desktop, toolbarLayout = toolbar, snippetLibrary = snippets, navBlockMode = navMode,
                inputMode = desktopInput, onSetInputMode = settings::setDesktopInputMode,
                fullscreen = fullscreen, onFullscreenChanged = { fullscreen = it },
                onToolbarLayoutChanged = settings::setToolbarLayout, onSnippetLibraryChanged = settings::setSnippetLibrary)
            Screen.Sftp -> SftpScreen(filesProfile, onProfileConsumed = { filesProfile = null }, onAttachFinished = { screen = Screen.Terminal })
            Screen.Keys -> KeysScreen()
            Screen.Settings -> SettingsScreen(onManageTunnels = { tunnels = true }, viewModel = settings)
        }
    } }
    ConnectionAuthDialogs(connections)
}
