package dev.polarzen.chatbox

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

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
    val target = endpoint(base, allowLoopbackHttp)
    val url = target.toURL()
    val c = (if (target.scheme.equals("http", true)) url.openConnection(Proxy.NO_PROXY)
        else url.openConnection()) as HttpURLConnection
    try {
        c.instanceFollowRedirects = false
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 12_000
        c.readTimeout = 90_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "text/event-stream")
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val bytes = JSONObject().put("username", user).put("session_id", session)
            .put("message", message).put("enable_streaming", true).toString().toByteArray()
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
