package sh.haven.feature.connections

import sh.haven.core.ui.R as UiR
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey

@Composable
fun ConnectionEditDialog(
    profile: ConnectionProfile,
    profiles: List<ConnectionProfile>,
    keys: List<SshKey>,
    groups: List<Pair<String, String>>,
    tunnels: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfile) -> Unit,
) {
    var p by remember(profile.id) { mutableStateOf(profile) }
    var port by remember(profile.id) { mutableStateOf(when { p.isRdp -> p.rdpPort; p.isVnc -> p.vncPort ?: 5900; else -> p.port }.toString()) }
    val password = when { p.isRdp -> p.rdpPassword; p.isVnc -> p.vncPassword; p.isSmb -> p.smbPassword; else -> p.sshPassword }.orEmpty()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (profile.label.isBlank()) UiR.string.client_new_connection else UiR.string.client_edit_connection)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ClientChoice(stringResource(UiR.string.client_protocol), p.connectionType, listOf("SSH", "RDP", "VNC", "SMB").map { it to it }) { type ->
                p = p.copy(connectionType = type!!)
                port = when (type) { "RDP" -> "3389"; "VNC" -> "5900"; "SMB" -> "445"; else -> "22" }
            }
            OutlinedTextField(p.label, { p = p.copy(label = it) }, label = { Text(stringResource(UiR.string.client_name)) }, singleLine = true)
            OutlinedTextField(p.host, { p = p.copy(host = it.trim()) }, label = { Text(stringResource(R.string.common_host)) }, singleLine = true)
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.common_port)) }, singleLine = true)
            OutlinedTextField(when { p.isRdp -> p.rdpUsername.orEmpty(); p.isVnc -> p.vncUsername.orEmpty(); else -> p.username }, {
                p = p.copy(username = it, rdpUsername = it, vncUsername = it)
            }, label = { Text(stringResource(R.string.common_username)) }, singleLine = true)
            OutlinedTextField(password, { p = p.copy(sshPassword = it, smbPassword = it, rdpPassword = it, vncPassword = it) },
                label = { Text(stringResource(UiR.string.client_saved_password_optional)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            ClientChoice(stringResource(R.string.connections_field_group), p.groupId, listOf(null to stringResource(UiR.string.client_ungrouped)) + groups) { p = p.copy(groupId = it) }
            ClientChoice(stringResource(UiR.string.client_vpn), p.tunnelConfigId, listOf(null to stringResource(UiR.string.client_none)) + tunnels) { p = p.copy(tunnelConfigId = it) }
            if (p.isSsh) {
                ClientChoice(stringResource(UiR.string.client_terminal_transport), when { p.useMosh -> "MOSH"; p.useEternalTerminal -> "ET"; else -> "SSH" },
                    listOf("SSH" to "SSH", "MOSH" to "Mosh", "ET" to "Eternal Terminal")) {
                    p = p.copy(useMosh = it == "MOSH", useEternalTerminal = it == "ET")
                }
                ClientAuthenticationEditor(p, keys) { p = it }
                ClientChoice(stringResource(UiR.string.client_session_manager), p.sessionManager, listOf(null to stringResource(UiR.string.client_default), "NONE" to stringResource(UiR.string.client_none), "TMUX" to "tmux", "SCREEN" to "screen", "ZELLIJ" to "Zellij")) { p = p.copy(sessionManager = it) }
                ClientChoice(stringResource(UiR.string.client_file_transfer), p.fileTransport, listOf("AUTO" to stringResource(UiR.string.client_automatic), "SFTP" to "SFTP", "SCP" to "SCP")) { p = p.copy(fileTransport = it!!) }
                ClientChoice(stringResource(UiR.string.client_jump_host), p.jumpProfileId, listOf(null to stringResource(UiR.string.client_none)) + profiles.filter { it.isSsh && it.id != p.id }.map { it.id to it.label }) { p = p.copy(jumpProfileId = it) }
                ClientToggle(stringResource(UiR.string.client_reconnect_automatically), p.autoReconnect) { p = p.copy(autoReconnect = it) }
                ClientToggle(stringResource(UiR.string.client_tunnel_only), p.tunnelOnly) { p = p.copy(tunnelOnly = it) }
                OutlinedTextField(p.sshOptions.orEmpty(), { p = p.copy(sshOptions = it) }, label = { Text(stringResource(UiR.string.client_ssh_options)) }, minLines = 2)
            }
            if (p.isSmb) {
                OutlinedTextField(p.smbShare.orEmpty(), { p = p.copy(smbShare = it) }, label = { Text(stringResource(UiR.string.client_share)) })
                OutlinedTextField(p.smbDomain.orEmpty(), { p = p.copy(smbDomain = it) }, label = { Text(stringResource(UiR.string.client_domain)) })
            }
            if (p.isRdp) {
                OutlinedTextField(p.rdpDomain.orEmpty(), { p = p.copy(rdpDomain = it) }, label = { Text(stringResource(UiR.string.client_domain)) })
                ClientToggle(stringResource(UiR.string.client_network_level_authentication), p.rdpUseNla) { p = p.copy(rdpUseNla = it) }
            }
            if (p.isRdp || p.isVnc) {
                val forward = if (p.isRdp) p.rdpSshForward else p.vncSshForward
                ClientToggle(stringResource(UiR.string.client_connect_through_ssh), forward) { p = p.copy(rdpSshForward = it, vncSshForward = it) }
                if (forward) ClientChoice(stringResource(R.string.connections_field_ssh_connection), if (p.isRdp) p.rdpSshProfileId else p.vncSshProfileId,
                    listOf(null to stringResource(UiR.string.client_select_connection)) + profiles.filter { it.isSsh }.map { it.id to it.label }) {
                    p = p.copy(rdpSshProfileId = it, vncSshProfileId = it)
                }
            }
            ClientChoice(stringResource(UiR.string.client_proxy), p.proxyType, listOf(null to stringResource(UiR.string.client_none), "SOCKS5" to "SOCKS5", "SOCKS4" to "SOCKS4", "HTTP" to "HTTP")) { p = p.copy(proxyType = it) }
            if (p.proxyType != null) {
                OutlinedTextField(p.proxyHost.orEmpty(), { p = p.copy(proxyHost = it) }, label = { Text(stringResource(UiR.string.client_proxy_host)) })
                OutlinedTextField(p.proxyPort.toString(), { p = p.copy(proxyPort = it.toIntOrNull() ?: 1080) }, label = { Text(stringResource(UiR.string.client_proxy_port)) })
                OutlinedTextField(p.proxyUser.orEmpty(), { p = p.copy(proxyUser = it) }, label = { Text(stringResource(UiR.string.client_proxy_username)) })
                OutlinedTextField(p.proxyPassword.orEmpty(), { p = p.copy(proxyPassword = it) }, label = { Text(stringResource(UiR.string.client_proxy_password)) }, visualTransformation = PasswordVisualTransformation())
            }
        } },
        confirmButton = { TextButton(enabled = p.host.isNotBlank() && port.toIntOrNull() in 1..65535, onClick = {
            val n = port.toInt()
            onSave(p.copy(label = p.label.ifBlank { p.host }, port = n, rdpPort = n, vncPort = n,
                portKnockSequence = null, spaKey = null, spaHmacKey = null, spaAccessSpec = null, usbForwardVidPid = null,
                authMethods = p.authMethods.lineSequence().filterNot { it.startsWith("TOTP") || it == "PROTON_SRP" }.joinToString("\n")))
        }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun ClientToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp))
        Switch(value, onCheckedChange = onChange)
    }
}

