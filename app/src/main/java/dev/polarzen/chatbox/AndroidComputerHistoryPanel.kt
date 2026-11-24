package dev.polarzen.chatbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AndroidHistorySourceTabs(computer: Boolean, onPhone: () -> Unit, onComputer: () -> Unit) {
    TabRow(selectedTabIndex = if (computer) 1 else 0) {
        Tab(selected = !computer, onClick = onPhone, text = { Text("此手机") })
        Tab(selected = computer, onClick = onComputer, text = { Text("来自电脑") })
    }
}

@Composable
internal fun AndroidComputerHistoryPanel(state: AndroidPcHistory, work: CoroutineScope, onPhone: () -> Unit) {
    val selected = state.selected
    BackHandler(enabled = state.bound && selected != null, onBack = state::back)
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        if (!state.bound || selected == null) {
            Text("最近对话", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleLarge)
            AndroidHistorySourceTabs(computer = true, onPhone = onPhone, onComputer = {})
        }
        if (state.bound) {
            val reading = rememberSaveableStateHolder()
            if (selected == null) reading.SaveableStateProvider("directory") { ComputerDirectory(state, work) }
            else reading.SaveableStateProvider("chat-" + selected.sid) { ComputerConversation(state, selected, work) }
        } else {
            ComputerPairing(state, work)
        }
    }
}

@Composable
private fun ComputerPairing(state: AndroidPcHistory, work: CoroutineScope) {
    var address by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.cleanupRequired) {
            Text("电脑档案缓存待清理", style = MaterialTheme.typography.titleMedium)
            Text(state.notice)
            Button(onClick = state::forget, enabled = !state.busy) { Text("重试清理本机档案") }
            return@Column
        }
        Text("配对电脑档案", style = MaterialTheme.typography.titleMedium)
        Text("在 Windows 当前档案选择「与手机共享此档案的会话」，再输入电脑提供的共享服务地址和一次性配对码。配对后可浏览此档案已有及以后新增的会话。")
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
            enabled = !state.busy, label = { Text("共享服务 HTTPS 地址") }, placeholder = { Text("https://…") })
        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), singleLine = true,
            enabled = !state.busy, label = { Text("一次性档案配对码") }, visualTransformation = PasswordVisualTransformation())
        if (state.notice.isNotBlank()) Text(state.notice, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val endpoint = address.trim()
            val invitation = code.trim()
            work.launch { state.join(endpoint, invitation) }
        }, enabled = !state.busy && code.isNotBlank() && address.isNotBlank()) {
            Text(if (state.busy) "正在配对…" else "配对档案")
        }
    }
}

private fun computerTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private fun computerConnection(state: AndroidPcHistory): String = when {
    state.connected -> "已连接共享服务"
    state.syncing && state.lastSync == 0L -> "正在连接共享服务…"
    state.lastSync > 0L || state.received > 0 || state.sessions.isNotEmpty() || state.selected != null -> "离线 · 显示本机缓存"
    state.notice.isNotBlank() -> "连接失败"
    else -> "等待连接共享服务"
}

