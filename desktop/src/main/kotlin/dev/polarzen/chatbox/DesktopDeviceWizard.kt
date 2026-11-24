package dev.polarzen.chatbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private enum class DeviceStep { SOURCE, ENDPOINT, PAIR }

private data class HostStatus(val local: String, val gateway: String, val phone: String, val mode: String, val error: String)
private suspend fun checkedHostStatus(): HostStatus = withContext(Dispatchers.IO) {
    val status = DesktopDeviceRuntime.call(JSONObject().put("action", "status"))
    val error = if (status.optBoolean("ok")) "" else status.optString("error", "service_offline")
    val local = if (error.startsWith("gateway_")) DesktopDeviceRuntime.call(JSONObject().put("action", "local")) else status
    HostStatus(local.optString("localUrl"), local.optString("gatewayUrl"), local.optString("phoneUrl"), local.optString("mode"), error)
}

private fun hostGuidance(code: String): String = when (code) {
    "ngrok_auth_required" -> "ngrok 需要账号授权。请先登录自己的 ngrok 账号，或在连接方式中输入该账号的 authtoken。"
    "ngrok_missing" -> "找不到 ngrok。请在连接方式中填写已安装的 ngrok.exe 完整路径。"
    "ngrok_target_mismatch", "ngrok_target_ambiguous", "ngrok_inspection_enabled" ->
        "已有隧道未能通过网关检查。请将所选域名转发到本机受限网关，并关闭请求检查。"
    "gateway_unreachable", "gateway_identity_mismatch" ->
        "手机入口检查失败。请核对域名、受信任的 HTTPS 证书和反向代理目标，再检查入口。"
    "astrbot_identity_mismatch", "database_upstream_mismatch" ->
        "AstrBot 身份与所选部署不一致。请检查 Launcher 实例和原共享数据库。"
    "gateway_port_in_use" -> "本机网关端口被占用。请停止占用该端口的程序，或更换网关端口。"
    "db_in_use" -> "原共享数据库正在使用。请先正常停止使用它的旧共享服务，再重试。"
    "service_offline", "not_configured" -> "共享服务尚未运行。请启动服务，或重新配置连接方式。"
    "python_missing" -> "未找到 Python。请安装 Python 3.10 或以上版本，或启动已安装的 AstrBot Launcher 实例。"
    else -> "检查未完成（$code）。请检查共享服务和网络，然后重试。"
}

