package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal typealias SharedRoomComposer = @Composable (SharedChatState, Boolean, Boolean, () -> Unit) -> Unit

@Composable
internal fun SharedChatPanel(
    seed: SharedSeed,
    storage: SharedLocalStorage,
    allowLoopback: Boolean,
    decorateQuestion: (String) -> String = { it },
    retainedState: SharedChatState? = null,
    pairingScope: CoroutineScope? = null,
    composer: SharedRoomComposer = { state, visible, sendEnabled, send ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(if (visible) state.local.draft else "", state::editDraft, Modifier.weight(1f),
                enabled = visible && state.pending == null, maxLines = 3, label = { Text("消息") })
            Button(onClick = send, enabled = sendEnabled && state.local.draft.isNotBlank()) { Text("发送") }
        }
    },
    onClose: () -> Unit,
) {
    key(storage, seed.sourceBase, seed.username, seed.session, allowLoopback) {
        val decorate by rememberUpdatedState(decorateQuestion)
        val state = retainedState ?: remember { SharedChatState(storage, allowLoopback) { decorate(it) } }
        DisposableEffect(state) { onDispose { state.flushDraft() } }
        val scope = rememberCoroutineScope()
        val binding = state.binding
        var verifiedHere by remember(state, binding) { mutableStateOf(false) }
        LaunchedEffect(state, binding) {
            if (binding != null) {
                while (state.binding == binding) {
                    state.refresh()
                    if (state.authorized) verifiedHere = true
                    val active = (state.pending != null && !state.pendingMissing) || state.turns.lastOrNull()?.status == "running"
                    delay(if (active && state.authorized) 650L else 2500L)
                }
            }
        }
        Surface(Modifier.fillMaxSize()) {
            when {
                state.revoked || state.unreadable -> Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(if (state.unreadable) "无法读取本机共享绑定" else "此设备的访问权限已撤销",
                        style = MaterialTheme.typography.h6)
                    Text(if (state.unreadable) "退出可移除本机绑定，之后可以使用新加入码重新连接。"
                        else "如需继续，请让会话发起设备生成新的加入码。")
                    if (state.notice.isNotEmpty()) Text(state.notice)
                    Button(onClick = { scope.launch { state.leave(onClose) } }, enabled = !state.busy) { Text("退出共享") }
                }
                binding == null -> SharedConnectionForm(seed, state, allowLoopback, pairingScope ?: scope, onClose)
                else -> SharedRoom(state, binding, retainedState == null || verifiedHere, composer, onClose)
            }
        }
    }
}

@Composable
private fun SharedConnectionForm(
    seed: SharedSeed,
    state: SharedChatState,
    allowLoopback: Boolean,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    var create by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf("") }
    var code by remember(create) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("跨设备共享", Modifier.weight(1f), style = MaterialTheme.typography.h6)
            TextButton(onClick = onClose, enabled = !state.busy) { Text("返回") }
        }
        TabRow(selectedTabIndex = if (create) 0 else 1) {
            Tab(create, onClick = { create = true }, enabled = !state.busy, text = { Text("在另一台设备继续") })
            Tab(!create, onClick = { create = false }, enabled = !state.busy, text = { Text("加入已有会话") })
        }
        Text(if (create) "填写共享服务的地址和管理员提供的创建码。创建后，可以给另一台设备生成一次性加入码。"
            else "填写会话发起设备提供的共享服务地址和一次性加入码。")
        if (create) {
            Text("将导入此设备当前可见的 ${seed.history.size} 轮记录，包括提问、回复和附件提示。附件文件不会上传，其他设备独有、已清理或未加载的旧记录无法补回。")
        } else {
            Text("加入后可查看共享记录并继续发送文字。此设备原有的聊天记录保留在本机。")
        }
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), enabled = !state.busy,
            singleLine = true, label = { Text("共享服务地址") },
            placeholder = { Text(if (allowLoopback) "https://… 或 http://127.0.0.1:端口" else "https://…") })
        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true,
            label = { Text(if (create) "创建码" else "一次性加入码") }, visualTransformation = PasswordVisualTransformation())
        if (state.notice.isNotEmpty()) Text(state.notice, color = MaterialTheme.colors.error)
        Button(onClick = { scope.launch { state.connect(address, code, if (create) seed else null) } },
            enabled = !state.busy && address.isNotBlank() && code.isNotBlank()) {
            Text(if (state.busy) "连接中…" else if (create) "导入并创建共享会话" else "加入会话")
        }
    }
}

private enum class SharedDialog { INVITE, REVOKE, LEAVE, RELEASE }

