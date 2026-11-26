package dev.polarzen.chatbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun AndroidThemeWallpaper(modifier: Modifier) {
    val theme = LocalChatTheme.current
    val image = theme.wallpaper ?: return
    val bitmap by produceState<Bitmap?>(null, image) {
        value = null
        value = withContext(Dispatchers.Default) { decodeThemeWallpaper(image) }
    }
    val source = bitmap ?: return
    val softness = theme.blur
    // Scope the rendered frame to the source so removing or replacing a photo clears the old image.
    key(source) {
        val blurred by produceState<Bitmap?>(null, softness) {
            if (softness > 0) {
                delay(75)
                value = withContext(Dispatchers.Default) { softenThemeWallpaper(source, softness) }
            }
        }
        val current = if (softness == 0) source else blurred
        if (current != null) {
            Image(current.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
            Box(modifier.background(MaterialTheme.colorScheme.background.copy(alpha = .50f)))
        }
    }
}

private fun decodeThemeWallpaper(encoded: String): Bitmap? {
    val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth !in 1..1600 || options.outHeight !in 1..1600) return null
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun softenThemeWallpaper(source: Bitmap, softness: Int): Bitmap {
    val edge = max(source.width, source.height)
    val ratio = minOf(1f, 480f / edge)
    val width = max(1, (source.width * ratio).roundToInt())
    val height = max(1, (source.height * ratio).roundToInt())
    val small = Bitmap.createScaledBitmap(source, width, height, true)
    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    if (small !== source) small.recycle()
    val radius = max(1, (softness / 100f * max(width, height) / 18f).roundToInt())
    return Bitmap.createBitmap(blurWallpaperPixels(pixels, width, height, radius), width, height, Bitmap.Config.ARGB_8888)
}
