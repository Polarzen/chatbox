package dev.polarzen.chatbox

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val SOURCE_IMAGE_LIMIT = 512L * 1024 * 1024

internal fun mobileMediaDirectory(context: Context): File = File(context.filesDir, "media").apply {
    check(isDirectory || mkdirs() || isDirectory) { "无法创建本机附件目录。" }
}

internal fun publishMobileMedia(part: File, extension: String): File {
    val target = File(part.parentFile, "${UUID.randomUUID()}.$extension")
    Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    return target
}

private fun imageBounds(file: File): BitmapFactory.Options = BitmapFactory.Options().also {
    it.inJustDecodeBounds = true
    BitmapFactory.decodeFile(file.path, it)
    require(it.outWidth in 1..65_535 && it.outHeight in 1..65_535) { "图片尺寸无效或文件已损坏。" }
}

internal fun mobileBitmap(file: File, maxEdge: Int): Bitmap {
    val bounds = imageBounds(file)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while ((longest + sample - 1) / sample > maxEdge) sample *= 2
    return requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })) {
        "无法解码这张图片。"
    }
}

private fun checkedImageExtension(file: File, reply: Boolean): String {
    val bounds = imageBounds(file)
    if (reply) require(maxOf(bounds.outWidth, bounds.outHeight) <= 8192 &&
        bounds.outWidth.toLong() * bounds.outHeight <= 8_500_000L) { "回复图片尺寸过大。" }
    val extension = when (bounds.outMimeType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> if (reply) "gif" else null
        "image/webp" -> if (reply) "webp" else null
        else -> null
    }
    requireNotNull(extension) { if (reply) "回复图片格式不受支持。" else "仅支持 PNG 和 JPEG 图片。" }
    mobileBitmap(file, 256).recycle()
    return extension
}

private fun copySourceImage(input: InputStream, part: File) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    part.outputStream().use { out ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= SOURCE_IMAGE_LIMIT) { "图片超过 512 MiB。" }
            out.write(buffer, 0, count)
        }
    }
    require(total > 0) { "图片为空。" }
}

/** Copy while the picker, clipboard, or sharing app still grants read access. Call off the UI thread. */
internal fun importMobileImages(context: Context, uris: List<Uri>): List<MobileAsset> {
    val directory = mobileMediaDirectory(context)
    var lastError: Exception? = null
    val accepted = uris.distinct().mapNotNull { uri ->
        try {
            require(uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank() &&
                uri.userInfo == null) { "图片没有可用的内容授权。" }
            val provider = context.packageManager.resolveContentProvider(uri.authority!!, 0)
            require(provider?.applicationInfo?.uid != context.applicationInfo.uid) { "无法导入应用自己的内容地址。" }
            val granted = context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
            val publicSource = provider?.exported == true && provider.readPermission == null &&
                provider.pathPermissions.orEmpty().none { it.readPermission != null && it.match(uri.path.orEmpty()) }
            require(granted || publicSource) { "图片读取授权已失效，请重新选择。" }
            val part = File.createTempFile("import-", ".part", directory)
            try {
                requireNotNull(context.contentResolver.openInputStream(uri)) { "图片授权已失效，请重新选择。" }
                    .use { copySourceImage(it, part) }
                val extension = checkedImageExtension(part, reply = false)
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')?.filterNot(Char::isISOControl)
                    ?.take(160)?.takeIf(String::isNotBlank) ?: "图片.$extension"
                MobileAsset(publishMobileMedia(part, extension).absolutePath, "image", name)
            } finally {
                part.delete()
            }
        } catch (e: Exception) {
            lastError = e
            null
        }
    }
    if (accepted.isEmpty() && lastError != null) {
        throw IllegalArgumentException(lastError?.message ?: "图片不可用，请重新选择。", lastError)
    }
    return accepted
}

internal fun cacheMobileReply(context: Context, kind: String, bytes: ByteArray): MobileAsset {
    require(bytes.size in 1..MOBILE_REPLY_LIMIT) { "回复附件为空或超过 20 MiB。" }
    val part = File.createTempFile("reply-", ".part", mobileMediaDirectory(context))
    try {
        part.writeBytes(bytes)
        val extension = when (kind) {
            "image" -> checkedImageExtension(part, reply = true)
            "audio" -> { mobileWavDuration(part); "wav" }
            else -> throw IllegalArgumentException("未知的附件类型。")
        }
        val name = if (kind == "audio") "回复语音.wav" else "回复图片.$extension"
        return MobileAsset(publishMobileMedia(part, extension).absolutePath, kind, name)
    } finally {
        part.delete()
    }
}
