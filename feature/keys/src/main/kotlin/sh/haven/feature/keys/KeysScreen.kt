package sh.haven.feature.keys

import sh.haven.core.ui.R as UiR
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.LinearProgressIndicator
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import sh.haven.core.ssh.openkeychain.OpenKeychainKeyData
import sh.haven.core.ssh.openkeychain.OpenKeychainProvider
import sh.haven.core.ui.PasswordField
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreStore
import sh.haven.core.security.SshKeyGenerator
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val DEFAULT_COLLAPSED_SECTIONS = emptySet<String>()
@Composable
fun KeysScreen(viewModel: KeysViewModel = hiltViewModel()) {
    val keys by viewModel.keys.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    val generating by viewModel.generating.collectAsState()
    val needsPassphrase by viewModel.needsPassphrase.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val fidoPrompt by viewModel.fidoTouchPrompt.collectAsState()
    val discovered by viewModel.discoveredCredentials.collectAsState()
    val verifyRequired by viewModel.skVerifyRequired.collectAsState()
    val context = LocalContext.current
    var showGenerate by remember { mutableStateOf(false) }
    var showRegister by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<SshKey?>(null) }
    var deleting by remember { mutableStateOf<SshKey?>(null) }
    var passphraseKey by remember { mutableStateOf<SshKey?>(null) }
    val exportId by viewModel.pendingExportKeyId.collectAsState()
    val certExportId by viewModel.pendingCertExportKeyId.collectAsState()
    val certId by viewModel.pendingCertKeyId.collectAsState()
    val snack = remember { SnackbarHostState() }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importFromUri(context, it) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        exportId?.let { id -> if (uri != null) viewModel.exportPrivateKey(context, id, uri) }; viewModel.clearPendingExport()
    }
    val exportCert = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        certExportId?.let { id -> if (uri != null) viewModel.exportCertificate(context, id, uri) }; viewModel.clearPendingCertExport()
    }
    val importCert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        certId?.let { id -> if (uri != null) viewModel.importCertificateFromUri(context, id, uri) }; viewModel.clearPendingCertificate()
    }
    LaunchedEffect(exportId) { exportId?.let { export.launch(viewModel.getExportFileName(it)) } }
    LaunchedEffect(certExportId) { certExportId?.let { exportCert.launch(viewModel.getCertExportFileName(it)) } }
    LaunchedEffect(certId) { if (certId != null) importCert.launch(arrayOf("*/*")) }
    LaunchedEffect(error, message) {
        (error ?: message)?.let { snack.showSnackbar(it) }
        if (error != null) viewModel.dismissError()
        if (message != null) viewModel.dismissMessage()
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                TextButton(onClick = { showGenerate = true }) { Text(stringResource(UiR.string.client_generate_key)) }
                TextButton(onClick = { import.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.common_import)) }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() }
                        ?.let { viewModel.startImport(it.toByteArray()) }
                }) { Text(stringResource(R.string.common_paste)) }
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { showRegister = true }) { Text(stringResource(UiR.string.client_set_up_fido2_key)) }
                TextButton(onClick = { viewModel.discoverFromSecurityKey() }) { Text(stringResource(UiR.string.client_import_from_security_key)) }
            }
            if (generating) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                items(keys, key = { it.id }) { key ->
                    ListItem(headlineContent = { Text(key.label) }, supportingContent = { Column {
                        Text(key.keyType)
                        Text(key.fingerprintSha256, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { copyPublicKey(context, key) }) { Text(stringResource(R.string.keys_copy_public_key)) }
                            TextButton(onClick = { rename = key }) { Text(stringResource(R.string.common_rename)) }
                            TextButton(onClick = { deleting = key }) { Text(stringResource(R.string.common_delete)) }
                        }
                        Row {
                            Text(stringResource(UiR.string.client_offer_for_authentication), Modifier.weight(1f))
                            Switch(key.enabledForAuth, onCheckedChange = { viewModel.setKeyEnabledForAuth(key.id, it) })
                        }
                        Row {
                            Text(stringResource(UiR.string.client_require_biometric_unlock), Modifier.weight(1f))
                            Switch(key.biometricProtected, onCheckedChange = { viewModel.setBiometricProtected(key.id, it) })
                        }
                        if (key.keyType.startsWith("sk-")) Row {
                            Text(stringResource(UiR.string.client_require_fido2_user_verification), Modifier.weight(1f))
                            Switch(verifyRequired[key.id] ?: false, onCheckedChange = { viewModel.setSkVerifyRequired(key.id, it) })
                        }
                        Row {
                            if (key.isEncrypted) TextButton(onClick = { passphraseKey = key }) { Text(stringResource(UiR.string.client_store_passphrase)) }
                            TextButton(onClick = { viewModel.requestExport(key.id) }) { Text(stringResource(R.string.keys_export_private_key)) }
                        }
                        Row {
                            TextButton(onClick = { viewModel.requestAttachCertificate(key.id) }) { Text(stringResource(R.string.keys_attach_certificate)) }
                            if (key.certificateBytes != null) {
                                TextButton(onClick = { viewModel.requestCertExport(key.id) }) { Text(stringResource(R.string.keys_export_certificate)) }
                                TextButton(onClick = { viewModel.removeCertificate(key.id) }) { Text(stringResource(R.string.keys_remove_certificate)) }
                            }
                        }
                    } })
                    HorizontalDivider()
                }
                if (keys.isEmpty()) item { Text(stringResource(UiR.string.client_generate_or_import_an_ssh_key_or_enroll_a_fido2_security_key), Modifier.padding(24.dp)) }
            }
        }
    }
    fidoPrompt?.let { KeysFidoTouchPromptDialog(it, onCancel = { viewModel.cancelFido() }) }
    if (discovered.isNotEmpty()) DiscoveredCredentialsPicker(credentials = discovered,
        onImport = { viewModel.importDiscoveredCredentials(it) }, onDismiss = { viewModel.dismissDiscoveryPicker() })
    if (showGenerate) GenerateKeyDialog(onDismiss = { showGenerate = false }, onGenerate = { label, type, hardware ->
        if (hardware) viewModel.generateHardwareKey(label) else viewModel.generateKey(label, type)
        showGenerate = false
    })
    if (showRegister) RegisterOnSecurityKeyDialog(onDismiss = { showRegister = false }, onRegister = { label, verify, pin ->
        viewModel.registerOnSecurityKey(label, verify, pin); showRegister = false
    })
    if (needsPassphrase) PassphraseDialog(onConfirm = { viewModel.retryImportWithPassphrase(it) }, onDismiss = { viewModel.cancelImport() })
    importResult?.let { result -> ImportLabelDialog(keyType = result.keyType, fingerprint = result.fingerprintSha256,
        defaultLabel = commentOf(result.publicKeyOpenSsh), onConfirm = { viewModel.saveImportedKey(it) }, onDismiss = { viewModel.cancelImport() }) }
    rename?.let { key -> RenameKeyDialog(key.label, onConfirm = { viewModel.renameKey(key.id, it); rename = null }, onDismiss = { rename = null }) }
    passphraseKey?.let { key -> PassphraseDialog(onConfirm = { viewModel.setKeyStoredPassphrase(key.id, it); passphraseKey = null }, onDismiss = { passphraseKey = null }) }
    deleting?.let { key -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(UiR.string.client_delete_named, key.label)) },
        confirmButton = { TextButton(onClick = { viewModel.deleteKey(key.id); deleting = null }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } }) }
}

