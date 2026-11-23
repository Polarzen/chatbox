package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val MAX_REPLY_MEDIA_BYTES = 20 * 1024 * 1024

private enum class ReplyMedia(val marker: String, val label: String, val mimePrefix: String) {
    IMAGE("[IMAGE]", "图片", "image/"),
    AUDIO("[RECORD]", "语音", "audio/");

    fun accepts(mime: String?): Boolean = mime?.startsWith(mimePrefix) == true ||
        mime == "application/octet-stream" || (this == AUDIO && mime == "application/ogg")
}

/** Transport milestones emitted synchronously on the sending thread. Upload indexes are zero-based. */
internal sealed interface AstrBotTransportEvent {
    data class UploadStarted(val index: Int, val count: Int) : AstrBotTransportEvent
    data class UploadFinished(val index: Int, val count: Int) : AstrBotTransportEvent
    data object ChatStarted : AstrBotTransportEvent
    data object HeadersReceived : AstrBotTransportEvent
    data object StreamReady : AstrBotTransportEvent
    data object FirstContent : AstrBotTransportEvent
    data object Completed : AstrBotTransportEvent
}

internal fun endpoint(base: String, allowLoopbackHttp: Boolean = false): URI {
    val uri = URI(base.trim())
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    val loopbackHttp = allowLoopbackHttp && uri.scheme.equals("http", true) &&
        uri.host == "127.0.0.1" &&
        (uri.rawAuthority == "127.0.0.1" || (uri.port in 1..65535 &&
            uri.rawAuthority.matches(Regex("127[.]0[.]0[.]1:[0-9]+"))))
    require(uri.scheme.equals("https", true) || loopbackHttp)
    return URI(uri.toASCIIString().trimEnd('/') + "/api/v1/chat")
}

internal fun sendToAstrBot(
    base: String, token: String, user: String, session: String, message: String,
    allowLoopbackHttp: Boolean = false,
    onReplyImage: (String?, Int) -> Unit = { _, _ -> },
    onText: (String) -> Unit,
) {
    sendToAstrBot(base, token, user, session, message, allowLoopbackHttp,
        onReplyImage, {}, onText)
}

/** Event-aware overload. The original overload remains for existing Android and desktop callers. */
internal fun sendToAstrBot(
    base: String, token: String, user: String, session: String, message: String,
    allowLoopbackHttp: Boolean = false,
    onReplyImage: (String?, Int) -> Unit = { _, _ -> },
    onEvent: (AstrBotTransportEvent) -> Unit,
    onText: (String) -> Unit,
) {
    sendToAstrBot(base, token, user, session, message, allowLoopbackHttp, onReplyImage,
        onReplyAudio = { _, _ -> }, onEvent = onEvent, onText = onText)
}

internal fun sendToAstrBot(
    base: String, token: String, user: String, session: String, message: String,
    allowLoopbackHttp: Boolean = false,
    onReplyImage: (String?, Int) -> Unit = { _, _ -> },
    onReplyAudio: (String?, Int) -> Unit,
    onEvent: (AstrBotTransportEvent) -> Unit = {},
    onText: (String) -> Unit,
) {
    sendToAstrBotPayload(
        base, token, user, session, JSONObject().put("message", message),
        allowLoopbackHttp, onReplyImage, onReplyAudio, onEvent, onText,
    )
}

/** Upload an image using OpenAPI's file scope, then send its attachment in the chat scope. */
internal fun sendImageToAstrBot(
    base: String, token: String, user: String, session: String,
    image: Path, caption: String = "",
    allowLoopbackHttp: Boolean = false,
    onReplyImage: (String?, Int) -> Unit = { _, _ -> },
    onText: (String) -> Unit,
) {
    sendImageToAstrBot(base, token, user, session, image, caption, allowLoopbackHttp,
        onReplyImage, {}, onText)
}

/** Event-aware single-image overload, preserving the original overload for existing callers. */
internal fun sendImageToAstrBot(
    base: String, token: String, user: String, session: String,
    image: Path, caption: String = "",
    allowLoopbackHttp: Boolean = false,
    onReplyImage: (String?, Int) -> Unit = { _, _ -> },
    onEvent: (AstrBotTransportEvent) -> Unit,
    onText: (String) -> Unit,
) {
    sendImagesToAstrBot(base, token, user, session, listOf(image), caption,
        allowLoopbackHttp, onReplyImage, onEvent, onText)
}

