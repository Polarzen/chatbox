package dev.polarzen.chatbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
private fun DeviceForm(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) TextButton(onClick = onBack) { Text("返回") }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@Composable
internal fun AndroidDeviceWelcome(connect: () -> Unit, newLocal: () -> Unit) {
    DeviceForm("欢迎使用 Chatbox", null) {
        Text("连接自己的电脑，即可在两台设备上接着聊。也可以先在手机上使用，稍后再连接。")
        Button(onClick = connect) { Text("连接我的电脑") }
        OutlinedButton(onClick = newLocal) { Text("新建本机会话") }
    }
}

@Composable
internal fun AndroidDevicePairing(device: AndroidDeviceSync, scope: CoroutineScope,
                                 onBack: () -> Unit, onConnected: () -> Unit) {
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var scannerNote by remember { mutableStateOf("") }
    val formScope = rememberCoroutineScope()
    val transport = remember { SharedDeviceTransport(false) }
    var checking by remember { mutableStateOf(false) }
    fun checkPreview(endpoint: String, oneTimeCode: String) {
        if (checking || device.syncing) return
        address = endpoint
        scannerNote = ""
        checking = true
        formScope.launch {
            try {
                val instance = transport.health(endpoint)
                device.preview(endpoint, oneTimeCode)
                if (device.preview?.instance?.let { it != instance } == true) {
                    device.cancelPreview()
                    scannerNote = "入口返回了不同的共享服务身份。请在电脑上重新打开配对。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                scannerNote = "手机未能通过共享入口健康检查。请在电脑设备设置中检查共享服务，再核对手机地址。同一局域网请检查 Wi-Fi。通过 ngrok 连接时请检查账号授权和隧道状态。"
            } finally { checking = false }
        }
    }
    val currentConnected by rememberUpdatedState(onConnected)
    LaunchedEffect(device.bound, device.online) {
        if (device.bound && device.online) currentConnected()
    }
    DeviceForm("连接我的电脑", onBack) {
        val preview = device.preview
        when {
            device.pendingJoin || device.bound -> {
                Text("正在核对上次确认的设备连接。请保持电脑共享服务在线。")
                OutlinedButton(enabled = !device.syncing, onClick = { scope.launch { device.refresh() } }) {
                    Text("重新核对连接")
                }
            }
            preview != null -> {
                Text("确认共享范围", style = MaterialTheme.typography.titleLarge)
                Text("电脑入口 $address")
                Text("电脑标识 ${preview.instance}", style = MaterialTheme.typography.bodySmall)
                Text("电脑预计有 ${preview.pcProfiles} 个档案、${preview.pcSessions} 段会话。手机预计有 ${device.phoneProfiles} 个档案、${device.phoneSessions} 段会话。")
                Text("确认后，两台设备会共享所有现有档案中的聊天，以及今后新增的档案、会话与消息。未发送的草稿、登录凭据和附件原件留在原设备。")
                Button(enabled = !device.syncing, onClick = { scope.launch { device.join() } }) {
                    Text("确认范围并连接")
                }
                OutlinedButton(enabled = !device.syncing, onClick = device::cancelPreview) { Text("取消本次配对") }
            }
            else -> {
                Text("在 Windows 的设备设置中打开手机配对，扫描电脑显示的二维码。")
                if (!checking && !device.syncing) ScanDevicePairButton(onScanned = { pair ->
                    checkPreview(pair.address, pair.code)
                }, onError = { scannerNote = it })
                TextButton(enabled = !device.syncing, onClick = { manual = !manual }) {
                    Text(if (manual) "收起手动输入" else "手动输入地址和配对码")
                }
                if (manual) {
                    OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
                        enabled = !device.syncing, label = { Text("电脑提供的手机连接地址") })
                    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), singleLine = true,
                        enabled = !device.syncing, label = { Text("一次性配对码") })
                    Button(enabled = address.isNotBlank() && code.isNotBlank() && !checking && !device.syncing, onClick = {
                        checkPreview(address.trim(), code.trim())
                    }) { Text("查看共享范围") }
                }
            }
        }
        if (checking || device.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (device.notice.isNotBlank()) Text(device.notice)
        if (scannerNote.isNotBlank()) Text(scannerNote)
    }
}

@Composable
internal fun AndroidDeviceSettings(device: AndroidDeviceSync, scope: CoroutineScope,
                                  connect: () -> Unit, localConnections: () -> Unit,
                                  appearance: () -> Unit, onBack: () -> Unit) {
    var updateAddress by rememberSaveable { mutableStateOf(false) }
    var disconnect by rememberSaveable { mutableStateOf(false) }
    var candidate by rememberSaveable { mutableStateOf("") }
    DeviceForm("设备设置", onBack) {
        if (device.bound || device.pendingJoin) {
            Text(when {
                device.pendingJoin -> "等待确认连接"
                device.online -> "已连接到电脑"
                else -> "电脑离线 · 可阅读已缓存的会话"
            }, style = MaterialTheme.typography.titleLarge)
            Text(device.address)
            Text("共享所有现有和未来档案中的聊天。手机回到前台后会自动补齐记录。")
            OutlinedButton(enabled = !device.syncing, onClick = { scope.launch { device.refresh() } }) { Text("检查连接") }
            OutlinedButton(enabled = !device.syncing, onClick = {
                candidate = device.address
                updateAddress = true
            }) { Text("更新连接地址") }
            TextButton(enabled = !device.syncing, onClick = { disconnect = true }) { Text("断开设备并清除缓存") }
            Text("要吊销手机的服务访问权限，请在电脑的设备设置中撤销授权。")
        } else {
            Button(onClick = connect) { Text("连接我的电脑") }
            if (device.notice.isNotBlank()) TextButton(onClick = { disconnect = true }) { Text("清理本机设备配对资料") }
        }
        if (device.notice.isNotBlank()) Text(device.notice)
        HorizontalDivider()
        Text("本机设置", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = localConnections) { Text("本机服务档案与密钥") }
        TextButton(onClick = appearance) { Text("外观与通知") }
    }
    if (updateAddress) AlertDialog(onDismissRequest = { updateAddress = false },
        title = { Text("更新连接地址") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输入电脑设备设置中显示的新地址。手机会先核对原设备身份，再恢复同步。")
                OutlinedTextField(candidate, { candidate = it }, singleLine = true,
                    label = { Text("新的手机连接地址") })
            }
        }, confirmButton = {
            TextButton(enabled = candidate.isNotBlank(), onClick = {
                updateAddress = false
                scope.launch { device.changeAddress(candidate.trim()) }
            }) { Text("验证并更新") }
        }, dismissButton = { TextButton(onClick = { updateAddress = false }) { Text("取消") } })
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false },
        title = { Text("断开与电脑的连接？") },
        text = { Text("将移除手机保存的设备凭据和远端会话缓存。请先核对待发送消息并保存远端会话里的草稿。手机原有历史和电脑上的记录会保留。") },
        confirmButton = { TextButton(onClick = {
            disconnect = false
            scope.launch { device.disconnect() }
        }) { Text("断开并清除") } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text("取消") } })
}