@Composable
private fun SharedRoom(state: SharedChatState, binding: SharedBinding, verifiedHere: Boolean,
                       composer: SharedRoomComposer, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<SharedDialog?>(null) }
    val visible = verifiedHere && state.verifiedInView
    val pending = state.pending.takeIf { visible }
    val turns = if (visible) state.turns else emptyList()
    val running = turns.any { it.status == "running" }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("共享会话", style = MaterialTheme.typography.h6)
                    Text(state.connection, style = MaterialTheme.typography.caption)
                }
                if (binding.owner) TextButton(onClick = { dialog = SharedDialog.INVITE }, enabled = visible) { Text("邀请") }
                Column {
                    TextButton(onClick = { menu = true }) { Text("更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(onClick = { menu = false; scope.launch { state.refresh() } }) { Text("刷新记录") }
                        if (binding.owner) DropdownMenuItem(onClick = { menu = false; dialog = SharedDialog.REVOKE }) {
                            Text("撤销其他设备")
                        }
                        DropdownMenuItem(onClick = { menu = false; dialog = SharedDialog.LEAVE }) { Text("退出本机共享") }
                    }
                }
            }
            Divider()
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending != null && turns.none { it.id == pending.id }) {
                    item(key = pending.id) {
                        SharedMessages(pending.question, "", "正在核对发送结果…")
                    }
                }
                items(turns.asReversed(), key = { it.id }) { turn ->
                    val status = when (turn.status) {
                        "running" -> "回复生成中…"
                        "unknown" -> "回复完成情况未知，请核对后再继续。"
                        else -> null
                    }
                    SharedMessages(turn.question, turn.answer, status)
                }
                if (visible && turns.isEmpty() && pending == null && state.authorized) item {
                    Text("共享记录为空，发送文字开始对话。", Modifier.padding(vertical = 8.dp))
                }
                if ((visible && state.authorized) || turns.isNotEmpty()) item(key = "shared-history-start") {
                    Text("共享记录起点 · 仅包含创建时发起设备可见的旧记录，以及之后在共享会话中发送的内容。其他设备独有的旧记录可能缺失。",
                        Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.caption)
                }
            }
            if (state.saveFailed) {
                Text("本机保存失败，重新打开后可能无法恢复。", color = MaterialTheme.colors.error)
                TextButton(onClick = state::retrySave) { Text("重试保存") }
            }
            if (state.notice.isNotEmpty()) Text(state.notice, style = MaterialTheme.typography.body2)
            if (pending != null && state.pendingMissing) {
                Text("尚未查到这次发送。按原编号重试会先核对记录，服务会将相同编号的提交合并为一轮。", style = MaterialTheme.typography.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch { state.retryPending(pending.id) } }, enabled = !state.busy) {
                        Text("按原编号重试")
                    }
                    TextButton(onClick = { dialog = SharedDialog.RELEASE }, enabled = !state.busy) { Text("放弃并恢复草稿") }
                }
            }
        }
        composer(state, visible, visible && state.authorized && !state.busy && !running && pending == null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { state.send() }
        }
    }
    when (dialog) {
        null -> Unit
        SharedDialog.INVITE -> SharedInviteDialog(state, binding) { dialog = null }
        SharedDialog.REVOKE -> SharedConfirm("撤销其他设备？", "所有已加入设备都将失去此会话的读取和发送权限。",
            "撤销访问", { dialog = null }) { dialog = null; scope.launch { state.revokeGuests() } }
        SharedDialog.LEAVE -> SharedConfirm("退出本机共享？",
            if (binding.owner) "本机会移除共享绑定和管理凭据。其他设备仍可访问，若需停止其访问，请先撤销其他设备。"
            else "本机会移除共享绑定和草稿。再次加入需要新的加入码。",
            "移除本机绑定", { dialog = null }) { dialog = null; scope.launch { state.leave(onClose) } }
        SharedDialog.RELEASE -> SharedConfirm("停止核对这次发送？",
            "原提问会恢复为草稿。再次发送会使用新编号，如果服务已接收最初的提交，可能生成重复回复。",
            "停止核对并恢复草稿", { dialog = null }) { dialog = null; scope.launch { state.releasePending() } }
    }
}

@Composable
internal fun SharedMessages(question: String, answer: String, status: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("提问", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
        SharedMessageBody(question)
        Text("回复", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
        if (answer.isNotEmpty()) SharedMessageBody(answer)
        if (status != null) Text(status, style = MaterialTheme.typography.caption)
        Divider(Modifier.padding(top = 6.dp))
    }
}

// The protocol carries inline text for both imported media and new bridge replies.
private val sharedHint = Regex("""\[(?:图片|语音|文件)附件]|〔(?:图片|语音|附件(?:：[^〕\r\n]*)?)〕""")
private val namedHint = Regex("""〔附件：([^〕\r\n]*)〕""")

@Composable
private fun SharedMessageBody(text: String) {
    val display = remember(text) {
        namedHint.replace(text) { match ->
            val name = match.groupValues[1]
            if ('/' in name || '\\' in name) "〔附件〕" else match.value
        }
    }
    SelectionContainer { Text(display) }
    // Android imports that already say “仅原设备可用” carry their own availability label.
    if (remember(display) { sharedHint.containsMatchIn(display) }) {
        Text("共享记录仅含附件提示，无法在此打开文件。", style = MaterialTheme.typography.caption)
    }
}

@Composable
private fun SharedInviteDialog(state: SharedChatState, binding: SharedBinding, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val invite = state.invite
    var now by remember(invite) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(invite) {
        while (invite != null && now < invite.expiresAt) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val valid = invite != null && now < invite.expiresAt
    AlertDialog(onDismissRequest = close, title = { Text("邀请另一台设备") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("在另一台设备选择「加入已有会话」，输入共享服务地址和一次性加入码。")
            if (java.net.URI(binding.address).host == "127.0.0.1") {
                Text("手机请填写共享服务的局域网 HTTPS 地址。随包配置使用以下地址。")
                SelectionContainer { Text("https://chatbox.home.arpa:8443") }
            } else {
                SelectionContainer { Text(binding.address) }
            }
            if (valid) {
                SelectionContainer { Text(invite.code, style = MaterialTheme.typography.h5) }
                Text("剩余 ${(invite.expiresAt - now + 999) / 1000} 秒，使用一次后失效。")
            } else if (invite != null) Text("加入码已过期。")
            Button(onClick = { scope.launch { state.invite() } }, enabled = !state.busy && state.authorized) {
                Text(if (valid) "生成新的加入码" else "生成一次性加入码")
            }
            if (valid) TextButton(onClick = { scope.launch { state.cancelInvite() } }, enabled = !state.busy) {
                Text("取消加入码")
            }
            if (state.notice.isNotEmpty()) Text(state.notice)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}

@Composable
private fun SharedConfirm(title: String, detail: String, accept: String, cancel: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = confirm) { Text(accept) } },
        dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
}