/** Upload ordered images using OpenAPI's file scope, then send all attachments in one chat message. */
internal fun sendImagesToAstrBot(
    base: String, token: String, user: String, session: String,
    images: List<Path>, caption: String = "",
    allowLoopbackHttp: Boolean = false,
    onReplyImage: (String?, Int) -> Unit = { _, _ -> },
    onEvent: (AstrBotTransportEvent) -> Unit = {},
    onText: (String) -> Unit,
) {
    sendImagesToAstrBot(base, token, user, session, images, caption, allowLoopbackHttp, onReplyImage,
        onReplyAudio = { _, _ -> }, onEvent = onEvent, onText = onText)
}

internal fun sendImagesToAstrBot(
    base: String, token: String, user: String, session: String,
    images: List<Path>, caption: String = "",
    allowLoopbackHttp: Boolean = false,
    onReplyImage: (String?, Int) -> Unit = { _, _ -> },
    onReplyAudio: (String?, Int) -> Unit,
    onEvent: (AstrBotTransportEvent) -> Unit = {},
    onText: (String) -> Unit,
) {
    require(images.isNotEmpty()) { "至少选择一张图片。" }
    // Validate the complete selection before starting uploads, so a later invalid entry cannot
    // leave earlier attachments uploaded without a corresponding chat message.
    val prepared = images.map { image ->
        val path = image.toAbsolutePath().normalize()
        require(Files.isRegularFile(path) && Files.isReadable(path)) { "无法读取所选图片。" }
        path to imageMediaType(path)
    }
    val uploaded = prepared.mapIndexed { index, (path, mediaType) ->
        onEvent(AstrBotTransportEvent.UploadStarted(index, prepared.size))
        val attachmentId = uploadImageToAstrBot(base, token, path, mediaType, allowLoopbackHttp)
        onEvent(AstrBotTransportEvent.UploadFinished(index, prepared.size))
        path to attachmentId
    }
    val parts = JSONArray()
    if (caption.isNotEmpty()) parts.put(JSONObject().put("type", "plain").put("text", caption))
    uploaded.forEach { (path, attachmentId) ->
        parts.put(JSONObject().put("type", "image").put("attachment_id", attachmentId)
            .put("filename", path.fileName.toString()))
    }
    sendToAstrBotPayload(base, token, user, session, JSONObject().put("message", parts),
        allowLoopbackHttp, onReplyImage, onReplyAudio, onEvent, onText)
}

/** Download a reply attachment through the authenticated, same-origin OpenAPI file endpoint. */
internal fun downloadReplyImage(
    base: String, token: String, attachmentId: String, allowLoopbackHttp: Boolean = false,
): ByteArray = downloadReplyMedia(base, token, attachmentId, allowLoopbackHttp, ReplyMedia.IMAGE)

internal fun downloadReplyAudio(
    base: String, token: String, attachmentId: String, allowLoopbackHttp: Boolean = false,
): ByteArray = downloadReplyMedia(base, token, attachmentId, allowLoopbackHttp, ReplyMedia.AUDIO)

private fun downloadReplyMedia(
    base: String, token: String, attachmentId: String, allowLoopbackHttp: Boolean, media: ReplyMedia,
): ByteArray {
    require(attachmentId.isNotBlank()) { "${media.label}附件编号无效。" }
    val chatTarget = endpoint(base, allowLoopbackHttp)
    val encodedId = URLEncoder.encode(attachmentId, Charsets.UTF_8.name())
    val target = URI(chatTarget.toASCIIString().removeSuffix("/chat") + "/file?attachment_id=$encodedId")
    val c = openAstrBotConnection(target)
    try {
        c.instanceFollowRedirects = false
        c.requestMethod = "GET"
        c.connectTimeout = 12_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", if (media == ReplyMedia.AUDIO)
            "audio/*, application/ogg, application/octet-stream" else "image/*, application/octet-stream")

        val status = c.responseCode
        check(status in 200..299) { downloadHttpError(status, media) }
        val mediaType = c.contentType?.substringBefore(';')?.trim()?.lowercase()
        check(media.accepts(mediaType)) {
            "${media.label}下载接口未返回${media.label}，请检查 AstrBot 服务。"
        }
        check(c.contentLengthLong <= MAX_REPLY_MEDIA_BYTES) {
            "回复${media.label}超过 20 MB 限制。"
        }
        val bytes = c.inputStream.use { it.readBounded(MAX_REPLY_MEDIA_BYTES, media) }
        check(bytes.isNotEmpty()) { "服务返回了空${media.label}。" }
        check(!looksLikeJson(bytes)) { "${media.label}下载接口返回了错误信息，而不是${media.label}。" }
        return bytes
    } finally {
        c.disconnect()
    }
}

