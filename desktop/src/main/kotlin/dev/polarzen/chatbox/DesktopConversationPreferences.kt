package dev.polarzen.chatbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PartnerNameDialog(
    handle: ChatPreferenceHandle, onSaved: () -> Unit, onClose: () -> Unit,
) {
    var peer by remember { mutableStateOf(handle.value.peerName.orEmpty()) }
    var profile by remember { mutableStateOf(handle.value.profileName.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val reviewed = handle.conflicts.intersect(setOf("profileName", "peerName"))
    val proposal = handle.value.copy(profileName = profile, peerName = peer)
    val normalized = runCatching { proposal.normalized() }
    AlertDialog(onDismissRequest = onClose, title = { Text("修改对方名称") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(profile, { profile = it; error = null }, singleLine = true,
                label = { Text("服务档案默认名") }, modifier = Modifier.fillMaxWidth().testTag("profile-name-field"))
            OutlinedTextField(peer, { peer = it; error = null }, singleLine = true,
                label = { Text("此会话的名字") }, placeholder = { Text("跟随服务档案") },
                modifier = Modifier.fillMaxWidth().testTag("peer-name-field"))
            Text("顶栏预览", color = desktopPalette.muted, fontSize = 12.sp)
            Text(normalized.getOrNull()?.effectiveName ?: peer.ifBlank { profile }, fontSize = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("peer-name-preview"))
            TextButton(onClick = { peer = ""; error = null }) { Text("此会话恢复默认") }
            TextButton(onClick = { profile = ""; error = null }) { Text("档案恢复默认") }
            if ("profileName" in reviewed) PreferenceConflict("档案默认名",
                profile.ifBlank { "助手" }, handle.remoteValue.profileName ?: "助手") {
                profile = handle.remoteValue.profileName.orEmpty()
            }
            if ("peerName" in reviewed) PreferenceConflict("此会话的名字",
                peer.ifBlank { "跟随服务档案" }, handle.remoteValue.peerName ?: "跟随服务档案") {
                peer = handle.remoteValue.peerName.orEmpty()
            }
            (normalized.exceptionOrNull()?.message ?: error)?.let { Text(it, color = MaterialTheme.colors.error) }
        }
    }, confirmButton = { TextButton(enabled = normalized.isSuccess, onClick = {
        try { handle.saveReviewed(normalized.getOrThrow(), reviewed); onSaved() }
        catch (e: Exception) { error = preferenceFailure(e) }
    }, modifier = Modifier.testTag("save-peer-name")) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

internal fun preferenceFailure(error: Exception): String = when (error) {
    is IllegalArgumentException, is IllegalStateException -> error.message?.take(90) ?: "设置已更改，请重新打开。"
    else -> "无法保存设置，请检查本地数据目录。"
}

@Composable
internal fun ConversationContextDialog(
    handle: ChatPreferenceHandle, turns: List<ContextTurn>, question: String,
    probe: suspend (ConversationPreferences) -> AstrBotContextPreview,
    adding: ContextPin?, onSaved: () -> Unit, onClose: () -> Unit,
) {
    val initial = handle.value
    val reviewed = handle.conflicts.intersect(setOf("recentTurns", "pins", "remindAfter"))
    var custom by remember { mutableStateOf(initial.recentTurns != null || adding != null) }
    var count by remember { mutableStateOf((initial.recentTurns ?: 3).toString()) }
    var threshold by remember { mutableStateOf(initial.remindAfter?.toString().orEmpty()) }
    var pins by remember { mutableStateOf((initial.pins + listOfNotNull(adding)).distinct()) }
    var error by remember { mutableStateOf<String?>(null) }
    val recent = count.toIntOrNull()
    val reminder = threshold.toIntOrNull()
    val validCount = !custom || recent != null && recent in 0..500
    val validReminder = threshold.isBlank() || reminder != null && reminder in 1..10000
    val desired = initial.copy(recentTurns = if (custom) recent else null,
        pins = if (custom) pins else emptyList(), remindAfter = reminder)
    val preview = if (validCount && validReminder && desired.pins.size <= 128) selectContext(turns, desired) else emptyList()
    var verified by remember(desired.recentTurns, desired.pins, question) { mutableStateOf<AstrBotContextPreview?>(null) }
    val previewPreferences = desired.copy(recentTurns = desired.recentTurns ?: 0)
    LaunchedEffect(previewPreferences.recentTurns, previewPreferences.pins, validCount, question) {
        if (validCount) {
            kotlinx.coroutines.delay(250)
            verified = probe(previewPreferences)
        }
    }
    val selected = preview.orEmpty().map { ContextPin(it.seq, it.role) }.toSet()
    val missing = desired.pins.filterNot(selected::contains)
    AlertDialog(onDismissRequest = onClose, title = { Text("上下文") }, text = {
        LazyColumn(Modifier.widthIn(max = 540.dp).fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("已保存 ${turns.size} 轮聊天记录", color = desktopPalette.muted, fontSize = 12.sp)
                Text(when {
                    verified == null -> "正在检查服务的上下文能力…"
                    verified?.supported != true -> verified?.reason.orEmpty()
                    else -> verified?.reason?.ifBlank { "服务支持自选上下文。模型窗口以服务发送前的核验为准。" }.orEmpty()
                }, color = desktopPalette.muted, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for ((mode, label) in listOf(false to "跟随服务默认", true to "自选最近轮数")) {
                        Row(Modifier.selectable(custom == mode, role = Role.RadioButton, onClick = { custom = mode })
                            .padding(end = 12.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(custom == mode, onClick = null)
                            Text(label, Modifier.padding(start = 4.dp))
                        }
                    }
                }
                if (custom) OutlinedTextField(count, { count = it; error = null }, singleLine = true,
                    isError = !validCount, label = { Text("最近轮数（0–500）") },
                    modifier = Modifier.fillMaxWidth().testTag("context-recent-count"))
                OutlinedTextField(threshold, { threshold = it; error = null }, singleLine = true, isError = !validReminder,
                    label = { Text("自设轮数提醒（可留空）") },
                    modifier = Modifier.fillMaxWidth().testTag("context-remind-after"))
                Spacer(Modifier.height(10.dp))
                Text(if (custom && verified?.supported == true) "服务预览" else "本机选择", fontWeight = FontWeight.SemiBold)
                Text(if (preview == null) "由服务管理历史，并保留当前问题。"
                    else "最近 ${recent ?: 0} 轮和 ${desired.pins.size} 条固定消息。另含当前问题。",
                    fontSize = 13.sp)
                if (!custom && pins.isNotEmpty()) Text("保存服务默认后，将移除 ${pins.size} 条固定选择。", fontSize = 12.sp)
                if (missing.isNotEmpty()) Text("有 ${missing.size} 条固定消息暂未取得，请先同步或移除。", color = MaterialTheme.colors.error)
            }
            if (custom && verified?.supported == true) {
                val result = checkNotNull(verified)
                items(result.selected.size) { index ->
                    PreviewMessage(result, index, initial.effectiveName)
                }
                if (desired.pins.isNotEmpty()) item { Text("手动固定", fontWeight = FontWeight.SemiBold) }
            }
            val localItems = preview.orEmpty().filter {
                verified?.supported != true || ContextPin(it.seq, it.role) in pins
            }
            items(localItems, key = { "${it.seq}:${it.role}" }) { message ->
                val pin = ContextPin(message.seq, message.role)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("第 ${message.seq} 轮 · ${if (message.role == "user") "我" else initial.effectiveName}",
                            color = desktopPalette.muted, fontSize = 11.sp)
                        Text(message.text, fontSize = 13.sp)
                    }
                    if (pin in pins) TextButton(onClick = { pins = pins - pin }) { Text("取消固定") }
                }
            }
            items(missing) { pin ->
                TextButton(onClick = { pins = pins - pin }) { Text("移除第 ${pin.seq} 轮的${if (pin.role == "user") "提问" else "回复"}") }
            }
            item {
                val remote = handle.remoteValue
                if ("recentTurns" in reviewed) PreferenceConflict("最近轮数", if (custom) count else "服务默认",
                    remote.recentTurns?.toString() ?: "服务默认") {
                    custom = remote.recentTurns != null
                    remote.recentTurns?.let { count = it.toString() }
                }
                if ("pins" in reviewed) PreferenceConflict("固定消息", formatContextPins(if (custom) pins else emptyList()),
                    formatContextPins(remote.pins)) {
                    pins = remote.pins
                    if (pins.isNotEmpty() && !custom) {
                        custom = true
                        count = (remote.recentTurns ?: 0).toString()
                    }
                }
                if ("remindAfter" in reviewed) PreferenceConflict("自设轮数提醒", threshold.ifBlank { "关闭" },
                    remote.remindAfter?.toString() ?: "关闭") { threshold = remote.remindAfter?.toString().orEmpty() }
                error?.let { Text(it, color = MaterialTheme.colors.error) }
            }
        }
    }, confirmButton = { TextButton(enabled = validCount && validReminder && missing.isEmpty(), onClick = {
        try { handle.saveReviewed(desired, reviewed); onSaved() } catch (e: Exception) { error = preferenceFailure(e) }
    }, modifier = Modifier.testTag("save-context")) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable
internal fun ContextSendDialog(
    question: String, peer: String, preview: AstrBotContextPreview?, onConfirm: () -> Unit,
    onAdjust: () -> Unit, onClose: () -> Unit,
) {
    AlertDialog(onDismissRequest = onClose, title = { Text("发送前核对上下文") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            item {
                Text(preview?.reason?.ifBlank { "服务已核对本次参考的消息。" } ?: "正在向服务核对…",
                    color = if (preview?.canSend == false) MaterialTheme.colors.error else desktopPalette.muted, fontSize = 13.sp)
            }
            if (preview != null) items(preview.selected.size) { i -> PreviewMessage(preview, i, peer) }
            item {
                Text("当前问题", color = desktopPalette.muted, fontSize = 11.sp)
                Text(question, fontSize = 14.sp)
                TextButton(onClick = onAdjust) { Text("调整上下文") }
            }
        }
    }, confirmButton = { TextButton(enabled = preview?.canSend == true && preview.supported,
        onClick = onConfirm, modifier = Modifier.testTag("confirm-context-send")) { Text("发送") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable
private fun PreviewMessage(result: AstrBotContextPreview, index: Int, peer: String) {
    val message = result.selected[index]
    val fit = result.fittingVisibleMessagesEstimate?.size
    val budget = if (fit == null) "" else if (index < fit) " · 预计可容纳" else " · 预计超出上限"
    Text((if (message.role == "user") "我" else peer) + budget, color = desktopPalette.muted, fontSize = 11.sp)
    Text(message.text.ifEmpty { "此设备未核实正文" }, fontSize = 13.sp)
}

private fun formatContextPins(pins: List<ContextPin>): String =
    pins.joinToString("、") { "第 ${it.seq} 轮${if (it.role == "user") "提问" else "回复"}" }.ifEmpty { "无" }

@Composable
private fun PreferenceConflict(field: String, local: String, remote: String, useRemote: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text("$field · 两端已有不同修改", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("本机：$local", fontSize = 12.sp)
        Text("另一台设备：$remote", fontSize = 12.sp)
        TextButton(onClick = useRemote, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("采用另一台设备的值") }
    }
}
