package sh.haven.feature.settings

import sh.haven.core.ui.R as UiR
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.platform.PlatformShell

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onManageTunnels: () -> Unit = {}, viewModel: SettingsViewModel = hiltViewModel()) {
    val theme by viewModel.theme.collectAsState()
    val biometric by viewModel.biometricEnabled.collectAsState()
    val secure by viewModel.screenSecurity.collectAsState()
    val root by viewModel.rootFilesEnabled.collectAsState()
    val keepAwake by viewModel.keepScreenOnInTerminal.collectAsState()
    val keyboard by viewModel.autoShowKeyboardInTerminal.collectAsState()
    val terminalToolbar by viewModel.showTerminalToolbar.collectAsState()
    val clipboard by viewModel.remoteClipboardToLocalEnabled.collectAsState()
    val logging by viewModel.connectionLoggingEnabled.collectAsState()
    val fontSize by viewModel.terminalFontSize.collectAsState()
    val lockTimeout by viewModel.lockTimeout.collectAsState()
    val sessionManager by viewModel.sessionManager.collectAsState()
    val updateCheck by viewModel.updateCheckEnabled.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    var backupAction by remember { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var backupOverwrite by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.exportBackup(uri, backupPassword)
        backupPassword = ""
    }
    val exportSshPilot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/yaml")) { uri ->
        if (uri != null) viewModel.exportBackup(uri, backupPassword, sshPilot = true)
        backupPassword = ""
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBackup(uri, backupPassword, overwriteExisting = backupOverwrite)
        backupPassword = ""
        backupOverwrite = false
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(UiR.string.client_appearance_and_terminal), style = MaterialTheme.typography.titleMedium)
            Row { UserPreferencesRepository.ThemeMode.entries.forEach { mode ->
                FilterChip(selected = theme == mode, onClick = { viewModel.setTheme(mode) }, label = { Text(mode.label) })
            } }
            Text(stringResource(UiR.string.client_terminal_font_size, fontSize.toInt()))
            Slider(fontSize.toFloat(), { viewModel.setTerminalFontSize(it.toInt()) }, valueRange = 8f..32f)
            SettingSwitch(stringResource(R.string.settings_keep_screen_on_title), keepAwake, viewModel::setKeepScreenOnInTerminal)
            SettingSwitch(stringResource(UiR.string.client_show_keyboard_on_connection), keyboard, viewModel::setAutoShowKeyboardInTerminal)
            SettingSwitch(
                stringResource(UiR.string.client_show_terminal_toolbar),
                terminalToolbar,
                viewModel::setShowTerminalToolbar,
                supportingText = stringResource(UiR.string.client_terminal_toolbar_keyboard_hint),
            )
            var managers by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { managers = true }) { Text(stringResource(UiR.string.client_default_session_manager, sessionManager.label)) }
                DropdownMenu(managers, { managers = false }) {
                    UserPreferencesRepository.SessionManager.entries.forEach { manager ->
                        DropdownMenuItem(text = { Text(manager.label) }, onClick = { viewModel.setSessionManager(manager); managers = false })
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(UiR.string.client_security), style = MaterialTheme.typography.titleMedium)
            if (viewModel.biometricAvailable) SettingSwitch(stringResource(UiR.string.client_lock_app_with_biometrics), biometric, viewModel::setBiometricEnabled)
            SettingSwitch(stringResource(UiR.string.client_protect_screen_content), secure, viewModel::setScreenSecurity)
            if (biometric) {
                var timeouts by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { timeouts = true }) { Text(stringResource(UiR.string.client_lock_after, lockTimeout.label)) }
                    DropdownMenu(timeouts, { timeouts = false }) { UserPreferencesRepository.LockTimeout.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(t.label) }, onClick = { viewModel.setLockTimeout(t); timeouts = false })
                    } }
                }
            }
            SettingSwitch(stringResource(UiR.string.client_copy_remote_desktop_clipboard_to_phone), clipboard, viewModel::setRemoteClipboardToLocalEnabled)
            SettingSwitch(stringResource(UiR.string.client_save_connection_logs), logging, viewModel::setConnectionLoggingEnabled)
            HorizontalDivider()
            Text(stringResource(UiR.string.client_android_access_and_networking), style = MaterialTheme.typography.titleMedium)
            SettingSwitch(stringResource(UiR.string.client_enable_root_file_access), root, viewModel::setRootFilesEnabled)
            Text(stringResource(UiR.string.client_root_access_is_requested_when_you_open_root_in_files), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { PlatformShell.requestPermission() }) { Text(stringResource(UiR.string.client_authorize_shizuku)) }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(UiR.string.client_android_app_and_battery_settings)) }
            OutlinedButton(onClick = onManageTunnels) { Text(stringResource(UiR.string.client_manage_vpn_connections)) }
            HorizontalDivider()
            Text(stringResource(UiR.string.client_encrypted_backup), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(UiR.string.client_export_connections_credentials_and_settings_to_a_password_protected_file_im))
            Row {
                OutlinedButton(enabled = backupStatus !is SettingsViewModel.BackupStatus.InProgress,
                    onClick = { backupAction = "export" }) { Text(stringResource(R.string.settings_export_backup_title)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = backupStatus !is SettingsViewModel.BackupStatus.InProgress,
                    onClick = { backupOverwrite = false; backupAction = "import" }) { Text(stringResource(UiR.string.client_import_backup)) }
            }
            Text(stringResource(UiR.string.client_sshpilot_backup_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = backupStatus !is SettingsViewModel.BackupStatus.InProgress,
                onClick = { backupAction = "sshpilot-export" }) { Text(stringResource(UiR.string.client_sshpilot_export)) }
            when (val status = backupStatus) {
                is SettingsViewModel.BackupStatus.InProgress -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is SettingsViewModel.BackupStatus.Success -> Text(status.message)
                is SettingsViewModel.BackupStatus.Error -> Text(status.message, color = MaterialTheme.colorScheme.error)
                else -> {}
            }
            HorizontalDivider()
            SettingSwitch(stringResource(R.string.settings_update_check_title), updateCheck, viewModel::setUpdateCheckEnabled)
        }
    }
    backupAction?.let { action -> AlertDialog(onDismissRequest = { backupAction = null; backupPassword = "" },
        title = { Text(stringResource(when (action) {
            "import" -> UiR.string.client_import_backup
            "sshpilot-export" -> UiR.string.client_sshpilot_export
            else -> R.string.settings_export_backup_title
        })) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (action == "import") Text(stringResource(UiR.string.client_sshpilot_password_hint))
            if (action == "import") SettingSwitch(
                stringResource(UiR.string.client_sshpilot_overwrite_title), backupOverwrite, { backupOverwrite = it },
                supportingText = stringResource(if (backupOverwrite) UiR.string.client_sshpilot_overwrite_hint
                    else UiR.string.client_sshpilot_merge_hint),
            )
            if (action == "sshpilot-export") Text(stringResource(UiR.string.client_sshpilot_export_hint))
            OutlinedTextField(backupPassword, { backupPassword = it }, label = { Text(stringResource(UiR.string.client_backup_password)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        } },
        confirmButton = { TextButton(enabled = backupPassword.isNotEmpty() || action == "import", onClick = {
            backupAction = null
            when (action) {
                "import" -> restore.launch(arrayOf("*/*"))
                "sshpilot-export" -> exportSshPilot.launch("haven-sshpilot-servers.yaml")
                else -> export.launch("haven-backup.enc")
            }
        }) { Text(stringResource(UiR.string.client_continue)) } },
        dismissButton = { TextButton(onClick = { backupAction = null; backupPassword = "" }) { Text(stringResource(R.string.common_cancel)) } }) }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit, supportingText: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label)
            if (supportingText != null) Text(supportingText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onCheckedChange = null)
    }
}
