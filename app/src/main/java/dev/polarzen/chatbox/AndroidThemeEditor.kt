package dev.polarzen.chatbox

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun AndroidThemeEditor(saved: ChatTheme, syncNote: String, onApply: (ChatTheme) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(saved) }
    var draft by remember { mutableStateOf(saved) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val changedElsewhere = saved != base && draft != saved
    LaunchedEffect(saved) {
        if (draft == base || draft == saved) { base = saved; draft = saved }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            error = ""
            try {
                val bytes = withContext(Dispatchers.IO) {
                    Base64.encodeToString(importThemePhoto(context, uri), Base64.NO_WRAP)
                }
                draft = draft.copy(wallpaper = bytes)
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = e.localizedMessage ?: "无法打开图片。" }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("主题", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) { Text("返回") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((dark, label) in listOf(false to "浅色", true to "深色")) {
                FilterChip(selected = draft.dark == dark, onClick = { draft = draft.copy(dark = dark) },
                    label = { Text(label) })
            }
        }
        Text("主题色", style = MaterialTheme.typography.titleMedium)
        ChatColorPicker(draft.accent, draft.dark) {
            draft = draft.copy(accent = it)
        }
        Text("预览", style = MaterialTheme.typography.titleMedium)
        AndroidChatTheme(draft) { ThemeConversationPreview() }
        Text("聊天背景", style = MaterialTheme.typography.titleMedium)
        Text("已配对设备会同步主题和所选背景图片。", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pick.launch("image/*") }, enabled = !loading && !saving) {
                Text(if (loading) "正在读取…" else if (draft.wallpaper == null) "选择图片" else "更换图片")
            }
            if (draft.wallpaper != null) TextButton(enabled = !loading && !saving, onClick = {
                draft = draft.copy(wallpaper = null)
            }) { Text("移除图片") }
        }
        if (draft.wallpaper != null) {
            Text("背景模糊  ${draft.blur}%", style = MaterialTheme.typography.labelLarge)
            Slider(draft.blur.toFloat(), { draft = draft.copy(blur = it.roundToInt()) },
                valueRange = 0f..100f, modifier = Modifier.semantics { contentDescription = "背景模糊" })
        }
        if (syncNote.isNotBlank()) Text(syncNote, style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (changedElsewhere) {
            Text("主题已在另一台设备更改。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { base = saved; draft = saved }) { Text("载入最新主题") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !saving && !loading, onClick = { draft = ChatTheme(); error = "" }) { Text("恢复默认") }
            Button(enabled = draft != saved && !loading && !saving, onClick = {
                val applied = draft
                saving = true
                error = ""
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { onApply(applied) }
                        base = applied
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { error = e.localizedMessage ?: "主题未保存。" }
                    finally { saving = false }
                }
            }) { Text(if (changedElsewhere) "使用当前预览" else "应用主题") }
        }
    }
}

@Composable
private fun ThemeConversationPreview() {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.background,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
        Column {
            Surface(color = colors.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
                        Surface(Modifier.size(8.dp), shape = RoundedCornerShape(50), color = colors.primary) {}
                    }
                    Text("对话", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            Box(Modifier.fillMaxWidth()) {
                AndroidThemeWallpaper(Modifier.matchParentSize())
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(Modifier.align(Alignment.End), shape = RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp),
                        color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                        Text("换个设备继续聊。", Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    Surface(shape = RoundedCornerShape(3.dp, 12.dp, 12.dp, 12.dp),
                        color = colors.surface, contentColor = colors.onSurface) {
                        Text("好，我们接着说。", Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}