@Composable
internal fun DesktopDeviceWizard(store: DesktopStore, connection: DesktopConnection, sync: DesktopDeviceSync, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (sync.enabled) DeviceStep.PAIR else DeviceStep.SOURCE) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var host by remember { mutableStateOf<HostStatus?>(null) }
    var mode by remember { mutableStateOf("ngrok") }
    var phone by remember { mutableStateOf(sync.phoneUrl) }
    var token by remember { mutableStateOf("") }
    var program by remember { mutableStateOf("") }
    var database by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(DesktopDeviceRuntime.configuredGatewayPort().toString()) }
    var advanced by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(sync.enabled) }
    var login by remember { mutableStateOf(if (sync.enabled) sync.autoStart else true) }
    var pair by remember { mutableStateOf<DevicePairCode?>(null) }
    var expiresAt by remember { mutableLongStateOf(0L) }
    var baseline by remember { mutableStateOf(emptySet<String>()) }
    var linking by remember { mutableStateOf<String?>(null) }
    var pendingRevoke by remember { mutableStateOf<ProfileDevice?>(null) }
    val currentDevices = sync.devices
    var setupSources by remember { mutableStateOf<DesktopDiscovery?>(null) }
    LaunchedEffect(connection.instances) {
        setupSources = withContext(Dispatchers.IO) { discoverDesktopSources(connection.instances) }
    }

    LaunchedEffect(step, setupSources?.database, connection.profileId) {
        if (!sync.enabled && step == DeviceStep.ENDPOINT && database.isEmpty()) {
            val discovery = setupSources
            val deployed = discovery?.deployment
            if (deployed != null && (deployed.id == connection.profileId ||
                    !deployed.id.startsWith("launcher:") && deployed.address == connection.address)) {
                database = discovery.database.orEmpty()
            }
        }
    }

    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "操作未完成，请检查共享服务、网络和本机数据目录。" }
            finally { busy = false }
        }
    }
    suspend fun checkHost() {
        val state = checkedHostStatus()
        host = state
        if (state.error.isNotEmpty()) {
            pair = null
            message = hostGuidance(state.error)
        }
    }
    suspend fun invitePhone() {
        pair = null
        baseline = sync.devices.map { it.id }.toSet()
        sync.createInvite()
        val issued = sync.invite
        if (issued != null) {
            pair = DevicePairCode(sync.phoneUrl, issued.code)
            expiresAt = System.nanoTime() + issued.expiresIn * 1_000_000_000L
            message = "等待手机扫码并确认共享范围。"
        } else message = sync.notice
    }
    LaunchedEffect(Unit) {
        if (sync.enabled) {
            checkHost()
            host?.let { state ->
                if (state.mode.isNotBlank()) mode = state.mode
                if (state.phone.isNotBlank()) phone = state.phone
            }
        }
    }
    LaunchedEffect(pair, expiresAt) {
        if (pair != null) {
            delay(((expiresAt - System.nanoTime()) / 1_000_000L).coerceAtLeast(0))
            pair = null
            message = "配对码已过期。可以重新生成。"
        }
    }
    LaunchedEffect(currentDevices, pair) {
        if (pair != null && currentDevices.any { it.id !in baseline }) {
            pair = null
            message = "手机已完成配对。可在两端的最近对话中继续聊天。"
        }
    }
    val profiles = store.profiles.filter { !it.offlineOnly && it.kind != "shared" && it.kind != "device" }
    val estimate = remember(profiles, sync.revision) { profiles.sumOf { p -> store.sessions(p.id).count { it.entryCount > 0 } } }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(when (step) {
            DeviceStep.SOURCE -> "找到 AstrBot"
            DeviceStep.ENDPOINT -> "选择手机连接方式"
            DeviceStep.PAIR -> "连接我的手机"
        }) },
        text = {
            Column(Modifier.widthIn(min = 280.dp, max = 510.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (step) {
                    DeviceStep.SOURCE -> DesktopDeviceSourcePicker(connection, setupSources, !busy)
                    DeviceStep.ENDPOINT -> {
                        Text("AstrBot · " + connection.address)
                        Choice("跨网络 · ngrok", mode == "ngrok", !busy) { mode = "ngrok" }
                        Choice("局域网或已有可信 HTTPS 域名", mode == "trusted", !busy) { mode = "trusted" }
                        Text(if (mode == "ngrok") "优先使用账号已有稳定域名。留空时尝试复用现有隧道，或使用账号可用的地址。"
                            else "将手机可访问的 HTTPS 域名转发到本机受限网关 http://127.0.0.1:$port。", style = MaterialTheme.typography.caption)
                        OutlinedTextField(phone, { phone = it }, label = { Text(if (mode == "ngrok") "已有 ngrok HTTPS 域名（可选）" else "手机 HTTPS 地址") },
                            enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = !busy, onClick = { advanced = !advanced }) { Text(if (advanced) "收起部署配置" else "部署配置与 ngrok 授权") }
                        if (advanced) {
                            if (mode == "ngrok") {
                                OutlinedTextField(program, { program = it }, label = { Text("ngrok.exe 完整路径（可选）") }, enabled = !busy, singleLine = true)
                                OutlinedTextField(token, { token = it }, label = { Text("ngrok authtoken（已授权可留空）") },
                                    enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation())
                            }
                            OutlinedTextField(port, { port = it }, label = { Text("本机受限网关端口") }, enabled = !busy, singleLine = true)
                            OutlinedTextField(database, { database = it }, label = { Text("原共享 SQLite 数据库路径（可选）") }, enabled = !busy, singleLine = true)
                            setupSources?.database?.let { path -> TextButton(onClick = { database = path }, enabled = !busy) { Text("使用找到的旧部署数据库") } }
                        }
                        if (database.isNotBlank()) Text("将沿用共享数据库 · " + database, style = MaterialTheme.typography.caption)
                        Divider()
                        Text("电脑预计导入 ${profiles.size} 个档案、$estimate 段已有对话。手机会在确认时显示其本地数量。")
                        Text("两台设备将共享所有现有及以后新增的档案、会话。空会话和草稿留在本机，附件原件留在发送设备。未确定来源的离线历史待验证后纳入。")
                        if (!sync.enabled) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(accepted, onCheckedChange = { accepted = it }, enabled = !busy)
                            Text("允许这两台设备共享上述范围")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(login, onCheckedChange = { login = it }, enabled = !busy)
                            Text("Windows 登录后自动恢复共享服务")
                        }
                    }
                    DeviceStep.PAIR -> {
                        Text("当前 AstrBot 档案 · " + connection.address.ifBlank { "未选中" })
                        host?.let { state ->
                            Text("本机共享服务 · " + state.local.ifBlank { "尚未验证" })
                            Text("受限网关 · " + state.gateway.ifBlank { "尚未验证" })
                            SelectionContainer { Text("手机 HTTPS 地址 · " + state.phone.ifBlank { "尚未验证" }) }
                            if (state.error.isBlank()) Text("入口已通过电脑的 HTTPS 检查。")
                            else Text(hostGuidance(state.error), color = MaterialTheme.colors.error)
                        } ?: Text("正在检查共享服务和手机入口…")
                        pair?.let { value ->
                            DesktopPairQr(value)
                            SelectionContainer { Text("一次性短码 · " + value.code) }
                            Text("在手机打开「连接我的电脑」扫码，或手填上面的 HTTPS 地址和短码。")
                        }
                        if (currentDevices.isEmpty()) Text("尚无已配对手机。")
                        else {
                            Text("已配对设备", style = MaterialTheme.typography.subtitle1)
                            currentDevices.forEach { device ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(device.name, Modifier.weight(1f))
                                    TextButton(enabled = !busy, onClick = { pendingRevoke = device }) { Text("撤销") }
                                }
                            }
                        }
                        Divider()
                        if (sync.unmatchedProfiles.isNotEmpty()) {
                            Text("待关联的手机来源", style = MaterialTheme.typography.subtitle1)
                            sync.unmatchedProfiles.forEach { source ->
                                Text(source.name + " · " + source.username)
                                SelectionContainer { Text(source.base, style = MaterialTheme.typography.caption) }
                                TextButton(enabled = !busy && sync.upstreamCandidates.isNotEmpty(),
                                    onClick = { linking = source.profile }) { Text("选择对应的电脑档案") }
                            }
                            if (sync.upstreamCandidates.isEmpty()) Text("请先在电脑连接设置中验证对应的 AstrBot 档案。")
                            Divider()
                        }
                        Text("收起或退出聊天窗口后，共享服务可继续运行。电脑须开机联网，AstrBot 和手机入口可用时才能生成新回复。")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(sync.autoStart, enabled = !busy, onCheckedChange = { value -> runAction { sync.setAutostart(value); message = sync.notice } })
                            Text("Windows 登录后自动恢复服务")
                        }
                        Text("停止共享会关闭服务和登录启动。已配对的身份保留，新输入的文字会在本机排队，恢复服务后同步。",
                            style = MaterialTheme.typography.caption)
                        Row {
                            TextButton(enabled = !busy, onClick = { runAction { sync.startHost(); checkHost() } }) { Text("启动服务") }
                            TextButton(enabled = !busy, onClick = { runAction { sync.stopHost(); pair = null; checkHost() } }) { Text("停止共享") }
                            TextButton(enabled = !busy, onClick = { runAction { checkHost() } }) { Text("检查入口") }
                        }
                        TextButton(enabled = !busy, onClick = { pair = null; message = ""; step = DeviceStep.SOURCE }) { Text("更改 AstrBot 或手机入口") }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.body2)
            }
        },
        confirmButton = {
            when (step) {
                DeviceStep.SOURCE -> Button(onClick = { step = DeviceStep.ENDPOINT; message = "" },
                    enabled = connection.connected && !connection.verifying && !busy) { Text("下一步") }
                DeviceStep.ENDPOINT -> Button(enabled = !busy && accepted && connection.connected && !connection.verifying &&
                    (mode == "ngrok" || phone.isNotBlank()) && (port.toIntOrNull() ?: 0) in 1..65535,
                    onClick = configure@{
                        val target = connection.snapshot() ?: return@configure
                        val previouslyEnabled = sync.enabled
                        val args = JSONObject().put("mode", mode).put("phoneUrl", phone.trim()).put("autostart", login)
                            .put("gatewayPort", port.toInt())
                        if (database.isNotBlank()) args.put("dbPath", database.trim())
                        if (mode == "ngrok" && program.isNotBlank()) args.put("ngrokPath", program.trim())
                        if (mode == "ngrok" && token.isNotBlank()) args.put("ngrokToken", token.trim())
                        runAction {
                            sync.authorize(args, target)
                            message = sync.notice
                            token = ""
                            if (sync.enabled) {
                                checkHost()
                                val ready = host?.takeIf { it.error.isEmpty() && it.mode == mode &&
                                    (phone.isBlank() || it.phone.trimEnd('/') == phone.trim().trimEnd('/')) }
                                if (ready != null) {
                                    step = DeviceStep.PAIR
                                    if (!previouslyEnabled) invitePhone()
                                }
                            }
                        }
                    }) { Text(if (sync.enabled) "应用并检查入口" else "开启共享并生成配对码") }
                DeviceStep.PAIR -> Button(enabled = !busy && sync.enabled, onClick = { runAction { checkHost(); if (host?.error == "") invitePhone() } }) {
                    Text(if (pair == null) "生成配对码" else "重新生成配对码")
                }
            }
        },
        dismissButton = {
            Row {
                if (step == DeviceStep.ENDPOINT) TextButton(enabled = !busy, onClick = { step = DeviceStep.SOURCE; message = "" }) { Text("返回") }
                TextButton(enabled = !busy, onClick = onDismiss) { Text(if (sync.enabled) "完成" else "仅在本机使用") }
            }
        }, modifier = Modifier.testTag("device-setup-dialog"),
    )
    val linkingSource = sync.unmatchedProfiles.firstOrNull { it.profile == linking }
    if (linkingSource != null) {
        var selected by remember(linkingSource.profile) { mutableStateOf<String?>(null) }
        val candidate = sync.upstreamCandidates.firstOrNull { it.profileId == selected }
        AlertDialog(onDismissRequest = { linking = null }, title = { Text("确认同一 AstrBot 来源") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text("手机 · ${linkingSource.name} · ${linkingSource.username}")
                    SelectionContainer { Text(linkingSource.base) }
                    Text("请选择运行同一 AstrBot 的已验证电脑档案。关联后，此手机地址下的会话可经该电脑上游续聊，保留原用户名和归属。")
                    sync.upstreamCandidates.forEach { upstream ->
                        Choice(upstream.name + " · " + upstream.base, upstream.profileId == selected, true) {
                            selected = upstream.profileId
                        }
                    }
                }
            }, confirmButton = {
                TextButton(enabled = candidate != null, onClick = {
                    if (candidate != null) {
                        linking = null
                        runAction { sync.linkSource(linkingSource.profile, candidate.profileId); message = sync.notice }
                    }
                }) { Text("确认是同一 AstrBot 并关联") }
            }, dismissButton = { TextButton(onClick = { linking = null }) { Text("取消") } })
    }
    pendingRevoke?.let { device ->
        AlertDialog(onDismissRequest = { pendingRevoke = null }, title = { Text("撤销 " + device.name + "？") },
            text = { Text("此手机将失去设备目录和后续消息的访问权。电脑历史与手机原有本地会话保留，离线手机需联网才能获知撤销。") },
            confirmButton = { TextButton(onClick = { pendingRevoke = null; runAction { sync.revoke(device.id); message = sync.notice } }) { Text("撤销访问") } },
            dismissButton = { TextButton(onClick = { pendingRevoke = null }) { Text("取消") } })
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = choose, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun DesktopDeviceSourcePicker(connection: DesktopConnection, discovered: DesktopDiscovery?, enabled: Boolean) {
    var address by remember(connection.address) { mutableStateOf(connection.address) }
    var secret by remember(connection.profileId) { mutableStateOf("") }
    var addNewKey by remember(connection.profileId) { mutableStateOf(false) }
    val candidates = desktopSourceChoices(connection.instances, connection.profiles, discovered)
    val only = candidates.singleOrNull()
    LaunchedEffect(only?.id, discovered) {
        if (discovered != null && only != null && connection.profileId.isBlank()) connection.selectProfile(only.id, only.address)
    }
    val active = connection.keyList.activeId
    LaunchedEffect(connection.profileId, connection.address, active) {
        if (active != null && !connection.connected && !connection.verifying && connection.error == null) connection.verify()
    }
    Text("选择用于聊天的 AstrBot。已有密钥可直接验证。")
    candidates.forEach { candidate ->
        Choice(candidate.name + " · " + candidate.address, connection.profileId == candidate.id, enabled) {
            connection.selectProfile(candidate.id, candidate.address)
        }
    }
    OutlinedTextField(address, { address = it }, label = { Text("AstrBot 地址") }, enabled = enabled,
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("device-astrbot-address"))
    TextButton(enabled = enabled && address.isNotBlank(), onClick = { connection.useAddress(address.trim()) }) { Text("使用此地址") }
    if (connection.profileId.isNotBlank()) {
        if (connection.keyList.keys.isNotEmpty()) {
            connection.keyList.keys.forEach { key ->
                Choice(key.name, key.id == (connection.pendingKeyId ?: active), enabled && !connection.verifying) { connection.selectKey(key.id) }
            }
            TextButton(enabled = enabled, onClick = { addNewKey = !addNewKey }) { Text("使用另一把 OpenAPI Key") }
        }
        if (connection.keyList.keys.isEmpty() || addNewKey) {
            OutlinedTextField(secret, { secret = it }, label = { Text("AstrBot OpenAPI Key（chat 权限）") },
                enabled = enabled, singleLine = true, visualTransformation = PasswordVisualTransformation())
            TextButton(onClick = {
                val used = connection.keyList.keys.map { it.name }.toSet()
                val name = generateSequence(1) { it + 1 }.map { if (it == 1) "AstrBot" else "AstrBot $it" }.first { it !in used }
                connection.addKey(name, secret.trim())
                secret = ""
            }, enabled = enabled && secret.isNotBlank() && !connection.verifying) { Text("安全保存并验证") }
        }
        if (!connection.connected && active != null) TextButton(onClick = connection::verify, enabled = enabled && !connection.verifying) {
            Text("重新验证现有密钥")
        }
    }
    Text(when {
        connection.verifying -> "正在向 AstrBot 验证…"
        connection.connected -> "AstrBot 已验证。"
        connection.error != null -> connection.error
        connection.profileId.isBlank() && candidates.isEmpty() -> "尚未找到 AstrBot。请启动 Launcher 或填写部署地址。"
        else -> "请选择档案和聊天密钥。"
    })
}
