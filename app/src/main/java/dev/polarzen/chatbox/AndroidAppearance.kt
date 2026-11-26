package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme as SharedMaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
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

internal val LocalChatTheme = staticCompositionLocalOf { ChatTheme() }

private fun androidThemeColors(theme: ChatTheme): androidx.compose.material3.ColorScheme {
    val dark = theme.dark
    val accent = theme.accent
    val base = if (dark) darkAppearance else lightAppearance
    if (accent == null) return base
    val raw = Color(android.graphics.Color.parseColor(accent))
    fun tint(value: Color, amount: Float) = lerp(value, raw, amount)
    val surface = tint(if (dark) Color(0xff202025) else Color.White, .045f)
    val canvas = tint(if (dark) Color(0xff18181b) else Color(0xfff9f9fa), .09f)
    val ink = base.onSurface
    val palette = chatThemeColors(theme)
    val primary = palette.accent
    val onPrimary = palette.onAccent
    val container = palette.accentSurface
    return base.copy(primary = primary, onPrimary = onPrimary, primaryContainer = container,
        onPrimaryContainer = ink, secondary = primary, onSecondary = onPrimary,
        secondaryContainer = container, onSecondaryContainer = ink, tertiary = primary, onTertiary = onPrimary,
        tertiaryContainer = container, onTertiaryContainer = ink, surfaceTint = primary,
        surface = surface, background = canvas, surfaceVariant = tint(surface, .12f),
        surfaceContainer = canvas, surfaceContainerLowest = surface, surfaceContainerLow = surface,
        surfaceContainerHigh = tint(surface, .09f), surfaceContainerHighest = tint(surface, .14f),
        outlineVariant = tint(base.outlineVariant, .18f))
}

@Composable
internal fun AndroidChatTheme(theme: ChatTheme, content: @Composable () -> Unit) {
    val dark = theme.dark
    val colors = remember(dark, theme.accent) { androidThemeColors(theme) }
    val shared = if (dark) darkColors() else lightColors()
    SharedMaterialTheme(colors = shared.copy(
        primary = colors.primary, secondary = colors.primary,
        background = colors.background, surface = colors.surface, onPrimary = colors.onPrimary,
        onSecondary = colors.onPrimary, onBackground = colors.onBackground, onSurface = colors.onSurface,
    )) {
        MaterialTheme(colorScheme = colors) {
            CompositionLocalProvider(
                LocalChatTheme provides theme,
                LocalTextSelectionColors provides TextSelectionColors(colors.primary,
                    if (theme.accent == null) { if (dark) Color(0xFF50456B) else Color(0xFFACC5FF) }
                    else chatThemeColors(theme).selection), content = content)
        }
    }
}

@Composable
internal fun AndroidAppearanceScreen(
    openTheme: () -> Unit, notify: Boolean, onNotify: (Boolean) -> Unit,
    systemAllowed: Boolean, systemSettings: () -> Unit, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("外观与通知", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("返回") }
        }
        TextButton(onClick = openTheme) { Text("主题") }
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
