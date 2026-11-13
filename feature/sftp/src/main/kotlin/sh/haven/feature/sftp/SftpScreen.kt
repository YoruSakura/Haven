package sh.haven.feature.sftp

import sh.haven.core.ui.R as UiR
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.feature.editor.EditorScreen
import sh.haven.feature.editor.EditorState

@Composable
fun SftpScreen(pendingProfileId: String? = null, onProfileConsumed: () -> Unit = {}, onAttachFinished: () -> Unit = {}, viewModel: SftpViewModel = hiltViewModel()) {
    val profiles by viewModel.connectedProfiles.collectAsState()
    val active by viewModel.activeProfileId.collectAsState()
    val path by viewModel.currentPath.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val progress by viewModel.transferProgress.collectAsState()
    val clipboard by viewModel.clipboard.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    val attach by viewModel.attachRequest.collectAsState()
    val editor by viewModel.editorFile.collectAsState()
    val saving by viewModel.editorSaving.collectAsState()
    val conflict by viewModel.conflictPrompt.collectAsState()
    val uploadConflict by viewModel.uploadConflict.collectAsState()
    val pendingCount by viewModel.pastePendingCount.collectAsState()
    val selected by viewModel.selectedPaths.collectAsState()
    var actionEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var downloadEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var deleting by remember { mutableStateOf<SftpEntry?>(null) }
    var renameEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newFolder by remember { mutableStateOf<String?>(null) }
    var chmodEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var chmodText by remember { mutableStateOf("644") }
    var wrap by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refresh() }
    val manageStorage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { viewModel.addSafFolder(it) } }
    val uploadFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { viewModel.uploadFolder(it) } }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val files = uris.map { uri ->
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
            name to uri
        }
        viewModel.uploadFiles(files)
    }

    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        downloadEntry?.let { entry -> if (uri != null) viewModel.downloadFile(entry, uri) }; downloadEntry = null
    }
    LaunchedEffect(pendingProfileId) {
        pendingProfileId?.let { viewModel.setPendingSmbProfile(it) }
        viewModel.syncConnectedProfiles()
        if (pendingProfileId != null) onProfileConsumed()
    }
    LaunchedEffect(error, message) {
        (error ?: message)?.let { snack.showSnackbar(it) }
        if (error != null) viewModel.dismissError()
        if (message != null) viewModel.dismissMessage()
    }
    if (editor !is SftpViewModel.EditorFileState.Closed) {
        BackHandler { viewModel.closeEditor() }
        val state = when (val e = editor) {
            is SftpViewModel.EditorFileState.Open -> EditorState.Loaded(e.content, e.fileName, e.filePath)
            is SftpViewModel.EditorFileState.Error -> EditorState.Error(e.message)
            else -> EditorState.Loading
        }
        EditorScreen(state, wrap, saving, onToggleWordWrap = { wrap = !wrap }, onSave = viewModel::saveEditorContent, onBack = viewModel::closeEditor)
        return
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                profiles.filter { attach == null || it.isSsh }.forEach { p -> FilterChip(active == p.id, onClick = { viewModel.selectProfile(p.id) }, enabled = !loading, label = { Text(p.label) }) }
                TextButton(onClick = { folder.launch(null) }) { Text(stringResource(UiR.string.client_add_local_folder)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = viewModel::navigateUp) { Text(stringResource(R.string.sftp_navigate_up_icon)) }
                Text(path, Modifier.weight(1f).padding(12.dp), maxLines = 2)
                TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.sftp_refresh)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { upload.launch(arrayOf("*/*")) }) { Text(stringResource(UiR.string.client_upload_files)) }
                TextButton(onClick = { uploadFolder.launch(null) }) { Text(stringResource(R.string.sftp_upload_folder)) }
                TextButton(onClick = { newFolder = "" }) { Text(stringResource(R.string.sftp_new_folder)) }
                TextButton(onClick = viewModel::toggleShowHidden) { Text(stringResource(UiR.string.client_hidden_files)) }
                if (clipboard != null) TextButton(onClick = viewModel::pasteFromClipboard) { Text(stringResource(UiR.string.client_paste_count, clipboard!!.entries.size)) }
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) manageStorage.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")))
                    else permission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }) { Text(stringResource(UiR.string.client_storage_access)) }
            }
            if (selected.isNotEmpty()) Row {
                TextButton(onClick = { viewModel.copyToClipboard(viewModel.selectedEntries(), false); viewModel.clearSelection() }) { Text(stringResource(UiR.string.client_copy_selected)) }
                TextButton(onClick = { viewModel.copyToClipboard(viewModel.selectedEntries(), true); viewModel.clearSelection() }) { Text(stringResource(UiR.string.client_cut_selected)) }
                TextButton(onClick = viewModel::clearSelection) { Text(stringResource(R.string.sftp_selection_clear)) }
            }
            if (pendingCount > 0) Row {
                Text(stringResource(UiR.string.client_pending_transfers, pendingCount), Modifier.weight(1f).padding(12.dp))
                TextButton(onClick = viewModel::resumePasteQueue) { Text(stringResource(R.string.sftp_paste_resume)) }
                TextButton(onClick = viewModel::discardPasteQueue) { Text(stringResource(R.string.sftp_paste_discard)) }
            }
            if (attach != null) Row {
                TextButton(onClick = { viewModel.confirmAttachFolder(); onAttachFinished() }, enabled = profiles.any { it.id == active && it.isSsh } && !loading) { Text(stringResource(R.string.sftp_attach_use_folder)) }
                TextButton(onClick = { viewModel.cancelAttach(); onAttachFinished() }) { Text(stringResource(UiR.string.client_cancel_attachment)) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            progress?.let { p -> Text("${p.fileName}: ${p.transferredBytes} / ${p.totalBytes}", Modifier.padding(8.dp)); LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth()) }
            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.path }) { entry ->
                    ListItem(modifier = Modifier.clickable { if (entry.isDirectory) viewModel.navigateTo(entry.path) else actionEntry = entry },
                        headlineContent = { Text((if (entry.isDirectory) "▸ " else "") + entry.name) },
                        supportingContent = { Text(if (entry.isDirectory) stringResource(UiR.string.client_folder) else stringResource(UiR.string.client_file_bytes, entry.size)) },
                        leadingContent = { Checkbox(entry.path in selected, onCheckedChange = { viewModel.toggleSelection(entry) }) },
                        trailingContent = { TextButton(onClick = { actionEntry = entry }) { Text(stringResource(UiR.string.client_more)) } })
                    HorizontalDivider()
                }
                if (!loading && entries.isEmpty()) item { Text(stringResource(UiR.string.client_empty_folder), Modifier.padding(24.dp)) }
            }
        }
    }
    actionEntry?.let { e -> AlertDialog(onDismissRequest = { actionEntry = null }, title = { Text(e.name) }, text = { Column {
        if (!e.isDirectory) {
            TextButton(onClick = { actionEntry = null; viewModel.openInEditor(e) }) { Text(stringResource(UiR.string.client_edit_as_text)) }
            TextButton(onClick = { actionEntry = null; downloadEntry = e; download.launch(e.name) }) { Text(stringResource(R.string.common_download)) }
        }
        TextButton(onClick = { viewModel.copyToClipboard(listOf(e), false); actionEntry = null }) { Text(stringResource(R.string.common_copy)) }
        TextButton(onClick = { viewModel.copyToClipboard(listOf(e), true); actionEntry = null }) { Text(stringResource(R.string.sftp_cut)) }
        TextButton(onClick = { renameEntry = e; renameText = e.name; actionEntry = null }) { Text(stringResource(R.string.common_rename)) }
        if (viewModel.supportsPermissions()) TextButton(onClick = { chmodEntry = e; actionEntry = null }) { Text(stringResource(R.string.sftp_permissions_dialog_title)) }
        TextButton(onClick = { deleting = e; actionEntry = null }) { Text(stringResource(R.string.common_delete)) }
    } }, confirmButton = { TextButton(onClick = { actionEntry = null }) { Text(stringResource(R.string.common_close)) } }) }
    deleting?.let { e -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(UiR.string.client_delete_named, e.name)) },
        confirmButton = { TextButton(onClick = { viewModel.deleteEntry(e); deleting = null }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } }) }
    renameEntry?.let { e -> FileNameDialog(stringResource(R.string.common_rename), renameText, { renameText = it }, { viewModel.renameEntry(e, renameText); renameEntry = null }, { renameEntry = null }) }
    newFolder?.let { name -> FileNameDialog(stringResource(R.string.sftp_new_folder), name, { newFolder = it }, { viewModel.createDirectory(name); newFolder = null }, { newFolder = null }) }
    chmodEntry?.let { e -> FileNameDialog(stringResource(UiR.string.client_permissions_octal), chmodText, { chmodText = it }, {
        chmodText.toIntOrNull(8)?.let { viewModel.chmodEntry(e, it) }; chmodEntry = null
    }, { chmodEntry = null }) }
    conflict?.let { c -> AlertDialog(onDismissRequest = { c.onChoice(ConflictAction.SKIP, false) }, title = { Text(stringResource(UiR.string.client_file_exists, c.fileName)) },
        text = { Column { ConflictAction.entries.filter { it != ConflictAction.RESUME || c.canResume }.forEach { action ->
            TextButton(onClick = { c.onChoice(action, false) }) { Text(action.name.lowercase()) }
        } } }, confirmButton = {}) }
    uploadConflict?.let { c -> AlertDialog(onDismissRequest = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP) }, title = { Text(stringResource(UiR.string.client_file_exists, c.fileName)) },
        confirmButton = { TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.REPLACE) }) { Text(stringResource(R.string.sftp_replace)) } },
        dismissButton = { TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP) }) { Text(stringResource(R.string.sftp_skip)) } }) }
}

@Composable
private fun FileNameDialog(title: String, value: String, onChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, onChange, singleLine = true) },
        confirmButton = { TextButton(enabled = value.isNotBlank() && value !in setOf(".", "..") && !value.contains('/') && !value.contains('\u0000'), onClick = onConfirm) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
