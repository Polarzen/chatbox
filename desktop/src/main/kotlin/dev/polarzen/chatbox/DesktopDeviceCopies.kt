package dev.polarzen.chatbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopDeviceCopies(
    store: DesktopStore, selected: SessionKey, revision: Long, images: ReplyImageCache, audio: ReplyAudioCache,
    onSelect: (SessionKey) -> Unit,
) {
    if (selected.sessionId.isEmpty()) return
    val related = remember(store, selected, revision) { store.deviceRelatedKeys(selected) }
    val original = remember(store, selected, revision) { store.deviceOriginal(selected) }
    if (related.size < 2 && original == null) return
    var copiesOpen by remember(selected) { mutableStateOf(false) }
    var originalOpen by remember(selected) { mutableStateOf(false) }
    TextButton(onClick = { if (related.size > 1) copiesOpen = true else originalOpen = true }) {
        Text(if (related.size > 1) "档案副本" else "本机原文")
    }
    DesktopModalGuard(copiesOpen || originalOpen)
    if (copiesOpen) {
        AlertDialog(onDismissRequest = { copiesOpen = false }, title = { Text("此会话的电脑档案") },
            text = { Column {
                Text("这些档案已确认属于同一共享会话。选择档案可查看该档案的草稿和本机附件。")
                related.forEach { key ->
                    val profile = store.profile(key.profileId)
                    val name = profile?.let(::profileDisplayName) ?: key.profileId
                    TextButton(onClick = { copiesOpen = false; onSelect(key) }) {
                        Text((if (selected == key) "当前 · " else "") + name + " · " + key.sessionId.take(8))
                    }
                }
                if (original != null) TextButton(onClick = { copiesOpen = false; originalOpen = true }) {
                    Text("查看此档案的本机原文")
                }
            } }, confirmButton = { TextButton(onClick = { copiesOpen = false }) { Text("关闭") } })
    }
    if (originalOpen && original != null) {
        AlertDialog(onDismissRequest = { originalOpen = false }, title = { Text("此档案的本机原文") },
            text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("此处保留合并前的本机记录，供只读查看。普通聊天输入框会沿共享会话的正文续聊。") }
                items(original.entries) { entry ->
                    Column {
                        DesktopStoredAttachments(entry)
                        if (entry.question.isNotBlank()) SelectionContainer { Text("我：" + entry.question) }
                        SelectionContainer { DesktopReplyContent(selected.profileId, selected.sessionId, entry.answer,
                            entry.replyAttachments, images, audio, emptyMap(), emptyMap(), emptySet(), false, {}) }
                        Text(entry.status, style = MaterialTheme.typography.caption)
                    }
                    Divider()
                }
            } }, confirmButton = { TextButton(onClick = { originalOpen = false }) { Text("关闭") } })
    }
}
