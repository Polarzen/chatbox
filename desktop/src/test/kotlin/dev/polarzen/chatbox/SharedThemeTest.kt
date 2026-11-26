package dev.polarzen.chatbox

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

class SharedThemeTest {
    private fun jpeg(width: Int, height: Int): String {
        val stream = ByteArrayOutputStream()
        check(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", stream))
        return Base64.getEncoder().encodeToString(stream.toByteArray())
    }

    @Test fun codecPreservesWallpaperAndNormalizesAccent() {
        val image = jpeg(1600, 1)
        val theme = ChatTheme(true, "#18abbc", image, 72)
        assertEquals(theme.copy(accent = "#18ABBC"), ChatTheme.fromJson(theme.toJson()))
        assertFalse(theme.toString().contains(image))
        assertEquals(ChatTheme(), ChatTheme.fromJson(ChatTheme().toJson()))
    }

    @Test fun invalidBoundariesAndMalformedRemoteDataAreRejected() {
        for (payload in listOf("", "bm90YSBqcGVn", jpeg(1601, 1), "A".repeat(700000))) {
            assertThrows(Exception::class.java) {
                ChatTheme.fromJson(ChatTheme().toJson().put("wallpaper", payload))
            }
        }
        assertThrows(Exception::class.java) { ChatTheme.fromJson(JSONObject().put("dark", true)) }
        assertThrows(Exception::class.java) { ChatTheme(blur = 101) }
        assertThrows(Exception::class.java) { ChatTheme(accent = "#nothex") }
        assertThrows(Exception::class.java) { ChatTheme.fromJson(ChatTheme().toJson().put("dark", "false")) }
    }

    @Test fun extremesKeepForegroundAndPrimaryControlsReadable() {
        fun ratio(a: Color, b: Color) = (maxOf(a.luminance(), b.luminance()) + .05f) /
            (minOf(a.luminance(), b.luminance()) + .05f)
        for (dark in listOf(false, true)) for (hex in listOf("#000000", "#FFFFFF", "#00FF00", "#FFFF00", "#FF0000")) {
            val colors = chatThemeColors(ChatTheme(dark, hex))
            val panel = if (dark) Color(0xFF25222E) else Color(0xFFF7F6FA)
            assertTrue(ratio(colors.accent, panel) >= 4.5f)
            assertTrue(ratio(colors.onAccent, colors.accent) >= 4.5f)
        }
    }
}
