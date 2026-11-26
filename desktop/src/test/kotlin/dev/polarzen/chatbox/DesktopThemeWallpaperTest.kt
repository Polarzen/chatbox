package dev.polarzen.chatbox

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopThemeWallpaperTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun normalizesLargePngToPortableJpeg() {
        val file = temp.newFile("wallpaper.png")
        ImageIO.write(BufferedImage(THEME_IMAGE_EDGE * 2, 800, BufferedImage.TYPE_INT_ARGB), "png", file)
        val theme = ChatTheme(wallpaper = desktopWallpaper(file), blur = 35)
        assertEquals(theme, ChatTheme.fromJson(theme.toJson()))
        val imageBytes = Base64.getDecoder().decode(theme.wallpaper)
        assertTrue(imageBytes.size <= THEME_IMAGE_BYTES)
        val image = ImageIO.read(ByteArrayInputStream(imageBytes))
        assertEquals(THEME_IMAGE_EDGE, image.width)
        assertEquals(400, image.height)
    }

    @Test fun unreadableSelectionDoesNotYieldThemeData() {
        val file = temp.newFile("invalid.jpg").apply { writeText("not an image") }
        assertTrue(runCatching { desktopWallpaper(file) }.isFailure)
    }
}
