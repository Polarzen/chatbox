package dev.polarzen.chatbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private enum class ConnectionEdit { AddService, RenameService, RemoveService, AddKey, RenameKey, RemoveKey }

@Composable
internal fun AndroidConnectionSettings(connections: AndroidConnections, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(connections.base.ifEmpty { connections.services.firstOrNull()?.base.orEmpty() }) }
    val service = connections.services.find { it.base == address }
    var edit by remember { mutableStateOf<ConnectionEdit?>(null) }
    var editingKey by remember { mutableStateOf<AndroidNamedKey?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    fun editKey(action: ConnectionEdit, key: AndroidNamedKey) {
        editingKey = key
        edit = action
    }
    fun openHistory(base: String) {
        if (connections.openHistory(base)) onClose() else error = "无法打开此本机档案。"
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("连接设置", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("完成") }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务档案", style = MaterialTheme.typography.titleMedium)
                    Box {
                        OutlinedButton({ selecting = true }, enabled = pending == null && connections.services.isNotEmpty()) {
                            Text(service?.name ?: "选择服务")
                        }
                        DropdownMenu(selecting, { selecting = false }) {
                            connections.services.forEach { item ->
                                DropdownMenuItem(text = { Column {
                                    Text(item.name)
                                    Text(item.base, style = MaterialTheme.typography.bodySmall)
                                } }, onClick = { address = item.base; selecting = false; error = "" })
                            }
                        }
                    }
                    TextButton({ edit = ConnectionEdit.AddService }, enabled = pending == null) { Text("新增 HTTPS 服务") }
                    if (service != null) {
                        Text(service.base, style = MaterialTheme.typography.bodyMedium)
                        if (connections.base == address) Text("当前档案 · ${connections.status}")
                        ConnectionActions("管理服务", pending == null, {
                            edit = ConnectionEdit.RenameService
                        }, { edit = ConnectionEdit.RemoveService })
                        HorizontalDivider()
                        Text("命名密钥", style = MaterialTheme.typography.titleMedium)
                        service.keys.forEach { key ->
                            val active = connections.base == address && connections.keyId == key.id
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(key.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                ConnectionActions("管理密钥", pending == null,
                                    { editKey(ConnectionEdit.RenameKey, key) }, { editKey(ConnectionEdit.RemoveKey, key) })
                            }
                            if (active) {
                                Text(if (connections.token() == null) connections.status else "活动密钥 · 上次验证可读取当前身份的会话列表",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = {
                                pending = key.id
                                error = ""
                                scope.launch {
                                    try { connections.verify(service.base, key.id) }
                                    catch (e: Exception) { error = connectionFailure(e) }
                                    finally { pending = null }
                                }
                            }, enabled = pending == null) {
                                Text(if (pending == key.id) "正在验证…" else if (active) "重新验证" else "验证并切换")
                            }
                        }
                        TextButton({ edit = ConnectionEdit.AddKey }, enabled = pending == null) { Text("新增命名密钥") }
                        TextButton({ openHistory(address) }, enabled = pending == null) { Text("离线查看此档案的会话") }
                    }
                    HorizontalDivider()
                    TextButton({ openHistory("") }, enabled = pending == null) { Text("查看本机离线会话") }
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    edit?.let { action ->
        val key = editingKey
        val addingService = action == ConnectionEdit.AddService
        val addingKey = action == ConnectionEdit.AddKey
        val removing = action == ConnectionEdit.RemoveKey || action == ConnectionEdit.RemoveService
        var name by remember(action, key) { mutableStateOf(when (action) {
            ConnectionEdit.RenameService -> service?.name.orEmpty()
            ConnectionEdit.RenameKey -> key?.name.orEmpty()
            else -> ""
        }) }
        // Never put the secret in a saved-instance Bundle.
        var value by remember(action) { mutableStateOf("") }
        var problem by remember(action) { mutableStateOf("") }
        val title = when (action) {
            ConnectionEdit.AddService -> "新增服务档案"
            ConnectionEdit.RenameService -> "服务改名"
            ConnectionEdit.RemoveService -> "移除服务档案？"
            ConnectionEdit.AddKey -> "新增命名密钥"
            ConnectionEdit.RenameKey -> "密钥改名"
            ConnectionEdit.RemoveKey -> "移除密钥？"
        }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (removing) {
                    Text(if (action == ConnectionEdit.RemoveKey) "从本机移除「${key?.name.orEmpty()}」。"
                    else "从本机移除「${service?.name.orEmpty()}」和它的密钥。重新添加同一地址后仍可查看本机会话。")
                } else {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("本机名称") })
                    if (addingService) OutlinedTextField(value, { value = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), label = { Text("HTTPS 地址") })
                    if (addingKey) OutlinedTextField(value, { value = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), label = { Text("聊天 API 密钥") })
                }
                if (problem.isNotEmpty()) Text(problem, color = MaterialTheme.colorScheme.error)
            }
        }, dismissButton = { TextButton({ edit = null }) { Text("取消") } }, confirmButton = {
            TextButton(onClick = {
                try {
                    when (action) {
                        ConnectionEdit.AddService -> address = connections.addService(name, value)
                        ConnectionEdit.RenameService -> connections.renameService(address, name)
                        ConnectionEdit.RemoveService -> {
                            connections.removeService(address)
                            address = connections.services.firstOrNull()?.base.orEmpty()
                        }
                        ConnectionEdit.AddKey -> connections.addKey(address, name, value)
                        ConnectionEdit.RenameKey -> connections.renameKey(address, checkNotNull(key).id, name)
                        ConnectionEdit.RemoveKey -> connections.removeKey(address, checkNotNull(key).id)
                    }
                    edit = null
                    error = ""
                } catch (e: Exception) { problem = connectionFailure(e) }
            }, enabled = removing || name.isNotBlank() && (!(addingService || addingKey) || value.isNotBlank())) {
                Text(if (removing) "移除" else "保存")
            }
        })
    }
}

@Composable
private fun ConnectionActions(label: String, enabled: Boolean, rename: () -> Unit, remove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, enabled = enabled) { Text(label) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("改名") }, onClick = { open = false; rename() })
            DropdownMenuItem(text = { Text("移除") }, onClick = { open = false; remove() })
        }
    }
}