@Composable
private fun SecurityKeyChooser(
    onSetUp: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_security_key)) },
        text = {
            Column {
                ListItem(
                    modifier = Modifier.clickable { onSetUp() },
                    headlineContent = { Text(stringResource(R.string.keys_security_key_setup)) },
                    supportingContent = { Text(stringResource(R.string.keys_security_key_setup_hint)) },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
                ListItem(
                    modifier = Modifier.clickable { onImport() },
                    headlineContent = { Text(stringResource(R.string.keys_security_key_import)) },
                    supportingContent = { Text(stringResource(R.string.keys_security_key_import_hint)) },
                    leadingContent = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Collect label + verify-required + PIN, then register (create) a new SSH-SK
 * credential on a connected/tapped security key (CTAP2 MakeCredential). The PIN
 * is gathered here (not mid-exchange) so the whole CTAP exchange runs as one
 * continuous tap — USB: plug & touch; NFC: tap & hold. The touch step itself is
 * rendered by the shared [KeysFidoTouchPromptDialog]. To enrol several keys,
 * register, swap the key, and repeat.
 */
@Composable
private fun RegisterOnSecurityKeyDialog(
    onDismiss: () -> Unit,
    onRegister: (label: String, verifyRequired: Boolean, pin: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var verifyRequired by remember { mutableStateOf(true) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && pin != confirm
    val canRegister = pin.length >= 4 && pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_register_on_security_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.keys_register_on_security_key_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.common_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.keys_register_verify_required))
                    Switch(checked = verifyRequired, onCheckedChange = { verifyRequired = it })
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.keys_register_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.keys_register_pin_confirm)) },
                    singleLine = true,
                    isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        if (mismatch) R.string.keys_register_pin_mismatch
                        else R.string.keys_register_pin_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mismatch) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRegister(label, verifyRequired, pin) },
                enabled = canRegister,
            ) {
                Text(stringResource(R.string.keys_register_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: (label: String, keyType: SshKeyGenerator.KeyType, hardwareBacked: Boolean) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(SshKeyGenerator.KeyType.ED25519) }
    var expanded by remember { mutableStateOf(false) }
    // "Generate in the device's security chip" opt-in. On: the algorithm is
    // locked to P-256 (the only curve KeyMint's hardware backend supports)
    // and the constraints are spelled out before generating.
    var hardwareBacked by remember { mutableStateOf(false) }
    // Resolved up front: stringResource is @Composable, the confirm onClick
    // below is not.
    val hardwareDefaultLabel = stringResource(R.string.keys_hardware_default_label)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_generate_ssh_key)) },
        text = {
            // Scrollable: the hardware branch adds a toggle + locked field +
            // four constraint lines on top of the label field — more than a
            // small phone's dialog can show unscrolled (same reasoning as the
            // bulk-delete dialog).
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.common_label)) },
                    placeholder = { Text(stringResource(R.string.keys_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Hardware key: chip toggle above the algorithm picker, so
                // locking the picker reads as a consequence of the toggle.
                // Whole row is the hit target (Role.Switch semantics); the
                // Switch itself gets onCheckedChange = null so the toggle
                // isn't announced twice.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .toggleable(
                            value = hardwareBacked,
                            role = Role.Switch,
                            onValueChange = { hardwareBacked = it },
                        ),
                ) {
                    Text(
                        text = stringResource(R.string.keys_hardware_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = hardwareBacked,
                        onCheckedChange = null,
                    )
                }

                if (hardwareBacked) {
                    // Key type is a property of the chip's KeyMint backend,
                    // not a choice — show it locked rather than hiding the
                    // field, so the user still sees what they're getting.
                    OutlinedTextField(
                        value = stringResource(R.string.keys_hardware_algorithm),
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text(stringResource(R.string.keys_key_type)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.keys_hardware_constraints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Box(modifier = Modifier.padding(top = 16.dp)) {
                        OutlinedTextField(
                            value = selectedType.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.keys_key_type)) },
                            modifier = Modifier
                                .fillMaxWidth(),
                        )
                        // Invisible clickable overlay to open dropdown
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .combinedClickable(onClick = { expanded = true }),
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            SshKeyGenerator.KeyType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.displayName) },
                                    onClick = {
                                        selectedType = type
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Blank-label fallback mirrors the software path's
                    // displayName fallback, via a resource so it localizes.
                    onGenerate(
                        label.ifBlank { hardwareDefaultLabel },
                        selectedType,
                        hardwareBacked,
                    )
                },
            ) {
                Text(stringResource(R.string.keys_generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun PassphraseDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.keys_encrypted_key),
    prompt: String = stringResource(R.string.keys_passphrase_prompt),
    confirmLabel: String = stringResource(R.string.keys_unlock),
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodyMedium,
                )
                PasswordField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.keys_passphrase),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotEmpty(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun ImportLabelDialog(
    keyType: String,
    fingerprint: String,
    defaultLabel: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(defaultLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_import_ssh_key)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.common_label)) },
                    placeholder = { Text(stringResource(R.string.keys_label_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    keyType,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label.ifBlank { keyType }) },
            ) {
                Text(stringResource(R.string.keys_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun RenameKeyDialog(
    currentLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(currentLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_rename_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.common_label)) },
                placeholder = { Text(stringResource(R.string.keys_label_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label) },
                enabled = label.isNotBlank(),
            ) {
                Text(stringResource(R.string.keys_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Extract the trailing comment from an OpenSSH public-key line
 * (`<type> <base64> [comment]`), or "" if there's none. Used to seed the
 * import-label field from the key's own comment (#231).
 */
private fun commentOf(publicKeyOpenSsh: String): String =
    sh.haven.core.ssh.SshPublicKeyComment.commentOf(publicKeyOpenSsh)

private fun copyPublicKey(context: Context, sshKey: SshKey) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // Imported keys and security-key credentials are stored without a comment,
    // so the copied line had nothing identifying it in an authorized_keys file.
    // Fall back to the key's own label (#449).
    val line = sh.haven.core.ssh.SshPublicKeyComment.withComment(
        sshKey.publicKeyOpenSsh,
        sshKey.label,
    )
    clipboard.setPrimaryClip(ClipData.newPlainText(
        context.getString(R.string.keys_ssh_public_key_clip_label), line,
    ))
}
