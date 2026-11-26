package dev.polarzen.chatbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Import into a bounded JPEG so both devices can render the same pixels without a URI grant. */
internal fun importThemePhoto(context: Context, uri: Uri): ByteArray {
    val file = File.createTempFile("wallpaper-", ".input", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { src ->
            file.outputStream().use { dst ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = src.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 32L * 1024 * 1024) { "请选择小于 32 MiB 的图片。" }
                    dst.write(buffer, 0, read)
                }
            }
        } ?: throw IOException("无法打开图片。")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val edge = max(options.outWidth, options.outHeight)
        require(options.outWidth > 0 && options.outHeight > 0 && edge <= 65536) { "不支持此图片尺寸。" }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (edge / options.inSampleSize > 2048) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("图片解码失败。")
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val transform = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        if (oriented !== bitmap) bitmap.recycle()
        try {
            return encodeWallpaper(oriented)
        } finally { oriented.recycle() }
    } finally { file.delete() }
}

private fun encodeWallpaper(bitmap: Bitmap): ByteArray {
    var edge = minOf(max(bitmap.width, bitmap.height), THEME_IMAGE_EDGE)
    val brush = Paint(Paint.FILTER_BITMAP_FLAG)
    while (edge >= 1) {
        val scale = edge.toFloat() / max(bitmap.width, bitmap.height)
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        val opaque = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(opaque)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), brush)
        val bytes = ByteArrayOutputStream()
        try {
            if (!opaque.compress(Bitmap.CompressFormat.JPEG, 86, bytes)) throw IOException("无法转换图片。")
        } finally { opaque.recycle() }
        if (bytes.size() <= THEME_IMAGE_BYTES) return bytes.toByteArray()
        edge = edge * 3 / 4
    }
    throw IOException("图片过大，请选择较小的图片。")
}
