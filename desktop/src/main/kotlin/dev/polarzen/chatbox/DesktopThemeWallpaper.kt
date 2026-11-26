package dev.polarzen.chatbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

internal fun desktopWallpaper(file: File): String {
    require(file.isFile && file.length() in 1..64L * 1024 * 1024) { "请选择小于 64 MB 的图片。" }
    val sampled = ImageIO.createImageInputStream(file).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
            ?: error("无法读取此图片格式。")
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width > 0 && height > 0) { "无法读取图片尺寸。" }
            val sample = (max(width, height) / THEME_IMAGE_EDGE).coerceAtLeast(1)
            val params = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            reader.read(0, params)
        } finally {
            reader.dispose()
        }
    }
    var target = max(sampled.width, sampled.height).coerceAtMost(THEME_IMAGE_EDGE)
    while (true) {
        val scale = target.toDouble() / max(sampled.width, sampled.height)
        val image = BufferedImage((sampled.width * scale).roundToInt().coerceAtLeast(1),
            (sampled.height * scale).roundToInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(sampled, 0, 0, image.width, image.height, null)
        } finally {
            graphics.dispose()
        }
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(bytes).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = .82f
                }
                writer.write(null, IIOImage(image, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        if (bytes.size() <= THEME_IMAGE_BYTES) return Base64.getEncoder().encodeToString(bytes.toByteArray())
        target = (target * .75).toInt().coerceAtLeast(1)
    }
}

internal val LocalChatTheme = staticCompositionLocalOf { ChatTheme() }

@Composable
internal fun DesktopThemeBackground(theme: ChatTheme) {
    val encoded = theme.wallpaper ?: return
    val bitmap by produceState<ImageBitmap?>(null, encoded) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Base64.getDecoder().decode(encoded)
                ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
                    try {
                        reader.input = input
                        require(reader.getWidth(0) in 1..THEME_IMAGE_EDGE && reader.getHeight(0) in 1..THEME_IMAGE_EDGE)
                        reader.read(0).toComposeImageBitmap()
                    } finally {
                        reader.dispose()
                    }
                }
            }.getOrNull()
        }
    }
    bitmap?.let {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize().blur((theme.blur * .32f).dp),
                contentScale = ContentScale.Crop)
            // Stable contrast for timestamps, the header and empty conversation text.
            Box(Modifier.fillMaxSize().background(desktopPalette.canvas.copy(alpha = .72f)))
        }
    }
}
