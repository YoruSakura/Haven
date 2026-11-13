package sh.haven.feature.connections
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ConnectionAuthDialogs(viewModel: ConnectionsViewModel = hiltViewModel()) {
val sshKeys by viewModel.sshKeys.collectAsState()
val passwordFallback by viewModel.passwordFallback.collectAsState()
val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsState()
val fidoTouchPrompt by viewModel.fidoTouchPrompt.collectAsState()
val keyboardInteractiveAuth by viewModel.keyboardInteractiveAuth.collectAsState()
val sshAuthBanner by viewModel.sshAuthBanner.collectAsState()
val sessionSelection by viewModel.sessionSelection.collectAsState()
val connectConfirm by viewModel.connectConfirm.collectAsState()
connectConfirm?.let { confirm ->
        val target = confirm.profile.label.ifBlank { confirm.profile.host }
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeepLinkConnect() },
            title = { Text(stringResource(R.string.connections_deeplink_confirm_title)) },
            text = {
                Text(
                    if (confirm.sessionName != null) {
                        stringResource(
                            R.string.connections_deeplink_confirm_session,
                            target,
                            confirm.sessionName,
                        )
                    } else {
                        stringResource(R.string.connections_deeplink_confirm_message, target)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeepLinkConnect() }) {
                    Text(stringResource(R.string.connections_deeplink_confirm_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeepLinkConnect() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
passwordFallback?.let { profile ->
        val assignedKey = profile.keyId?.let { id -> sshKeys.firstOrNull { it.id == id } }
        // If the profile has an encrypted key assigned and the initial
        // key-only connect attempt just failed, this dialog is specifically
        // asking for the key passphrase — not a host password.
        val mode = when {
            assignedKey != null && assignedKey.isEncrypted ->
                PasswordDialogMode.ASSIGNED_ENCRYPTED_KEY_PASSPHRASE
            assignedKey != null ->
                PasswordDialogMode.PASSWORD_OR_ASSIGNED_KEY
            sshKeys.isNotEmpty() ->
                PasswordDialogMode.PASSWORD_OR_UNASSIGNED_KEY
            else ->
                PasswordDialogMode.PASSWORD_ONLY
        }
        PasswordDialog(
            profile = profile,
            hasKeys = sshKeys.isNotEmpty(),
            mode = mode,
            assignedKeyLabel = assignedKey?.label,
            onDismiss = { viewModel.dismissPasswordFallback() },
            onConnect = { username, password, rememberPassword ->
                // Tunnel-mode (#121a) vs normal SSH login + the dismiss are all

                // answer_auth_prompt verb so both paths stay identical.
                viewModel.answerPasswordFallback(
                    password,
                    username = username,
                    rememberPassword = rememberPassword,
                )
            },
        )
    }
hostKeyPrompt?.let { prompt ->
        when (prompt) {
            is ConnectionsViewModel.HostKeyPrompt.NewHost -> {
                NewHostKeyDialog(
                    entry = prompt.entry,
                    onTrust = { viewModel.onHostKeyAccepted() },
                    onCancel = { viewModel.onHostKeyRejected() },
                )
            }
            is ConnectionsViewModel.HostKeyPrompt.KeyChanged -> {
                KeyChangedDialog(
                    oldFingerprint = prompt.oldFingerprint,
                    entry = prompt.entry,
                    onAccept = { viewModel.onHostKeyAccepted() },
                    onDisconnect = { viewModel.onHostKeyRejected() },
                )
            }
        }
    }
fidoTouchPrompt?.let { prompt ->
        FidoTouchPromptDialog(prompt = prompt, onCancel = { viewModel.cancelFido() })
    }
keyboardInteractiveAuth?.let { pending ->
        KeyboardInteractiveDialog(
            challenge = pending.challenge,
            onSubmit = { viewModel.submitKeyboardInteractiveResponses(it) },
            onCancel = { viewModel.cancelKeyboardInteractive() },
            bannerMessage = sshAuthBanner?.message,
        )
    }
if (keyboardInteractiveAuth == null) {
        sshAuthBanner?.let { banner ->
            SshAuthBannerDialog(
                banner = banner,
                onCancel = { viewModel.cancelSshAuthBanner() },
            )
        }
    }
sessionSelection?.let { selection ->
        sh.haven.core.ui.SessionPickerDialog(
            title = stringResource(R.string.connections_sessions_title, selection.managerLabel),
            sessionNames = selection.sessionNames,
            suggestedNewName = selection.suggestedNewName,
            createButtonContentDescription = stringResource(R.string.connections_new_session_create),
            onSelect = { name -> viewModel.onSessionSelected(selection.sessionId, name) },
            onNewSession = { name ->
                // Empty string is treated as "use the suggestion the ViewModel
                // would generate" — pass null down so the existing fallback
                // path runs. Any non-empty user input goes through as-is.
                viewModel.onSessionSelected(selection.sessionId, name.takeIf { it.isNotBlank() })
            },
            onDismiss = { viewModel.dismissSessionPicker() },
            previousSessionNames = selection.previousSessionNames,
            restorePreviousLabel = if (selection.previousSessionNames.size > 1) {
                stringResource(R.string.connections_restore_previous_sessions, selection.previousSessionNames.size)
            } else null,
            onRestorePrevious = { names -> viewModel.restorePreviousSessions(selection.sessionId, names) },
            canKill = selection.manager.killCommand != null,
            canRename = selection.manager.renameCommand != null,
            killContentDescription = stringResource(R.string.connections_kill_session),
            renameContentDescription = stringResource(R.string.connections_rename_session),
            onKill = { name -> viewModel.killRemoteSession(name) },
            onRename = { old, new -> viewModel.renameRemoteSession(old, new) },
            plainShellLabel = stringResource(R.string.connections_open_plain_shell),
            onPlainShell = { viewModel.onPlainShellSelected(selection.sessionId) },
            cancelLabel = stringResource(R.string.common_cancel),
            renameDialog = { currentLabel, onDismiss, onRenameTo ->
                RenameDialog(currentLabel = currentLabel, onDismiss = onDismiss, onRename = onRenameTo)
            },
        )
    }
}
@Composable
private fun RenameDialog(
    currentLabel: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var label by remember { mutableStateOf(currentLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_rename_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.common_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(label) },
                enabled = label.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
