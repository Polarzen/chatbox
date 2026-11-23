package dev.polarzen.chatbox

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

internal sealed interface ClipboardImages {
    data object NativePaste : ClipboardImages
    data class Files(val files: List<File>) : ClipboardImages
    data class Bitmap(val image: Image) : ClipboardImages
    data class Failure(val message: String) : ClipboardImages
}

/** Called only from the composer's paste key handler. Snapshot image/file data before returning. */
internal fun captureClipboardImages(
    clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
): ClipboardImages = try {
    val source = clipboard()
    val flavors = source.availableDataFlavors
    when {
        DataFlavor.javaFileListFlavor in flavors -> {
            val items = source.getData(DataFlavor.javaFileListFlavor) as? List<*>
                ?: throw IOException("文件列表无效")
            ClipboardImages.Files(items.map { it as? File ?: throw IOException("文件列表无效") })
        }
        DataFlavor.imageFlavor in flavors -> ClipboardImages.Bitmap(
            source.getData(DataFlavor.imageFlavor) as? Image ?: throw IOException("位图数据无效"),
        )
        else -> ClipboardImages.NativePaste
    }
} catch (_: IllegalStateException) {
    ClipboardImages.Failure("剪贴板正被占用，请稍后再粘贴。")
} catch (failure: Exception) {
    ClipboardImages.Failure("无法读取剪贴板，请重新复制后粘贴（${failure.localizedMessage ?: "读取失败"}）。")
}

// A 32-megapixel ARGB raster uses 128 MiB and accommodates a full 8K desktop.
private const val MAX_CAPTURE_PIXELS = 32L * 1024 * 1024

/** Encode off the UI thread. Only a completed, uniquely owned file is returned. */
internal fun writeClipboardPng(image: Image, directory: Path): Path {
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    require(width > 0 && height > 0) { "剪贴板图片尺寸无效，请重新截图。" }
    require(width.toLong() * height <= MAX_CAPTURE_PIXELS) {
        "截图过大（${width} × ${height}），请缩小截图范围。"
    }
    val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = bitmap.createGraphics()
    try {
        if (!graphics.drawImage(image, 0, 0, null)) throw IOException("未能读取完整截图，请重新截图。")
    } finally {
        graphics.dispose()
    }
    Files.createDirectories(directory)
    val output = Files.createTempFile(directory, "screenshot-", ".png")
    var complete = false
    try {
        if (!ImageIO.write(bitmap, "png", output.toFile())) throw IOException("缺少 PNG 编码器，无法保存截图。")
        complete = true
        return output
    } finally {
        if (!complete) Files.deleteIfExists(output)
    }
}