@Composable
private fun ComputerDirectory(state: AndroidPcHistory, work: CoroutineScope) {
    var menu by remember { mutableStateOf(false) }
    var forget by rememberSaveable { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("来源档案 · " + state.profileName, Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "档案操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("立即同步") }, onClick = {
                        menu = false
                        work.launch { state.refresh() }
                    })
                    DropdownMenuItem(text = { Text(if (state.showHidden) "返回可见会话" else "查看本机已隐藏") }, onClick = {
                        menu = false
                        state.filterHidden(!state.showHidden)
                        scope.launch { list.scrollToItem(0) }
                    })
                    DropdownMenuItem(text = { Text("共享与保留说明") }, onClick = { menu = false; info = true })
                    DropdownMenuItem(text = { Text("解除本机配对") }, onClick = { menu = false; forget = true })
                }
            }
        }
        OutlinedTextField(state.query, {
            state.search(it)
            scope.launch { list.scrollToItem(0) }
        }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("按标题搜索") })
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            item("status") {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(computerConnection(state), style = MaterialTheme.typography.labelLarge)
                    if (state.lastSync > 0L) Text("上次同步 · " + computerTime(state.lastSync),
                        style = MaterialTheme.typography.bodySmall)
                    if (!state.syncComplete) {
                        if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        val stage = if (state.lastSync == 0L) "首次同步" else "档案同步"
                        Text(stage + "尚未完成 · 已收到 " + state.received + " 个会话" +
                            (if (state.expected > 0) "，预计 " + state.expected + " 个" else ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.issue.isNotBlank()) Text(state.issue, color = MaterialTheme.colorScheme.error)
                    if (state.notice.isNotBlank()) Text(state.notice, style = MaterialTheme.typography.bodySmall)
                    if (state.showHidden) Text("本机已隐藏", style = MaterialTheme.typography.titleSmall)
                }
            }
            if (state.sessions.isEmpty()) item("empty") {
                val reason = when {
                    !state.syncComplete && state.received == 0 -> ""
                    state.query.isNotBlank() -> "已收到的会话中没有匹配的标题。"
                    state.showHidden -> "此手机没有已隐藏的会话。"
                    state.issue.isNotBlank() && state.received == 0 -> "此档案有同步缺口，目前没有可显示的记录。"
                    !state.connected && state.syncComplete && state.received == 0 -> "上次同步时此档案没有会话。"
                    state.syncComplete && state.received == 0 -> "此电脑档案暂无会话。"
                    state.received > 0 -> "此手机没有可见会话，可在档案操作中查看已隐藏会话。"
                    else -> ""
                }
                if (reason.isNotEmpty()) Text(reason, Modifier.padding(vertical = 12.dp))
            }
            items(state.sessions, key = { "session-" + it.sid }) { row ->
                Column(Modifier.fillMaxWidth().clickable(enabled = !state.showHidden) { state.open(row.sid) }
                    .padding(vertical = 10.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall)
                    Text(if (row.activity > 0L) "最近活动 · " + computerTime(row.activity) else "最近活动时间未知",
                        style = MaterialTheme.typography.bodySmall)
                    if (row.imported < row.total) Text("旧历史已导入 " + row.imported + " / " + row.total + " 轮",
                        style = MaterialTheme.typography.bodySmall)
                    if (state.showHidden) TextButton(onClick = { state.hide(row.sid, false) }) { Text("恢复到最近对话") }
                }
                HorizontalDivider()
            }
            if (state.hasMoreSessions) item("more") {
                TextButton(onClick = state::moreSessions, modifier = Modifier.fillMaxWidth()) { Text("加载更多会话") }
            }
        }
    }
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text("共享与保留说明") },
        text = { Text("此手机隐藏的会话可从档案操作恢复，电脑仍保留原记录。电脑删除会话并同步后，手机目录会移除该项；共享服务按原房间策略保留记录。\n\n电脑发布旧记录和目录变更时需要在线。读取已发布的历史需要连接 HTTPS 入口及共享服务；继续发送还需要 AstrBot 在线。离线可读已缓存内容。电脑撤销授权后，手机下一次联系服务时会清理该档案缓存。") },
        confirmButton = { TextButton(onClick = { info = false }) { Text("知道了") } })
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text("解除本机配对？") },
        text = { Text("此手机将清除该档案的绑定及缓存。电脑和共享服务保留原记录。再次连接此档案需要新的档案配对码。") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { forget = false; state.forget() }) { Text("解除配对") } },
        dismissButton = { TextButton(onClick = { forget = false }) { Text("取消") } })
}

@Composable
private fun ComputerConversation(state: AndroidPcHistory, session: ProfileSession, work: CoroutineScope) {
    var menu by remember { mutableStateOf(false) }
    var hide by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val turns = state.turns
    val parts = remember(turns) { computerTranscript(turns) }
    var readingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var readingOffset by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(list) {
        // LazyListState saves an index. Reopening a window may have added turns or removed a gap.
        // Restore the same question/reply fragment when it is still in the window.
        val restored = readingKey?.let { computerReadingIndex(parts, it) }
        if (restored != null) list.scrollToItem(restored, readingOffset)
        snapshotFlow {
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            (first?.key as? String)?.let { it to list.firstVisibleItemScrollOffset }
        }.filterNotNull().collect { (key, offset) ->
            readingKey = key
            readingOffset = offset
        }
    }
    val pending = state.pendingId != null
    val writable = session.reason.isBlank() && !state.busy && !pending
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = state::back) { Text("返回") }
            Column(Modifier.weight(1f)) {
                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text("来自电脑 · " + state.profileName + " · " + computerConnection(state),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "会话操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("立即同步") }, onClick = { menu = false; work.launch { state.refresh() } })
                    DropdownMenuItem(text = { Text("在此手机隐藏") }, onClick = { menu = false; hide = true })
                }
            }
        }
        HorizontalDivider()
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
            contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The stable bottom item keeps the newest reply in view while readers at older turns keep their anchor.
            item("bottom") {
                Column {
                    Spacer(Modifier.height(1.dp))
                    if (!state.connected && state.lastSync > 0L) {
                        Text("目录上次同步 · " + computerTime(state.lastSync), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.notice.isNotBlank()) Text(state.notice, style = MaterialTheme.typography.bodySmall)
                }
            }
            parts.forEach { part ->
                items(part.count, key = { index -> part.id + "/" + (part.count - 1 - index) }) { index ->
                    ComputerTranscriptPiece(part, part.count - 1 - index)
                }
            }
            if (state.hasOlder) item("older") {
                TextButton(onClick = state::older, modifier = Modifier.fillMaxWidth()) { Text("加载更早的记录") }
            }
            if (session.imported < session.total) item("import") {
                Text("电脑旧历史已导入 " + session.imported + " / " + session.total + " 轮。",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (turns.isEmpty()) item("empty") {
                Text(when {
                    state.syncing -> "正在读取会话记录…"
                    !state.connected -> "此手机尚未缓存这段正文。"
                    state.syncComplete && session.total == 0 -> "这段会话暂无记录。"
                    else -> "正在等待会话记录同步。"
                }, Modifier.padding(vertical = 8.dp))
            }
        }
        if (session.reason.isNotBlank()) Text("此会话只读 · " + session.reason, style = MaterialTheme.typography.bodySmall)
        if (pending) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.canRetry) "尚未查到这次发送。" else "正在核对这次发送…",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (state.canRetry) TextButton(enabled = !state.busy && state.connected && session.reason.isBlank(),
                onClick = { work.launch { state.retryPending() } }) { Text("按原编号重试") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.draft, state::editDraft, Modifier.weight(1f), enabled = writable,
                maxLines = 3, label = { Text("消息") })
            Button(enabled = writable && state.connected && state.draft.isNotBlank() && turns.lastOrNull()?.status != "running",
                onClick = { work.launch { state.send() } }) { Text("发送") }
        }
    }
    if (hide) AlertDialog(onDismissRequest = { hide = false }, title = { Text("在此手机隐藏会话？") },
        text = { Text("电脑和共享服务保留这段记录。可在「来自电脑」的档案操作中查看并恢复已隐藏的会话。") },
        confirmButton = { TextButton(onClick = { hide = false; state.hide(session.sid, true) }) { Text("隐藏") } },
        dismissButton = { TextButton(onClick = { hide = false }) { Text("取消") } })
}