private fun InputStream.readBounded(maxBytes: Int, media: ReplyMedia): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
        if (count < 0) break
        total += count
        check(total <= maxBytes) { "回复${media.label}超过 20 MB 限制。" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun looksLikeJson(bytes: ByteArray): Boolean {
    val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() } ?: return false
    return first == '{'.code.toByte() || first == '['.code.toByte()
}

private fun downloadHttpError(status: Int, media: ReplyMedia): String = when (status) {
    401 -> "密钥无效或已撤销。"
    403 -> "缺少${media.label}下载权限，请为 API 密钥启用 file 权限。"
    404 -> "找不到回复${media.label}，请检查附件是否仍然存在。"
    in 300..399 -> "服务返回重定向，请直接填写最终服务地址。"
    else -> "${media.label}下载失败，服务器返回 HTTP $status。"
}

private fun imageMediaType(path: Path): String = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "tif", "tiff" -> "image/tiff"
    else -> throw IllegalArgumentException("仅支持 PNG、JPEG、GIF、WebP、BMP 或 TIFF 图片。")
}

private fun uploadImageToAstrBot(
    base: String, token: String, image: Path, mediaType: String, allowLoopbackHttp: Boolean,
): String {
    val chatTarget = endpoint(base, allowLoopbackHttp)
    val target = URI(chatTarget.toASCIIString().removeSuffix("/chat") + "/file")
    val c = openAstrBotConnection(target)
    val boundary = "----Chatbox" + UUID.randomUUID().toString().replace("-", "")
    val extension = image.fileName.toString().substringAfterLast('.', "").lowercase()
    // An ASCII upload name avoids multipart header ambiguity for Unicode local filenames.
    val safeExtension = extension.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "img"
    val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.$safeExtension\"\r\n" +
        "Content-Type: $mediaType\r\n\r\n"
    val suffix = "\r\n--$boundary--\r\n"
    val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
    val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
    val contentLength = Math.addExact(Math.addExact(prefixBytes.size.toLong(), Files.size(image)), suffixBytes.size.toLong())
    try {
        c.instanceFollowRedirects = false
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 12_000
        c.readTimeout = 90_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(contentLength)
        c.outputStream.use { output ->
            output.write(prefixBytes)
            Files.newInputStream(image).use { input -> input.copyTo(output) }
            output.write(suffixBytes)
        }
        val status = c.responseCode
        check(status in 200..299) { uploadHttpError(status) }
        check(c.contentType?.substringBefore(';')?.trim()?.equals("application/json", true) == true) {
            "图片上传接口未返回 JSON，请检查 AstrBot 地址和版本。"
        }
        val response = runCatching { JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }) }
            .getOrElse { throw IllegalStateException("图片上传接口返回了无法识别的响应。") }
        if (response.optString("status") != "ok") {
            val detail = response.optString("message").lowercase()
            throw IllegalStateException(when {
                "too large" in detail || "file too large" in detail -> "图片超过服务端大小限制（512 MB）。"
                else -> "图片上传失败，请检查文件权限和 API 密钥的文件权限。"
            })
        }
        val attachmentId = response.optJSONObject("data")?.optString("attachment_id").orEmpty()
        check(attachmentId.isNotBlank()) { "图片上传成功响应中缺少附件编号。" }
        return attachmentId
    } finally {
        c.disconnect()
    }
}

private fun uploadHttpError(status: Int): String = when (status) {
    401 -> "密钥无效或已撤销。"
    403 -> "缺少文件上传权限，请为 API 密钥启用 file 权限。"
    404 -> "找不到文件上传接口，请检查 AstrBot 版本和服务地址。"
    413 -> "图片超过服务端大小限制。"
    in 300..399 -> "服务返回重定向，请直接填写最终服务地址。"
    else -> "图片上传失败，服务器返回 HTTP $status。"
}

private fun openAstrBotConnection(target: URI): HttpURLConnection {
    val url = target.toURL()
    return (if (target.scheme.equals("http", true)) url.openConnection(Proxy.NO_PROXY)
        else url.openConnection()) as HttpURLConnection
}

