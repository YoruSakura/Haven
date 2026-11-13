package sh.haven.feature.connections

import sh.haven.core.ui.R as UiR
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.ConnectionProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onOpenFiles: (String) -> Unit = {},
    onManageTunnels: () -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val connections by viewModel.connections.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val keys by viewModel.sshKeys.collectAsState()
    val tunnels by viewModel.tunnelConfigs.collectAsState()
    val statuses by viewModel.profileStatuses.collectAsState()
    val error by viewModel.error.collectAsState()
    val warning by viewModel.warning.collectAsState()
    val prefill by viewModel.prefillNewConnection.collectAsState()
    var draft by remember { mutableStateOf<ConnectionProfile?>(null) }
    var search by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf<String?>(null) }
    var forwarding by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deploy by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deleting by remember { mutableStateOf<ConnectionProfile?>(null) }
    val snack = remember { SnackbarHostState() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.announceRecentKills()
    }
    LaunchedEffect(error, warning) {
        (error ?: warning)?.let { snack.showSnackbar(it) }
        if (error != null) viewModel.dismissError()
        if (warning != null) viewModel.dismissWarning()
    }
    LaunchedEffect(prefill) {
        prefill?.let { p ->
            val type = when (p.transport) { "rdp" -> "RDP"; "vnc" -> "VNC"; else -> "SSH" }
            draft = ConnectionProfile(label = "", host = p.host, username = p.username.orEmpty(),
                connectionType = type, port = p.port ?: 22,
                rdpPort = p.port ?: 3389, vncPort = p.port ?: 5900,
                useMosh = p.transport == "mosh", useEternalTerminal = p.transport == "et")
            viewModel.consumePrefillNewConnection()
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.connections_title)) }, actions = {
            TextButton(onClick = onManageTunnels) { Text(stringResource(UiR.string.client_vpn)) }
            TextButton(onClick = { groupName = "" }) { Text(stringResource(UiR.string.client_new_group)) }
        }) },
        floatingActionButton = { FloatingActionButton(onClick = {
            draft = ConnectionProfile(label = "", host = "", username = "")
        }) { Icon(Icons.Default.Add, stringResource(UiR.string.client_new_connection)) } },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(search, { search = it }, label = { Text(stringResource(UiR.string.client_search_connections)) },
                modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true)
            LazyColumn(Modifier.weight(1f)) {
                val sections = listOf(null) + groups.map { it.id }
                sections.forEach { groupId ->
                    val group = groups.firstOrNull { it.id == groupId }
                    val profiles = connections.filter {
                        it.groupId == groupId && (search.isBlank() || "${it.label} ${it.host}".contains(search, true))
                    }
                    if (profiles.isNotEmpty() || group != null) {
                        item(key = "group-${groupId.orEmpty()}") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { group?.let { viewModel.toggleGroupCollapsed(it.id) } }) {
                                    Text(group?.label ?: stringResource(UiR.string.client_ungrouped))
                                }
                                if (group != null) TextButton(onClick = { viewModel.launchGroup(group.id) }) { Text(stringResource(UiR.string.client_connect_group)) }
                            }
                        }
                        if (group?.collapsed != true || search.isNotBlank()) items(profiles, key = { it.id }) { p ->
                            val status = statuses[p.id] ?: ProfileStatus.DISCONNECTED
                            ListItem(
                                modifier = Modifier.clickable { viewModel.connect(p, "") },
                                headlineContent = { Text(p.label.ifBlank { p.host }) },
                                supportingContent = { Column {
                                    Text("${p.connectionType} · ${p.username.let { if (it.isBlank()) "" else "$it@" }}${p.host} · ${status.name.lowercase()}")
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { draft = p }) { Text(stringResource(R.string.common_edit)) }
                                        if (status == ProfileStatus.CONNECTED) {
                                            TextButton(onClick = { viewModel.disconnect(p.id) }) { Text(stringResource(R.string.common_disconnect)) }
                                            if (p.isSsh || p.isSmb) TextButton(onClick = { onOpenFiles(p.id) }) { Text(stringResource(UiR.string.client_files)) }
                                        }
                                        if (p.isSsh) TextButton(onClick = { forwarding = p }) { Text(stringResource(UiR.string.client_forwards)) }
                                    }
                                    Row {
                                        if (p.isSsh) TextButton(onClick = { deploy = p }) { Text(stringResource(UiR.string.client_install_key)) }
                                        TextButton(onClick = { viewModel.duplicateConnection(p.id) }) { Text(stringResource(R.string.connections_menu_duplicate)) }
                                        TextButton(onClick = { deleting = p }) { Text(stringResource(R.string.common_delete)) }
                                    }
                                } },
                            )
                            HorizontalDivider()
                        }
                    }
                }
                if (connections.isEmpty()) item { Text(stringResource(UiR.string.client_add_an_ssh_rdp_vnc_or_smb_connection_to_get_started), Modifier.padding(24.dp)) }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
    draft?.let { profile ->
        ConnectionEditDialog(profile, connections, keys, groups.map { it.id to it.label },
            tunnels.filter { it.type != "CLOUDFLARE_ACCESS" }.map { it.id to it.label },
            onDismiss = { draft = null }, onSave = { viewModel.saveConnection(it); draft = null })
    }
    groupName?.let { name -> AlertDialog(onDismissRequest = { groupName = null }, title = { Text(stringResource(UiR.string.client_new_group)) },
        text = { OutlinedTextField(name, { groupName = it }, label = { Text(stringResource(UiR.string.client_name)) }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) viewModel.createGroup(name); groupName = null }) { Text(stringResource(R.string.common_create)) } },
        dismissButton = { TextButton(onClick = { groupName = null }) { Text(stringResource(R.string.common_cancel)) } }) }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(UiR.string.client_delete_named, profile.label)) },
        confirmButton = { TextButton(onClick = { viewModel.deleteConnection(profile.id); deleting = null }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } }) }
    forwarding?.let { profile ->
        val rules by remember(profile.id) { viewModel.portForwardRules(profile.id) }.collectAsState()
        val sessions by viewModel.sessions.collectAsState()
        PortForwardDialog(profile.label, profile.id, rules,
            sessions.values.filter { it.profileId == profile.id }.flatMap { it.activeForwards },
            onSave = viewModel::savePortForwardRule,
            onDelete = { viewModel.deletePortForwardRule(it, profile.id) }, onDismiss = { forwarding = null })
    }
    deploy?.let { p -> DeployKeyDialog(p, keys, onDismiss = { deploy = null },
        onDeploy = { key, password -> viewModel.deployKey(p, key, password); deploy = null }) }
}
