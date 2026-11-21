package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

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
    onText: (String) -> Unit,
) {
    sendToAstrBotPayload(
        base, token, user, session, JSONObject().put("message", message),
        allowLoopbackHttp, onText,
    )
}

/** Upload an image using OpenAPI's file scope, then send its attachment in the chat scope. */
internal fun sendImageToAstrBot(
    base: String, token: String, user: String, session: String,
    image: Path, caption: String = "",
    allowLoopbackHttp: Boolean = false,
    onText: (String) -> Unit,
) {
    val path = image.toAbsolutePath().normalize()
    require(Files.isRegularFile(path) && Files.isReadable(path)) { "无法读取所选图片。" }
    val mediaType = imageMediaType(path)
    val attachmentId = uploadImageToAstrBot(base, token, path, mediaType, allowLoopbackHttp)
    val parts = JSONArray()
    if (caption.isNotEmpty()) parts.put(JSONObject().put("type", "plain").put("text", caption))
    parts.put(JSONObject().put("type", "image").put("attachment_id", attachmentId)
        .put("filename", path.fileName.toString()))
    sendToAstrBotPayload(base, token, user, session, JSONObject().put("message", parts),
        allowLoopbackHttp, onText)
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
    allowLoopbackHttp: Boolean, onText: (String) -> Unit,
) {
    val target = endpoint(base, allowLoopbackHttp)
    val c = openAstrBotConnection(target)
    try {
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
        var done = false
        var completedText = ""
        var pendingText = ""
        c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            readSse(reader) { raw ->
                val frame = JSONObject(raw)
                when (frame.optString("type")) {
                    "plain" -> {
                        val chain = frame.optString("chain_type")
                        if (chain in setOf("reasoning", "tool_call", "tool_call_result", "agent_stats")) {
                            completedText += pendingText
                            pendingText = ""
                        } else {
                            val value = frame.opt("data") as? String ?: ""
                            if (frame.optBoolean("streaming")) pendingText += value
                            else {
                                pendingText = value
                                completedText += pendingText
                                pendingText = ""
                            }
                            onText(completedText + pendingText)
                        }
                    }
                    "complete", "image", "file", "video", "record", "actionrow" -> {
                        completedText += pendingText
                        pendingText = ""
                    }
                    "error" -> error("AstrBot 报告对话失败，请在服务端检查配置。")
                    "end" -> done = true
                }
                !done
            }
        }
        onText(completedText + pendingText)
        check(done) { "回复流意外中断，可能已在服务端执行。请勿直接重复发送。" }
    } finally {
        c.disconnect()
    }
}
