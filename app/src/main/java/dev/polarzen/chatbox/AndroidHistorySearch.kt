package dev.polarzen.chatbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class MobileHistorySide { TITLE, USER, ASSISTANT }

internal data class MobileHistoryHit(
    val sessionId: String,
    val sessionTitle: String,
    val turnId: String?,
    val round: Int,
    val side: MobileHistorySide,
    val query: String,
    val start: Int,
    val excerpt: String,
    val excerptStart: Int,
) {
    fun stillMatches(session: MobileSession): Boolean {
        if (session.id != sessionId) return false
        val turn = session.turns.getOrNull(round - 1)?.takeIf { it.id == turnId }
            ?: session.turns.firstOrNull { it.id == turnId }
        val source = when (side) {
            MobileHistorySide.TITLE -> session.title
            MobileHistorySide.USER -> turn?.question
            MobileHistorySide.ASSISTANT -> turn?.answer
        }
        return source?.regionMatches(start, query, 0, query.length, ignoreCase = true) == true
    }
}

/** Same literal, case-insensitive, overlapping matches and UTF-16 offsets as PC history. */
internal fun mobileHistoryHits(
    sessions: List<MobileSession>,
    query: String,
    checkActive: () -> Unit = {},
): List<MobileHistoryHit> = buildList {
    if (query.isBlank()) return@buildList
    for (session in sessions) {
        fun collect(source: String, turnId: String?, round: Int, side: MobileHistorySide) {
            checkActive()
            for (start in 0..source.length - query.length) {
                if (start % 1024 == 0) checkActive()
                if (!source.regionMatches(start, query, 0, query.length, ignoreCase = true)) continue
                val end = start + query.length
                var left = (start - 28).coerceAtLeast(0)
                var right = end + (source.length - end).coerceAtMost(28)
                if (left > 0 && source[left].isLowSurrogate()) left--
                if (right < source.length && source[right].isLowSurrogate()) right++
                val excerpt = buildString {
                    if (left > 0) append('…')
                    append(source, left, right)
                    if (right < source.length) append('…')
                }
                add(MobileHistoryHit(session.id, session.title, turnId, round, side, query, start,
                    excerpt, start - left + (if (left > 0) 1 else 0)))
            }
        }
        collect(session.title, null, 0, MobileHistorySide.TITLE)
        session.turns.forEachIndexed { i, turn ->
            collect(turn.question, turn.id, i + 1, MobileHistorySide.USER)
            collect(turn.answer, turn.id, i + 1, MobileHistorySide.ASSISTANT)
        }
    }
}

internal fun mobileHighlightedText(text: String, start: Int, length: Int) = buildAnnotatedString {
    append(text)
    if (start >= 0 && length in 1..text.length - start) {
        addStyle(SpanStyle(background = Color(0xFFFFDA71), color = Color(0xFF342203),
            fontWeight = FontWeight.SemiBold), start, start + length)
    }
}

@Composable
internal fun AndroidHistorySearch(
    sessions: List<MobileSession>,
    onDismiss: () -> Unit,
    onHit: (MobileHistoryHit) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    var stale by remember(query) { mutableStateOf(false) }
    var hits by remember(query) { mutableStateOf<List<MobileHistoryHit>?>(null) }
    LaunchedEffect(sessions, query) {
        hits = withContext(Dispatchers.Default) { mobileHistoryHits(sessions, query) { ensureActive() } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp),
            contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().heightIn(max = 560.dp)
                    .fillMaxHeight().onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) false
                        else if (it.key == Key.Escape) { onDismiss(); true }
                        else if (it.key == Key.K && it.isCtrlPressed) { focus.requestFocus(); true }
                        else false
                    },
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索聊天记录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("history-search-query"),
                        placeholder = { Text("搜索会话标题和消息") },
                        trailingIcon = if (query.isEmpty()) null else ({
                            TextButton(onClick = { query = ""; focus.requestFocus() }) { Text("清空") }
                        }))
                    Spacer(Modifier.height(8.dp))
                    val results = hits
                    when {
                        query.isBlank() -> Text("输入关键词以搜索当前服务档案。")
                        results == null -> Text("正在搜索…")
                        results.isEmpty() -> Text("没有找到匹配的聊天记录。")
                        else -> {
                            if (stale) Text("此结果已变更，请重新选择。", color = MaterialTheme.colorScheme.error)
                            Text("找到 ${results.size} 处匹配", style = MaterialTheme.typography.labelMedium)
                            key(query) {
                                LazyColumn(Modifier.weight(1f)) {
                                    items(results, key = { "${it.sessionId}/${it.turnId}/${it.side}/${it.start}" }) { hit ->
                                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                            if (sessions.find { it.id == hit.sessionId }?.let(hit::stillMatches) == true)
                                                onHit(hit)
                                            else stale = true
                                        }.padding(vertical = 10.dp)) {
                                            Text(hit.sessionTitle, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val label = when (hit.side) {
                                                MobileHistorySide.TITLE -> "会话标题"
                                                MobileHistorySide.USER -> "第 ${hit.round} 轮 · 用户"
                                                MobileHistorySide.ASSISTANT -> "第 ${hit.round} 轮 · 助手"
                                            }
                                            Text(label, style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(mobileHighlightedText(hit.excerpt, hit.excerptStart, hit.query.length))
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
