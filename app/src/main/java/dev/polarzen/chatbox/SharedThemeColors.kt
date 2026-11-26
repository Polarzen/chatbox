package dev.polarzen.chatbox

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

internal data class ChatThemeColors(
    val accent: Color, val onAccent: Color, val accentSurface: Color, val selection: Color,
)

internal fun chatThemeColors(theme: ChatTheme): ChatThemeColors {
    if (theme.accent == null) return if (theme.dark) {
        ChatThemeColors(Color(0xFFC6ABFF), Color(0xFF28173D), Color(0xFF3C304D), Color(0xFF50456B))
    } else {
        ChatThemeColors(Color(0xFF6745B4), Color.White, Color(0xFFF0EBFB), Color(0xFFACC5FF))
    }
    val seed = Color(0xff000000.toInt() or theme.accent.drop(1).toInt(16))
    val panel = if (theme.dark) Color(0xFF25222E) else Color(0xFFF7F6FA)
    val target = if (theme.dark) Color.White else Color.Black
    val accent = (0..20).asSequence().map { lerp(seed, target, it / 20f) }
        .first { contrast(it, panel) >= 4.5f }
    val onAccent = if (contrast(accent, Color.Black) >= contrast(accent, Color.White)) Color.Black else Color.White
    return ChatThemeColors(accent, onAccent, lerp(panel, seed, if (theme.dark) .24f else .15f),
        lerp(panel, seed, if (theme.dark) .35f else .30f))
}

private fun contrast(a: Color, b: Color): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (max(x, y) + .05f) / (min(x, y) + .05f)
}
