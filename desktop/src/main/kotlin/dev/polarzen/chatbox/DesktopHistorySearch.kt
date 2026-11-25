package dev.polarzen.chatbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopHistorySearch(
    store: DesktopStore,
    profileId: String,
    revision: Int,
    overrides: Map<String, Chat>,
    onDismiss: () -> Unit,
    onSelectHit: (HistoryHit, String) -> Boolean,
    partnerName: (String) -> String = { "助手" },
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val inset = if (maxWidth < 500.dp) 8.dp else 24.dp
            Surface(
                modifier = Modifier.padding(inset).widthIn(max = 680.dp).fillMaxWidth()
                    .heightIn(max = 580.dp).fillMaxHeight().testTag("history-search")
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape -> { onDismiss(); true }
                            event.key == Key.K && event.isCtrlPressed -> { focus.requestFocus(); true }
                            else -> false
                        }
                    },
                shape = RoundedCornerShape(14.dp),
                elevation = 8.dp,
            ) {
                Column(Modifier.padding(inset)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索聊天记录", Modifier.weight(1f), style = MaterialTheme.typography.h6)
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("history-search-cancel")) {
                            Text("取消")
                        }
                    }
                    OutlinedTextField(
                        query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("history-search-query"),
                        singleLine = true,
                        placeholder = { Text("搜索会话标题和消息") },
                        trailingIcon = if (query.isEmpty()) null else {
                            {
                                TextButton(
                                    onClick = { query = ""; focus.requestFocus() },
                                    modifier = Modifier.testTag("history-search-clear"),
                                ) { Text("清空") }
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    when {
                        profileId.isBlank() -> Text("请先选择服务档案。")
                        query.isBlank() -> Text("输入关键词以搜索当前服务档案。")
                        else -> HistoryResults(store, profileId, revision, overrides, query, partnerName) { hit ->
                            onSelectHit(hit, query).also { if (it) onDismiss() }
                        }
                    }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
    }
}

@Composable
private fun HistoryResults(
    store: DesktopStore,
    profileId: String,
    revision: Int,
    overrides: Map<String, Chat>,
    query: String,
    partnerName: (String) -> String,
    onSelect: (HistoryHit) -> Boolean,
) {
    var refresh by remember(store, profileId) { mutableIntStateOf(0) }
    var snapshot by remember(store, profileId, revision, refresh) { mutableStateOf<HistorySearchSnapshot?>(null) }
    var failed by remember(store, profileId, revision, refresh) { mutableStateOf(false) }
    LaunchedEffect(store, profileId, revision, refresh) {
        try {
            snapshot = withContext(Dispatchers.Default) {
                store.historySearchSnapshot(profileId) { !isActive }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            failed = true
        } catch (_: org.json.JSONException) {
            failed = true
        }
    }
    val base = snapshot
    var hits by remember(store, profileId, query) {
        mutableStateOf<List<HistoryHit>?>(null, referentialEqualityPolicy())
    }
    var stale by remember(store, profileId, query) { mutableStateOf(false) }
    LaunchedEffect(base, query, overrides) {
        if (base != null) {
            hits = withContext(Dispatchers.Default) { base.search(query, overrides) { !isActive } }
        }
    }
    val results = hits
    when {
        failed -> Text("无法读取此档案的聊天记录。")
        results == null -> Text("正在搜索…")
        results.isEmpty() -> Text("没有找到匹配的聊天记录。", Modifier.testTag("history-search-empty"))
        else -> {
            if (stale) Text("此结果已变更，请重新选择。", color = MaterialTheme.colors.error)
            Text("找到 ${results.size} 处匹配", style = MaterialTheme.typography.caption)
            key(profileId, query) {
                LazyColumn(Modifier.fillMaxWidth().testTag("history-search-results")) {
                    items(results, key = { "${it.sessionId}:${it.entryIndex}:${it.side}:${it.start}" }) { hit ->
                        val source = when (hit.side) {
                            HistorySide.TITLE -> "会话标题"
                            HistorySide.USER -> "第 ${hit.entryIndex + 1} 轮 · 用户"
                            HistorySide.ASSISTANT -> "第 ${hit.entryIndex + 1} 轮 · ${partnerName(hit.sessionId)}"
                        }
                        Column(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                if (!onSelect(hit)) {
                                    stale = true
                                    refresh++
                                }
                            }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Text(hit.sessionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold)
                            Text(source, style = MaterialTheme.typography.caption)
                            val highlight = SpanStyle(
                                background = desktopPalette.searchBackground,
                                color = desktopPalette.searchInk,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(buildAnnotatedString {
                                append(hit.excerpt)
                                addStyle(highlight, hit.excerptStart, hit.excerptStart + (hit.endExclusive - hit.start))
                            })
                        }
                        Divider()
                    }
                }
            }
        }
    }
}