private enum class ComputerPartKind { QUESTION, ANSWER, NOTE }

// Some imported replies are several MiB. Compose measures each visible piece on its own.
private data class ComputerPart(val id: String, val kind: ComputerPartKind, val text: String) {
    private val pieceSize = 768
    val count = (text.length - 1) / pieceSize + 1

    private fun boundary(piece: Int): Int {
        if (piece == count) return text.length
        val index = piece * pieceSize
        return if (index > 0 && text[index].isLowSurrogate() && text[index - 1].isHighSurrogate()) index - 1 else index
    }

    fun piece(index: Int) = text.substring(boundary(index), boundary(index + 1))
}

private fun computerTranscript(turns: List<SharedTurn>): List<ComputerPart> = buildList {
    for (i in turns.indices.reversed()) {
        val turn = turns[i]
        if (turn.status != "complete") add(ComputerPart("status-" + turn.id, ComputerPartKind.NOTE,
            if (turn.status == "running") "正在生成回复…" else "回复完成情况未知"))
        if (turn.answer.isNotEmpty()) add(ComputerPart("answer-" + turn.id, ComputerPartKind.ANSWER, turn.answer))
        if (turn.question.isNotEmpty()) add(ComputerPart("question-" + turn.id, ComputerPartKind.QUESTION, turn.question))
        val previous = turns.getOrNull(i - 1) ?: continue
        if (turn.seq - previous.seq > 1) {
            val first = previous.seq + 1
            val last = turn.seq - 1
            val range = if (first == last) first.toString() else "$first–$last"
            add(ComputerPart("gap-" + turn.id, ComputerPartKind.NOTE, "第 ${range} 轮尚未缓存，联网后继续补齐"))
        }
    }
}

private fun computerReadingIndex(parts: List<ComputerPart>, key: String): Int? {
    if (key == "bottom") return 0
    val piece = key.substringAfterLast('/').toIntOrNull() ?: return null
    val group = key.substringBeforeLast('/')
    // A completed reply or backfilled gap replaces its transient label. Keep its adjacent message in view.
    val adjacent = when {
        group.startsWith("status-") -> "answer-" + group.removePrefix("status-")
        group.startsWith("gap-") -> "question-" + group.removePrefix("gap-")
        else -> null
    }
    var index = 1 // bottom/status item
    var nearby: Int? = null
    for (part in parts) {
        if (part.id == group) return index + part.count - 1 - piece.coerceIn(0, part.count - 1)
        if (part.id == adjacent) nearby = if (group.startsWith("gap-")) index + part.count - 1 else index
        index += part.count
    }
    return nearby
}

@Composable
private fun ComputerTranscriptPiece(part: ComputerPart, piece: Int) {
    val value = part.piece(piece)
    when (part.kind) {
        ComputerPartKind.NOTE -> Text(value, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ComputerPartKind.ANSWER -> SelectionContainer { Text(value, Modifier.padding(horizontal = 8.dp)) }
        ComputerPartKind.QUESTION -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                SelectionContainer { Text(value, Modifier.padding(12.dp)) }
            }
        }
    }
}
