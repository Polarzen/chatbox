package dev.polarzen.chatbox

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/** The same route decides which preference owner supplies the chat header and its list row. */
internal fun AndroidDeviceRow.localChatBase(bound: Boolean): String? =
    nativeBase?.takeIf { !bound || profile.startsWith("phone-only:") }

internal data class AndroidRecentText(val peerName: String, val topic: String?) {
    companion object {
        fun from(preferences: ConversationPreferences, title: String): AndroidRecentText {
            val peer = preferences.effectiveName
            return AndroidRecentText(peer, title.takeIf { it.isNotBlank() && it != peer })
        }
    }
}

internal fun initialChatRow(rows: List<AndroidDeviceRow>, kind: String?, owner: String?, sid: String?,
                            localAvailable: Boolean, currentBase: String, currentSid: String): AndroidDeviceRow? {
    if (kind == "local" && localAvailable && owner != null && sid != null)
        return AndroidDeviceRow("phone-only:$owner", sid, "", 0, "", "", owner, "")
    return rows.firstOrNull { kind == "device" && it.profile == owner && it.sid == sid }
        ?: rows.firstOrNull { it.nativeBase == currentBase && it.sid == currentSid } ?: rows.firstOrNull()
}

internal fun androidRecentRows(app: AndroidAppState): List<AndroidDeviceRow> {
    val shared = app.device.rows
    val local = (app.connections.services.map { it.base } + "").distinct().flatMap { base ->
        app.history.sessions(base).mapNotNull { session ->
            val draft = session.draft
            val localDraft = session.turns.isEmpty() && (draft.text.isNotBlank() || draft.attachments.isNotEmpty())
            if ((!localDraft && (base.isNotEmpty() || session.manualName == null && session.turns.isEmpty())) ||
                shared.any { it.nativeBase == base && it.sid == session.id }) return@mapNotNull null
            val title = if (localDraft) "草稿 · " + draft.text.lineSequence().first().take(48).ifBlank { "附件" }
                else session.title
            AndroidDeviceRow("phone-only:$base", session.id, title,
                session.turns.lastOrNull()?.createdAt ?: 0L,
                app.connections.services.firstOrNull { it.base == base }?.name ?: "本机",
                app.history.user, base, "")
        }
    }
    return if (local.isEmpty()) shared else (shared + local).sortedWith(
        compareByDescending<AndroidDeviceRow> { it.activity }.thenBy { it.profile }.thenBy { it.sid })
}

@Composable
internal fun AndroidRecentScreen(app: AndroidAppState, onOpen: (AndroidDeviceRow) -> Unit,
                                onCreate: () -> Unit, active: (AndroidDeviceRow) -> Boolean = { false },
                                modifier: Modifier = Modifier.fillMaxSize().systemBarsPadding(),
                                theme: () -> Unit, settings: () -> Unit) {
    val device = app.device
    val rows by produceState<List<AndroidDeviceRow>?>(null, app) {
        snapshotFlow { androidRecentRows(app) }.flowOn(Dispatchers.IO).collect { value = it }
    }
    var hide by remember { mutableStateOf<AndroidDeviceRow?>(null) }
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("最近对话", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onCreate) { Icon(Icons.Outlined.Add, "新建会话") }
            IconButton(onClick = theme) { Icon(painterResource(R.drawable.chat_theme), "主题") }
            IconButton(onClick = settings) { Icon(painterResource(R.drawable.chat_settings), "设备设置") }
        }
        if (device.bound && !device.online) Text("离线 · 显示本机缓存",
            Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        if (device.notice.isNotBlank()) Text(device.notice, Modifier.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (rows.isNullOrEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (rows == null || device.syncing) Text("正在读取会话…") else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无对话", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onCreate) { Text("新建会话") }
                }
            }
        } else LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(rows.orEmpty(), key = { "${it.profile.length}:${it.profile}:${it.sid}" }) { row ->
                var menu by remember { mutableStateOf(false) }
                val isCurrent = active(row)
                val base = row.localChatBase(device.bound)
                // Only visible rows observe their owner. Preference edits and sync publish revisions,
                // while SQLite reads run off the UI thread and never open a connection to the server.
                val label by key(device, row.profile, row.sid, base, row.nativeBase) {
                    val title by rememberUpdatedState(row.title)
                    produceState<AndroidRecentText?>(null) {
                        snapshotFlow {
                            val handle = if (base != null) device.preferences(base, row.sid) else device.preferences(row)
                            AndroidRecentText.from(handle.value, title)
                        }.flowOn(Dispatchers.IO).collect { value = it }
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .semantics { selected = isCurrent }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button) { onOpen(row) }
                        .padding(horizontal = 4.dp, vertical = 12.dp)) {
                        Text(label?.peerName.orEmpty(), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val topic = if (label == null) row.title else label?.topic
                        if (!topic.isNullOrBlank()) Text(topic, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(row.source, row.username).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (device.bound && !row.profile.startsWith("phone-only:")) Box {
                        IconButton(onClick = { menu = true }) {
                            val name = label?.let { listOfNotNull(it.peerName, it.topic).joinToString(" · ") } ?: row.title
                            Icon(painterResource(R.drawable.chat_more), "会话 $name 菜单")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("在本机隐藏") }, onClick = { menu = false; hide = row })
                        }
                    }
                }
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
