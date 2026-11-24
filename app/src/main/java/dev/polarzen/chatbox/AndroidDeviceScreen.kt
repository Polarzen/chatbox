package dev.polarzen.chatbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

/** Multiple profiles with the same address are independent when their owners differ. */
internal fun defaultDeviceProfile(available: List<DeviceProfile>, lastProfile: String?,
                                  nativeBase: String, nativeUser: String): DeviceProfile? {
    return available.firstOrNull { it.profile == lastProfile }
        ?: available.singleOrNull { it.base == nativeBase && it.username == nativeUser }
        ?: available.singleOrNull()
}

@Composable
internal fun AndroidRecentScreen(app: AndroidAppState, onOpen: (AndroidDeviceRow) -> Unit,
                                onCreate: () -> Unit, settings: () -> Unit) {
    val device = app.device
    // Directory derivation can scan many profiles. Keep it outside composition and off
    // the UI dispatcher; only the published, sorted snapshot is consumed by the list.
    val rows by produceState(emptyList<AndroidDeviceRow>(), app) {
        snapshotFlow {
            val shared = device.rows
            val local = (app.connections.services.map { it.base } + "").distinct().flatMap { base ->
                app.history.sessions(base).mapNotNull { session ->
                    val draft = session.draft
                    val localDraft = session.turns.isEmpty() && (draft.text.isNotBlank() || draft.attachments.isNotEmpty())
                    if ((!localDraft && (base.isNotEmpty() || session.turns.isEmpty())) ||
                        shared.any { it.nativeBase == base && it.sid == session.id }) return@mapNotNull null
                    val title = if (localDraft) "草稿 · " + draft.text.lineSequence().first().take(48).ifBlank { "附件" }
                        else session.title
                    AndroidDeviceRow("phone-only:$base", session.id, title,
                        session.turns.lastOrNull()?.createdAt ?: 0L,
                        app.connections.services.firstOrNull { it.base == base }?.name ?: "本机",
                        app.history.user, base, "")
                }
            }
            if (local.isEmpty()) shared else (shared + local).sortedWith(
                compareByDescending<AndroidDeviceRow> { it.activity }.thenBy { it.profile }.thenBy { it.sid })
        }.flowOn(Dispatchers.IO).collect { value = it }
    }
    var hide by remember { mutableStateOf<AndroidDeviceRow?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("最近对话", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = settings) { Text("设备设置") }
        }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("＋ 新建会话") }
        if (device.bound && !device.online) Text("离线 · 显示本机缓存",
            Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        if (device.notice.isNotBlank()) Text(device.notice, Modifier.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (device.syncing) "正在读取会话…" else "暂无对话。新建会话后即可开始聊天。")
            }
        } else LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(rows, key = { "${it.profile.length}:${it.profile}:${it.sid}" }) { row ->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button) { onOpen(row) }
                        .padding(horizontal = 4.dp, vertical = 12.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(row.source, row.username).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (device.bound && !row.profile.startsWith("phone-only:")) Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, "会话 ${row.title} 菜单")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("在本机隐藏") }, onClick = { menu = false; hide = row })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
    hide?.let { row ->
        AlertDialog(onDismissRequest = { hide = null }, title = { Text("在这台手机上隐藏会话？") },
            text = { Text("“${row.title}”将从本机最近对话中隐藏，电脑上的历史会保留。") },
            confirmButton = { TextButton(onClick = { device.hide(row); hide = null }) { Text("仅在本机隐藏") } },
            dismissButton = { TextButton(onClick = { hide = null }) { Text("取消") } })
    }
}

@Composable
internal fun DeviceProfileChooser(profiles: List<DeviceProfile>, onSelect: (DeviceProfile) -> Unit,
                                  onDismiss: () -> Unit, onSettings: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择会话所属档案") },
        text = {
            if (profiles.isEmpty()) Text("还没有可用于发送的档案。请在电脑上连接 AstrBot 并开启共享，再回到这里新建。")
            else LazyColumn {
                items(profiles, key = { it.profile }) { profile ->
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(profile) }
                        .padding(vertical = 10.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text("${profile.base} · ${profile.username}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = {
            if (profiles.isEmpty()) TextButton(onClick = onSettings) { Text("设备设置") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
