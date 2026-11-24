package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme as SharedMaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// DesktopAppearance uses a light default with a purple accent and a separate chat canvas.
private val lightAppearance = lightColorScheme(
    background = Color(0xFFF7F6FA), surface = Color.White,
    onBackground = Color(0xFF292735), onSurface = Color(0xFF292735), onSurfaceVariant = Color(0xFF615E6E),
    outlineVariant = Color(0xFFE0DCE7), primary = Color(0xFF6745B4), secondary = Color(0xFF6745B4),
    primaryContainer = Color(0xFFF0EBFB), onPrimaryContainer = Color(0xFF292735),
    onPrimary = Color.White, surfaceVariant = Color(0xFFECE9F1),
    surfaceContainer = Color(0xFFF7F6FA), surfaceContainerLow = Color.White,
)
private val darkAppearance = darkColorScheme(
    background = Color(0xFF1B1922), surface = Color(0xFF25222E),
    onBackground = Color(0xFFF4F1F7), onSurface = Color(0xFFF4F1F7), onSurfaceVariant = Color(0xFFC5BFCE),
    outlineVariant = Color(0xFF514C5D), primary = Color(0xFFC6ABFF), secondary = Color(0xFFC6ABFF),
    primaryContainer = Color(0xFF3C304D), onPrimaryContainer = Color(0xFFF4F1F7),
    onPrimary = Color(0xFF28173D), surfaceVariant = Color(0xFF373240),
    surfaceContainer = Color(0xFF25222E), surfaceContainerLow = Color(0xFF25222E),
)

@Composable
internal fun AndroidChatTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkAppearance else lightAppearance
    val shared = if (dark) darkColors() else lightColors()
    SharedMaterialTheme(colors = shared.copy(
        primary = colors.primary, secondary = colors.primary,
        background = colors.background, surface = colors.surface, onPrimary = colors.onPrimary,
        onSecondary = colors.onPrimary, onBackground = colors.onBackground, onSurface = colors.onSurface,
    )) {
        MaterialTheme(colorScheme = colors) {
            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(colors.primary,
                    if (dark) Color(0xFF50456B) else Color(0xFFACC5FF)), content = content)
        }
    }
}

@Composable
internal fun AndroidAppearanceScreen(
    dark: Boolean, onDark: (Boolean) -> Unit, notify: Boolean, onNotify: (Boolean) -> Unit,
    systemAllowed: Boolean, systemSettings: () -> Unit, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("窗口外观", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("返回") }
        }
        Text("主题", style = MaterialTheme.typography.titleMedium)
        listOf(false to "浅色", true to "深色").forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().selectable(dark == value, role = Role.RadioButton,
                onClick = { onDark(value) }).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioButton(selected = dark == value, onClick = null)
                Text(label)
            }
        }
        TextButton(onClick = { onDark(false) }) { Text("恢复默认外观") }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().toggleable(notify, role = Role.Switch, onValueChange = onNotify)
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("后台回复通知", Modifier.weight(1f))
            Switch(checked = notify, onCheckedChange = null)
        }
        if (notify && !systemAllowed) {
            Text("系统通知已关闭。", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = systemSettings) { Text("打开系统通知设置") }
        }
    }
}
