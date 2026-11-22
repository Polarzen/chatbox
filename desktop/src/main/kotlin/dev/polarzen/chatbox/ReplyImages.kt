package dev.polarzen.chatbox

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class ReplyImage(val attachmentId: String?, val textOffset: Int)

/** Keeps received image bytes outside of history JSON and scopes names to a profile and attachment. */
internal class ReplyImageCache(val directory: Path) {
    fun read(profileId: String, attachmentId: String): ImageBitmap? {
        return try {
            val path = imagePath(profileId, attachmentId)
            if (!Files.isRegularFile(path)) return null
            val size = Files.size(path)
            if (size !in 1..MAX_ENCODED_BYTES.toLong()) return null
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_ENCODED_BYTES + 1) }
            if (bytes.size !in 1..MAX_ENCODED_BYTES) null else decode(bytes)
        } catch (_: Exception) {
            null
        }
    }

    fun save(profileId: String, attachmentId: String, bytes: ByteArray): ImageBitmap {
        require(bytes.size in 1..MAX_ENCODED_BYTES) { "Image data must be between 1 byte and 20 MiB" }
        val encoded = bytes.copyOf()
        val bitmap = decode(encoded) ?: throw IllegalArgumentException("Unsupported, corrupt, or oversized image")
        Files.createDirectories(directory)
        val target = imagePath(profileId, attachmentId)
        val temp = Files.createTempFile(directory, "reply-image-", ".tmp")
        try {
            Files.write(temp, encoded)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        return bitmap
    }

    private fun imagePath(profileId: String, attachmentId: String): Path {
        val key = "$profileId\u0000$attachmentId".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return directory.resolve("$digest.image")
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_ENCODED_BYTES) return null
        return try {
            Data.makeFromBytes(bytes).use { data ->
                Codec.makeFromData(data).use { codec ->
                    val info = codec.imageInfo
                    val pixels = info.width.toLong() * info.height.toLong()
                    if (info.width !in 1..MAX_DIMENSION || info.height !in 1..MAX_DIMENSION ||
                        pixels !in 1..MAX_DECODED_PIXELS) return null
                    val bitmap = codec.readPixels()
                    if (bitmap.isNull || bitmap.width != info.width || bitmap.height != info.height) {
                        bitmap.close()
                        return null
                    }
                    bitmap.asComposeImageBitmap()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_ENCODED_BYTES = 20 * 1024 * 1024
        const val MAX_DIMENSION = 8192
        const val MAX_DECODED_PIXELS = 8_500_000L
    }
}
