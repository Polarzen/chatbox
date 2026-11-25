package dev.polarzen.chatbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun mobileContextTurns(session: MobileSession, handle: ChatPreferenceHandle): List<ContextTurn> =
    session.turns.mapIndexedNotNull { index, turn ->
        handle.sequenceAt(index)?.let { seq ->
            ContextTurn(seq, turn.question, turn.answer, if (turn.status == "已完成") "complete" else turn.status)
        }
    }

@Composable
private fun ConflictChoice(field: String, local: String, remote: String, onUseRemote: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${field}存在跨端修改", style = MaterialTheme.typography.titleSmall)
        Text("本机 · $local", style = MaterialTheme.typography.bodySmall)
        Text("另一端 · $remote", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onUseRemote) { Text("采用另一端的$field") }
    }
}

private fun pinLabels(pins: List<ContextPin>): String =
    pins.joinToString("、") { "第${it.seq}轮${if (it.role == "user") "提问" else "回复"}" }.ifEmpty { "无" }

@Composable
internal fun AndroidNameDialog(handle: ChatPreferenceHandle, onDismiss: () -> Unit) {
    val opened = remember(handle.identity) { handle }
    var profile by remember { mutableStateOf(false) }
    var profileViewed by remember { mutableStateOf(false) }
    val reviewed = if (profileViewed) setOf("peerName", "profileName") else setOf("peerName")
    val nameField = if (profile) "profileName" else "peerName"
    var peerText by remember { mutableStateOf(opened.value.peerName.orEmpty()) }
    var profileText by remember { mutableStateOf(opened.value.profileName.orEmpty()) }
    var saveError by rememberSaveable { mutableStateOf("") }
    val checked = runCatching { opened.value.copy(peerName = peerText, profileName = profileText).normalized() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("修改对方名称") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!profile, { profile = false }, { Text("此会话") })
                    FilterChip(profile, { profile = true; profileViewed = true }, { Text("档案默认") })
                }
                OutlinedTextField(if (profile) profileText else peerText,
                    onValueChange = { if (profile) profileText = it else peerText = it; saveError = "" },
                    label = { Text(if (profile) "服务档案的默认名称" else "此会话的名称") },
                    singleLine = true, isError = checked.isFailure)
                Text("聊天顶栏预览", style = MaterialTheme.typography.labelMedium)
                checked.getOrNull()?.let { Text(it.effectiveName, style = MaterialTheme.typography.titleLarge) }
                TextButton(onClick = { if (profile) profileText = "" else peerText = ""; saveError = "" }) {
                    Text("恢复默认")
                }
                if (!handle.ready) Text("此会话的来源尚未核实。")
                else if (handle.pending) Text("设置已保存到本机，等待同步。")
                if (nameField in opened.conflicts) {
                    val local = if (profile) opened.value.profileName else opened.value.peerName
                    val other = if (profile) opened.remoteValue.profileName else opened.remoteValue.peerName
                    val fallback = if (profile) "默认（助手）" else "跟随服务档案"
                    ConflictChoice("名称", local ?: fallback, other ?: fallback) {
                        if (profile) profileText = other.orEmpty() else peerText = other.orEmpty()
                        saveError = ""
                    }
                    Text("保存将采用输入框中的名称。", style = MaterialTheme.typography.bodySmall)
                }
                if (!profileViewed && "profileName" in opened.conflicts) Text("档案默认名也有跨端修改，可切换到「档案默认」核对。")
                val error = checked.exceptionOrNull()?.message ?: saveError
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(enabled = opened.ready && checked.isSuccess, onClick = {
                runCatching { opened.saveReviewed(checked.getOrThrow(), reviewed) }
                    .onSuccess { onDismiss() }.onFailure { saveError = it.message ?: "名称未保存。" }
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun AndroidContextDialog(handle: ChatPreferenceHandle, turns: List<ContextTurn>, available: Boolean,
                                 capabilityNote: String, addPin: ContextPin?, onDismiss: () -> Unit) {
    val opened = remember(handle.identity) { handle }
    var useService by remember { mutableStateOf(opened.value.recentTurns == null && addPin == null) }
    var count by remember { mutableStateOf(opened.value.recentTurns?.toString() ?: if (addPin == null) "" else "0") }
    var threshold by remember { mutableStateOf(opened.value.remindAfter?.toString().orEmpty()) }
    var error by rememberSaveable { mutableStateOf("") }
    val recent = count.toIntOrNull()
    val alert = threshold.toIntOrNull()
    val valid = (useService || (recent != null && recent in 0..500)) &&
        (threshold.isBlank() || (alert != null && alert in 1..10000))
    var pins by remember { mutableStateOf((opened.value.pins + listOfNotNull(addPin)).distinct()) }
    val desired = opened.value.copy(recentTurns = recent.takeUnless { useService },
        pins = if (useService) emptyList() else pins, remindAfter = alert)
    val preview = if (valid && desired.pins.size <= 128) selectContext(turns, desired) else null
    val unavailable = desired.pins - preview.orEmpty().map { ContextPin(it.seq, it.role) }.toSet()
    val contextFields = mapOf("recentTurns" to "最近轮数", "pins" to "固定消息", "remindAfter" to "自设轮数提醒")
    val disputed = contextFields.filterKeys { it in opened.conflicts }.values
    val remote = opened.remoteValue
    val canEditSelection = available || "recentTurns" in opened.conflicts || "pins" in opened.conflicts
    val listState = rememberLazyListState()
    var reviewed by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(listState, opened) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.removePrefix("conflict:") } }
            .collect { shown -> reviewed = reviewed + shown.filter { it in opened.conflicts && it in contextFields } }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("会话上下文") },
        text = {
            LazyColumn(Modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("聊天记录保存在会话中。这里选择下次回复可以参考的历史。")
                    if (capabilityNote.isNotBlank()) Text(capabilityNote, Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if ("recentTurns" in opened.conflicts) item(key = "conflict:recentTurns") {
                    fun recentLabel(value: Int?) = value?.let { "最近 ${it} 轮" } ?: "跟随服务"
                    ConflictChoice("最近轮数", recentLabel(opened.value.recentTurns), recentLabel(remote.recentTurns)) {
                        useService = remote.recentTurns == null
                        count = remote.recentTurns?.toString().orEmpty()
                        error = ""
                    }
                }
                if ("pins" in opened.conflicts) item(key = "conflict:pins") {
                    ConflictChoice("固定消息", pinLabels(opened.value.pins), pinLabels(remote.pins)) {
                        pins = remote.pins
                        if (useService && pins.isNotEmpty()) {
                            useService = false
                            count = remote.recentTurns?.toString().orEmpty()
                        }
                        error = ""
                    }
                    if (useService && remote.pins.isNotEmpty()) Text("采用这些固定消息时，将同时使用另一端的最近 ${remote.recentTurns} 轮。")
                }
                if ("remindAfter" in opened.conflicts) item(key = "conflict:remindAfter") {
                    fun thresholdLabel(value: Int?) = value?.let { "${it} 轮" } ?: "关闭"
                    ConflictChoice("提醒轮数", thresholdLabel(opened.value.remindAfter), thresholdLabel(remote.remindAfter)) {
                        threshold = remote.remindAfter?.toString().orEmpty()
                        error = ""
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(useService, { useService = true }, { Text("跟随服务") })
                        FilterChip(!useService, { useService = false }, { Text("自选轮数") }, enabled = canEditSelection)
                    }
                }
                if (!useService) item {
                    OutlinedTextField(count, { count = it; error = "" }, label = { Text("最近几轮（0–500）") },
                        enabled = canEditSelection, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("一轮包含一个用户问题和回复。可长按已完成的消息，将其单独加入。", style = MaterialTheme.typography.bodySmall)
                }
                if (useService && pins.isNotEmpty()) item {
                    Text("保存后将解除已固定的 ${pins.size} 条消息。")
                }
                item {
                    OutlinedTextField(threshold, { threshold = it; error = "" }, singleLine = true,
                        label = { Text("自设轮数提醒（可留空）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                item { Text("固定消息 · ${desired.pins.size} 条", style = MaterialTheme.typography.labelMedium) }
                if (preview != null) {
                    item { Text("下一次参考 · ${preview.size} 条消息", style = MaterialTheme.typography.titleSmall) }
                    items(preview, key = { "${it.seq}:${it.role}" }) { message ->
                        ContextPreviewRow(message, desired)
                        val pin = ContextPin(message.seq, message.role)
                        if (pin in pins) TextButton(onClick = { pins = pins - pin }) { Text("取消固定") }
                    }
                }
                items(unavailable) { pin ->
                    TextButton(onClick = { pins = pins - pin }) {
                        Text("移除未载入的第 ${pin.seq} 轮${if (pin.role == "user") "提问" else "回复"}")
                    }
                }
                if (desired.pins.size > 128) item { Text("最多可固定 128 条消息。", color = MaterialTheme.colorScheme.error) }
                if (disputed.isNotEmpty()) item {
                    Text("另一台设备也修改了${disputed.joinToString("、")}。保存将采用此处的值。")
                }
                if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = opened.ready && valid && desired.pins.size <= 128 && unavailable.isEmpty() &&
                (canEditSelection || useService || desired.recentTurns == opened.value.recentTurns && desired.pins == opened.value.pins), onClick = {
                runCatching { opened.saveReviewed(desired, reviewed) }.onSuccess { onDismiss() }
                    .onFailure { error = it.message ?: "上下文设置未保存。" }
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ContextPreviewRow(message: ContextMessage, prefs: ConversationPreferences) {
    var expanded by rememberSaveable(message.seq, message.role) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 3.dp)) {
        val role = if (message.role == "user") "你" else prefs.effectiveName
        val pinned = ContextPin(message.seq, message.role) in prefs.pins
        Text("第 ${message.seq} 轮 · $role" + if (pinned) " · 已固定" else "",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message.text, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AndroidContextPreview(handle: ChatPreferenceHandle, question: String, result: AstrBotContextPreview?,
                                  canSend: Boolean, adjust: () -> Unit, onSend: () -> Unit, onDismiss: () -> Unit) {
    val prefs = handle.value
    AlertDialog(onDismissRequest = onDismiss, title = { Text("发送前预览") },
        text = {
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("最近 ${prefs.recentTurns ?: 0} 轮 · 固定 ${prefs.pins.size} 条") }
                if (result == null) item { Text("正在向服务核对本次选择…") }
                else {
                    if (result.supported) item { Text("服务预览 · ${result.selected.size} 条历史消息", style = MaterialTheme.typography.titleSmall) }
                    items(result.selected.size) { index ->
                        val message = result.selected[index]
                        val fit = result.fittingVisibleMessagesEstimate?.size
                        Column {
                            Text((if (message.role == "user") "你" else prefs.effectiveName) +
                                if (fit == null) "" else if (index < fit) " · 预计可容纳" else " · 预计超出",
                                style = MaterialTheme.typography.labelSmall)
                            Text(message.text.ifEmpty { "此设备未核实正文" })
                        }
                    }
                    if (result.estimatedTokens != null && result.maxTokens != null) item {
                        Text("预计 ${result.estimatedTokens} tokens · 配置上限 ${result.maxTokens}")
                    }
                    if (result.reason.isNotBlank()) item {
                        Text(result.reason, color = if (result.canSend) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
                item {
                    Text("当前问题", style = MaterialTheme.typography.labelMedium)
                    Text(result?.networkMessage ?: question.ifBlank { "附件消息" })
                }
                item { TextButton(onClick = adjust) { Text("调整上下文") } }
            }
        }, confirmButton = {
            TextButton(enabled = canSend && result?.canSend == true, onClick = onSend) { Text("确认发送") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("返回编辑") } })
}