private fun sendToAstrBotPayload(
    base: String, token: String, user: String, session: String, messagePayload: JSONObject,
    allowLoopbackHttp: Boolean, onReplyImage: (String?, Int) -> Unit,
    onReplyAudio: (String?, Int) -> Unit, onEvent: (AstrBotTransportEvent) -> Unit, onText: (String) -> Unit,
) {
    val target = endpoint(base, allowLoopbackHttp)
    val c = openAstrBotConnection(target)
    try {
        onEvent(AstrBotTransportEvent.ChatStarted)
        c.instanceFollowRedirects = false
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 12_000
        c.readTimeout = 90_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "text/event-stream")
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val bytes = messagePayload.put("username", user).put("session_id", session)
            .put("enable_streaming", true).toString().toByteArray(Charsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        val status = c.responseCode
        onEvent(AstrBotTransportEvent.HeadersReceived)
        check(status in 200..299) {
            when (status) {
                401 -> "密钥无效或已撤销。"
                403 -> "权限不足，请检查聊天权限。"
                404 -> "找不到对话接口，请检查 AstrBot 版本和服务地址。"
                in 300..399 -> "服务返回重定向，请直接填写最终服务地址。"
                else -> "服务器返回 HTTP $status。"
            }
        }
        check(c.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", true) == true) {
            "服务未返回对话事件流，请检查接口地址。"
        }
        onEvent(AstrBotTransportEvent.StreamReady)
        var done = false
        var emittedFirstContent = false
        var completedText = ""
        var pendingText = ""
        var pendingMedia: ReplyMedia? = null
        var pendingCount = 0
        fun replyOffset(): Int = completedText.length + pendingText.length
        fun emitFirstContent() {
            if (!emittedFirstContent) {
                emittedFirstContent = true
                onEvent(AstrBotTransportEvent.FirstContent)
            }
        }
        fun emitMedia(media: ReplyMedia, id: String?) {
            emitFirstContent()
            when (media) {
                ReplyMedia.IMAGE -> onReplyImage(id, replyOffset())
                ReplyMedia.AUDIO -> onReplyAudio(id, replyOffset())
            }
        }
        fun flushMissing() {
            val media = pendingMedia ?: return
            val count = pendingCount
            pendingCount = 0
            pendingMedia = null
            repeat(count) { emitMedia(media, null) }
        }
        fun readReplyFrames() = c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            readSse(reader) { raw ->
                val frame = JSONObject(raw)
                val type = frame.optString("type")
                val chain = frame.optString("chain_type")
                val hiddenPlain = type == "plain" && chain in
                    setOf("reasoning", "tool_call", "tool_call_result", "agent_stats")
                when (type) {
                    "plain" -> {
                        if (hiddenPlain) {
                            completedText += pendingText
                            pendingText = ""
                        } else {
                            flushMissing()
                            val value = frame.opt("data") as? String ?: ""
                            if (frame.optBoolean("streaming")) pendingText += value
                            else {
                                pendingText = value
                                completedText += pendingText
                                pendingText = ""
                            }
                            if ((completedText + pendingText).isNotEmpty()) emitFirstContent()
                            onText(completedText + pendingText)
                        }
                    }
                    "image", "record" -> {
                        val media = if (type == "image") ReplyMedia.IMAGE else ReplyMedia.AUDIO
                        val value = frame.opt("data") as? String ?: ""
                        completedText += pendingText
                        pendingText = ""
                        // Consecutive image hints may be followed by a batch of saved images.
                        if (pendingMedia != media || media == ReplyMedia.AUDIO || !value.startsWith(media.marker)) {
                            flushMissing()
                        }
                        if (value.startsWith(media.marker)) {
                            pendingMedia = media
                            pendingCount++
                            if (media == ReplyMedia.AUDIO) emitFirstContent()
                        }
                    }
                    "attachment_saved" -> {
                        completedText += pendingText
                        pendingText = ""
                        val data = frame.optJSONObject("data")
                        val media = when (data?.optString("type")) {
                            "image" -> ReplyMedia.IMAGE
                            "record" -> ReplyMedia.AUDIO
                            else -> null
                        }
                        if (pendingMedia != media) flushMissing()
                        val id = (data?.opt("id") as? String)?.takeIf { it.isNotBlank() }
                        if (media != null && (id != null || media == ReplyMedia.AUDIO)) {
                            if (pendingCount > 0) {
                                pendingCount--
                                if (pendingCount == 0) pendingMedia = null
                            }
                            emitMedia(media, id)
                        } else flushMissing()
                    }
                    "complete", "file", "video", "actionrow" -> {
                        flushMissing()
                        completedText += pendingText
                        pendingText = ""
                    }
                    "error" -> error("AstrBot 报告对话失败，请在服务端检查配置。")
                    "end" -> {
                        flushMissing()
                        done = true
                    }
                }
                !done
            }
        }
        try {
            readReplyFrames()
        } catch (failure: Exception) {
            runCatching { flushMissing() }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
        flushMissing()
        onText(completedText + pendingText)
        check(done) { "回复流意外中断，可能已在服务端执行。请勿直接重复发送。" }
        onEvent(AstrBotTransportEvent.Completed)
    } finally {
        c.disconnect()
    }
}