@Composable
private fun ClientChoice(label: String, value: String?, options: List<Pair<String?, String>>, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull { it.first == value }?.second ?: value.orEmpty()}")
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onChange(id); open = false }) }
        }
    }
}

@Composable
private fun ClientAuthenticationEditor(
    profile: ConnectionProfile,
    keys: List<SshKey>,
    onChange: (ConnectionProfile) -> Unit,
) {
    val methods = profile.authMethodSpecs.filter {
        it !is ConnectionProfile.AuthMethodSpec.Totp && it != ConnectionProfile.AuthMethodSpec.ProtonSrp
    }
    fun kind(method: ConnectionProfile.AuthMethodSpec): String = when (method) {
        is ConnectionProfile.AuthMethodSpec.Key -> "KEY"
        ConnectionProfile.AuthMethodSpec.AnyHardwareKey -> "FIDO"
        ConnectionProfile.AuthMethodSpec.KeyboardInteractive -> "INTERACTIVE"
        else -> "PASSWORD"
    }
    fun method(type: String) = when (type) {
        "KEY" -> ConnectionProfile.AuthMethodSpec.Key(profile.keyId)
        "FIDO" -> ConnectionProfile.AuthMethodSpec.AnyHardwareKey
        "INTERACTIVE" -> ConnectionProfile.AuthMethodSpec.KeyboardInteractive
        else -> ConnectionProfile.AuthMethodSpec.Password
    }
    fun save(next: List<ConnectionProfile.AuthMethodSpec>, passwordOnly: Boolean = false) {
        onChange(profile.copy(
            authMethods = ConnectionProfile.AuthMethodSpec.serializeList(next),
            ignoreSavedKeys = passwordOnly,
            authType = if (passwordOnly) ConnectionProfile.AuthType.PASSWORD else ConnectionProfile.AuthType.KEY,
        ))
    }
    val types = listOf(
        "KEY" to stringResource(UiR.string.client_auth_saved_key),
        "PASSWORD" to stringResource(UiR.string.client_auth_password),
        "FIDO" to stringResource(UiR.string.client_auth_fido),
        "INTERACTIVE" to stringResource(UiR.string.client_auth_interactive),
    )
    val mode = when {
        profile.ignoreSavedKeys -> "PASSWORD"
        profile.authMethods.isBlank() || methods.isEmpty() -> "AUTO"
        methods.size > 1 -> "CUSTOM"
        else -> kind(methods.single())
    }
    ClientChoice(stringResource(UiR.string.client_authentication), mode,
        listOf("AUTO" to stringResource(UiR.string.client_automatic)) + types +
            listOf("CUSTOM" to stringResource(UiR.string.client_auth_multiple))) { type ->
        when (type) {
            "AUTO" -> save(emptyList())
            "CUSTOM" -> save(if (methods.size > 1) methods else listOf(method("KEY"), method("PASSWORD")))
            else -> save(listOf(method(type!!)), passwordOnly = type == "PASSWORD")
        }
    }
    val keyOptions = listOf(null to stringResource(UiR.string.client_any_software_key)) + keys.map { it.id to it.label }
    if (mode == "KEY" || mode == "AUTO") {
        ClientChoice(stringResource(R.string.connections_deploy_field_key), profile.keyId, keyOptions) { id ->
            onChange(profile.copy(keyId = id, authType = ConnectionProfile.AuthType.KEY, authMethods = if (mode == "AUTO") "" else ConnectionProfile.AuthMethodSpec.Key(id).serialize()))
        }
    }
    if (mode == "CUSTOM") {
        methods.forEachIndexed { index, spec ->
            ClientChoice(stringResource(UiR.string.client_auth_method_number, index + 1), kind(spec), types) { type ->
                save(methods.toMutableList().apply { this[index] = method(type!!) })
            }
            if (spec is ConnectionProfile.AuthMethodSpec.Key) {
                ClientChoice(stringResource(R.string.connections_deploy_field_key), spec.keyId, keyOptions) { id ->
                    save(methods.toMutableList().apply { this[index] = ConnectionProfile.AuthMethodSpec.Key(id) })
                }
            }
            Row {
                TextButton(enabled = index > 0, onClick = {
                    save(methods.toMutableList().apply { add(index - 1, removeAt(index)) })
                }) { Text(stringResource(UiR.string.client_move_up)) }
                TextButton(enabled = index < methods.lastIndex, onClick = {
                    save(methods.toMutableList().apply { add(index + 1, removeAt(index)) })
                }) { Text(stringResource(UiR.string.client_move_down)) }
                TextButton(onClick = { save(methods.filterIndexed { i, _ -> i != index }) }) { Text(stringResource(R.string.common_delete)) }
            }
        }
        TextButton(onClick = { save(methods + ConnectionProfile.AuthMethodSpec.Password) }) { Text(stringResource(UiR.string.client_add_auth_method)) }
    }
}
