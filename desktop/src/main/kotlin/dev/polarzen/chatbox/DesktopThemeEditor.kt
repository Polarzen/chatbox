package dev.polarzen.chatbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.awt.Window
import java.io.IOException
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun DesktopThemeEditor(
    state: DesktopThemeState,
    window: Window?,
    onDismiss: () -> Unit,
) {
    DesktopModalGuard()
    val original = remember { state.theme }
    var draft by remember { mutableStateOf(original) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("主题") },
        text = {
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("深色外观", Modifier.weight(1f))
                    Switch(checked = draft.dark, enabled = !busy, onCheckedChange = { draft = draft.copy(dark = it) })
                }
                ChatColorPicker(draft.accent, draft.dark) { draft = draft.copy(accent = it) }
                Text("聊天背景", style = MaterialTheme.typography.subtitle2)
                Row {
                    OutlinedButton(enabled = !busy, onClick = {
                        val chooser = JFileChooser().apply {
                            dialogTitle = "选择聊天背景图片"
                            fileFilter = FileNameExtensionFilter("图片（JPG、PNG、GIF、BMP）", "jpg", "jpeg", "png", "gif", "bmp")
                        }
                        if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
                            val selected = chooser.selectedFile
                            busy = true
                            scope.launch {
                                try {
                                    val encoded = withContext(Dispatchers.IO) { desktopWallpaper(selected) }
                                    draft = draft.copy(wallpaper = encoded)
                                    error = ""
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.localizedMessage ?: "无法读取此图片。" }
                                finally { busy = false }
                            }
                        }
                    }) { Text(if (draft.wallpaper == null) "选择图片" else "更换图片") }
                    if (draft.wallpaper != null) TextButton(enabled = !busy, onClick = {
                        draft = draft.copy(wallpaper = null, blur = 0)
                    }) { Text("移除图片") }
                }
                Text("背景模糊  ${draft.blur}%")
                Slider(value = draft.blur.toFloat(), onValueChange = { draft = draft.copy(blur = it.roundToInt()) },
                    valueRange = 0f..100f, enabled = !busy && draft.wallpaper != null,
                    modifier = Modifier.fillMaxWidth().testTag("theme-background-blur"))
                Text("已配对设备会同步主题和所选背景图片。", style = MaterialTheme.typography.caption)
                Text("预览", style = MaterialTheme.typography.subtitle2)
                DesktopTheme(draft.dark, draft) {
                    Box(Modifier.fillMaxWidth().height(142.dp).clip(RoundedCornerShape(12.dp))
                        .background(desktopPalette.canvas).testTag("theme-preview")) {
                        DesktopThemeBackground(draft)
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                            Surface(shape = RoundedCornerShape(11.dp), color = desktopPalette.accentSurface,
                                modifier = Modifier.align(Alignment.End)) {
                                Text("换个主题看看", Modifier.padding(8.dp), color = desktopPalette.ink)
                            }
                            Surface(shape = RoundedCornerShape(11.dp), color = desktopPalette.panel) {
                                Text("好，就用这个。", Modifier.padding(8.dp), color = desktopPalette.ink)
                            }
                        }
                    }
                }
                TextButton(enabled = !busy, onClick = { draft = ChatTheme() }) { Text("恢复默认主题") }
                (error.ifBlank { state.issue }).takeIf(String::isNotEmpty)?.let {
                    Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
        confirmButton = { Button(enabled = !busy, onClick = {
            busy = true
            val selected = draft
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        synchronized(state) {
                            if (state.theme != original && state.theme != selected)
                                throw IOException("另一台设备已修改主题，请关闭后重新打开。")
                            state.applyTheme(selected)
                        }
                    }
                    onDismiss()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.localizedMessage ?: "主题保存失败，请重试。" }
                finally { busy = false }
            }
        }) { Text("应用") } },
    )
}
