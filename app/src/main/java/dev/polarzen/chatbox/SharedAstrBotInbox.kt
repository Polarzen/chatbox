package dev.polarzen.chatbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

internal data class AstrBotInboxMessage(val id: Long, val text: String)
internal data class AstrBotInboxPage(val messages: List<AstrBotInboxMessage>, val more: Boolean)

internal class AstrBotInboxDenied : IOException("主动收件未获授权，请检查 AstrBot 上的密钥和会话归属授权。")

internal fun inboxSetupNotice(status: String, username: String): String = when (status) {
    "unsupported" -> "当前 AstrBot 尚未安装 Chat Box 主动收件适配。"
    "denied" -> "主动收件未获授权。请在 AstrBot 上为此 API Key 授权" +
        (if (username.isBlank()) "当前会话所属的 Chat Box 用户。" else "用户 $username。")
    "unavailable" -> "主动收件暂时未连接，恢复后会继续接收。"
    else -> ""
}

internal fun inboxTurnId(session: String, id: Long): String =
    UUID.nameUUIDFromBytes("chatbox-inbox-v1:${session.lowercase(Locale.ROOT)}:$id".toByteArray(Charsets.UTF_8)).toString()

private const val INBOX_PAGE_SIZE = 50
private const val MAX_INBOX_BYTES = 4 * 1024 * 1024

internal suspend fun fetchAstrBotInbox(
    base: String, token: String, user: String, session: String, after: Long,
    allowLoopbackHttp: Boolean = false,
): AstrBotInboxPage? = withContext(Dispatchers.IO) {
    require(after >= 0 && token.isNotBlank() && user.isNotBlank() && session.isNotBlank())
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    val url = endpoint(base, allowLoopbackHttp).toASCIIString().removeSuffix("/chat") +
        "/chatbox/inbox?username=" + encode(user) + "&session_id=" + encode(session) +
        "&after=$after&limit=$INBOX_PAGE_SIZE"
    val conn = openAstrBotConnection(URI(url))
    try {
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.connectTimeout = 5_000
        conn.readTimeout = 6_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("ngrok-skip-browser-warning", "1")
        when (val status = conn.responseCode) {
            404, 405, 501 -> return@withContext null
            401, 403 -> throw AstrBotInboxDenied()
            200 -> Unit
            else -> throw IOException("主动收件暂时不可用（HTTP $status）。")
        }
        if (!conn.contentType?.substringBefore(';')?.trim().equals("application/json", true)) {
            return@withContext null  // Instances serving the dashboard shell for unknown routes.
        }
        if (conn.contentLengthLong > MAX_INBOX_BYTES) throw IOException("主动收件响应过大。")
        val raw = conn.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_INBOX_BYTES) throw IOException("主动收件响应过大。")
                output.write(buffer, 0, read)
            }
            output.toString("UTF-8")
        }
        val reply = JSONObject(raw)
        require(reply.get("status") == "ok")
        val data = reply.getJSONObject("data")
        val rows = data.getJSONArray("messages")
        require(rows.length() <= INBOX_PAGE_SIZE)
        var previous = after
        val messages = List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            val rawId = row.get("id")
            require(rawId is Int || rawId is Long)
            val id = (rawId as Number).toLong()
            val body = row.get("text") as String
            require(id > previous && body.isNotBlank())
            previous = id
            AstrBotInboxMessage(id, body)
        }
        val more = data.get("more") as Boolean
        require(!more || messages.isNotEmpty())
        AstrBotInboxPage(messages, more)
    } finally {
        conn.disconnect()
    }
}
