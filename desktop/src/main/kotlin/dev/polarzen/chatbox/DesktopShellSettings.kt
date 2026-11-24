package dev.polarzen.chatbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class DesktopShellSettings(
    val hotkeyEnabled: Boolean = false,
    val hotkey: DesktopHotkey = DesktopHotkey.DEFAULT,
    val closeToTray: Boolean = false,
    val notifyReplies: Boolean = true,
)

internal class DesktopShellSettingsStore(private val dataDir: Path) {
    private val file = dataDir.resolve("shell.json")

    fun load(): DesktopShellSettings {
        val defaults = DesktopShellSettings()
        val json = try {
            JSONObject(Files.readString(file))
        } catch (_: IOException) {
            return defaults
        } catch (_: JSONException) {
            return defaults
        }
        return DesktopShellSettings(
            hotkeyEnabled = json.opt("hotkeyEnabled") as? Boolean ?: defaults.hotkeyEnabled,
            hotkey = (json.opt("hotkey") as? String)?.let { DesktopHotkey.parse(it) } ?: defaults.hotkey,
            closeToTray = json.opt("closeToTray") as? Boolean ?: defaults.closeToTray,
            notifyReplies = json.opt("notifyReplies") as? Boolean ?: defaults.notifyReplies,
        )
    }

    fun save(settings: DesktopShellSettings) {
        val json = JSONObject()
            .put("hotkeyEnabled", settings.hotkeyEnabled)
            .put("hotkey", settings.hotkey.toString())
            .put("closeToTray", settings.closeToTray)
            .put("notifyReplies", settings.notifyReplies)
        Files.createDirectories(dataDir)
        val temp = Files.createTempFile(dataDir, "shell-", ".tmp")
        try {
            Files.writeString(temp, json.toString())
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

@Composable
internal fun DesktopShellSettingsDialog(
    current: DesktopShellSettings,
    trayAvailable: Boolean,
    registeredKey: DesktopHotkey?,
    onSave: (DesktopShellSettings) -> String?,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    var shortcut by remember { mutableStateOf(current.hotkey.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = DesktopHotkey.parse(shortcut.trim())
    val invalid = draft.hotkeyEnabled && parsed == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("桌面设置") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                ShellSwitch("全局快捷键", "shell-hotkey-enabled", draft.hotkeyEnabled) {
                    draft = draft.copy(hotkeyEnabled = it)
                    error = null
                }
                OutlinedTextField(
                    value = shortcut,
                    onValueChange = { shortcut = it; error = null },
                    enabled = draft.hotkeyEnabled,
                    singleLine = true,
                    isError = invalid,
                    label = { Text("显示或隐藏窗口") },
                    modifier = Modifier.fillMaxWidth().testTag("shell-hotkey"),
                )
                if (invalid) Text("请输入组合键，例如 ${DesktopHotkey.DEFAULT}。", color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption)
                ShellSwitch("关闭窗口时收起到托盘", "shell-close-to-tray", draft.closeToTray && trayAvailable,
                    enabled = trayAvailable) {
                    draft = draft.copy(closeToTray = it)
                    error = null
                }
                if (!trayAvailable) Text("系统托盘不可用，关闭窗口时将退出。", style = MaterialTheme.typography.caption)
                ShellSwitch("后台回复完成时提醒", "shell-notify-replies", draft.notifyReplies) {
                    draft = draft.copy(notifyReplies = it)
                    error = null
                }
                error?.let {
                    Text(it, color = MaterialTheme.colors.error, modifier = Modifier.testTag("shell-save-error"))
                }
                if (error != null || registeredKey != current.hotkey.takeIf { current.hotkeyEnabled }) {
                    Text("当前快捷键：${registeredKey ?: "已关闭"}", style = MaterialTheme.typography.caption)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !invalid,
                modifier = Modifier.testTag("shell-save"),
                onClick = {
                    error = onSave(draft.copy(hotkey = parsed ?: current.hotkey))
                    if (error == null) onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ShellSwitch(label: String, tag: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(tag)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
