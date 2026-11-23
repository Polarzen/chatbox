package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.Proxy
import java.util.UUID

private const val LOGIN = "/api/v1/auth/login"
private const val DISCOVER = "/api/plug/astrbot_plugin_whale_tasks/task-control/session"
private const val SEND = "/api/chat/send"

internal fun isWhaleTaskCommand(text: String): Boolean =
    text.startsWith("/task") && (text.length == 5 || text[5].isWhitespace())

internal class WhaleTaskLoginRequiredException(message: String) : IllegalStateException(message)

internal class WhaleTaskOutcomeUnknownException : IllegalStateException("任务请求结果未确认，命令可能已执行，请先核对状态。")

internal class WhaleTaskSession private constructor(
    private val profileId: String,
    private val address: String,
    private val generation: Long,
    private val jwt: String,
    private val sessionId: String,
) {
    companion object {
        fun login(target: DesktopSendTarget, username: String, password: String, totp: String, beforeRequest: () -> Unit): WhaleTaskSession {
            require(username.isNotBlank() && password.isNotEmpty()) { "请输入 Dashboard 用户名和密码。" }
            require(totp.isEmpty() || totp.matches(Regex("[0-9]{6}"))) { "请输入六位动态验证码。" }
            val credentials = JSONObject().put("username", username).put("password", password)
            if (totp.isNotEmpty()) credentials.put("code", totp)
            val login = request(target, LOGIN, credentials, beforeRequest = beforeRequest) { readObject(it) }
            if (login.optString("status") != "ok") {
                throw WhaleTaskLoginRequiredException("登录失败，请检查用户名、密码和动态验证码。")
            }
            val token = login.optJSONObject("data")?.opt("token") as? String
            check(token != null && token != target.token && token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))) {
                "Dashboard 登录响应缺少有效的会话令牌。"
            }
            val id = request(target, DISCOVER, jwt = token, beforeRequest = beforeRequest) { readObject(it) }.opt("session_id") as? String
            check(id != null && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
                "任务插件返回的会话编号无效。"
            }
            return WhaleTaskSession(target.profileId, target.address, target.bindingGeneration, token, id)
        }
    }

    fun send(target: DesktopSendTarget, command: String, onEvent: (AstrBotTransportEvent) -> Unit, onText: (String) -> Unit,
             beforeRequest: () -> Unit) {
        if (profileId != target.profileId || address != target.address || generation != target.bindingGeneration) {
            throw WhaleTaskLoginRequiredException("连接已更改，请重新登录任务会话。")
        }
        require(isWhaleTaskCommand(command)) { "任务会话只接受 /task 命令。" }
        // Core preserves whitespace in a plain part but trims the string form.
        val message = JSONArray().put(JSONObject().put("type", "plain").put("text", command))
        val payload = JSONObject().put("message", message).put("session_id", sessionId).put("enable_streaming", true)
        onEvent(AstrBotTransportEvent.ChatStarted)
        request(target, SEND, payload, jwt, beforeRequest, onHeaders = { onEvent(AstrBotTransportEvent.HeadersReceived) }) {
            try {
                readAstrBotReply(it, onEvent = onEvent, onText = onText)
            } catch (_: IllegalStateException) {
                throw WhaleTaskOutcomeUnknownException()
            }
        }
    }
}

internal fun loginWhaleTasks(
    target: DesktopSendTarget, username: String, password: String, totp: String, beforeRequest: () -> Unit = {},
): WhaleTaskSession = WhaleTaskSession.login(target, username, password, totp, beforeRequest)

internal fun sendWhaleTask(
    target: DesktopSendTarget, session: WhaleTaskSession, command: String,
    onEvent: (AstrBotTransportEvent) -> Unit, onText: (String) -> Unit, beforeRequest: () -> Unit = {},
) = session.send(target, command, onEvent, onText, beforeRequest)

private fun readObject(connection: HttpURLConnection): JSONObject {
    check(connection.contentType?.substringBefore(';')?.trim().equals("application/json", true)) {
        "Dashboard 接口未返回 JSON。"
    }
    return connection.inputStream.use {
        val bytes = it.readNBytes(65537)
        check(bytes.size <= 65536) { "Dashboard 响应过长。" }
        JSONObject(bytes.toString(Charsets.UTF_8))
    }
}

private fun <T> request(
    target: DesktopSendTarget, path: String, body: JSONObject? = null, jwt: String? = null,
    beforeRequest: () -> Unit, onHeaders: () -> Unit = {}, read: (HttpURLConnection) -> T,
): T {
    require(target.profileId.startsWith("launcher:") && target.allowLoopbackHttp) { "请选择已验证的本机 Launcher 连接。" }
    val base = runCatching { endpoint(target.address, true) }.getOrNull()
    require(base != null && base.scheme == "http" && base.host == "127.0.0.1" && base.rawPath == "/api/v1/chat") {
        "任务连接需要本机 Launcher 的回环地址。"
    }
    // Dashboard also returns a Set-Cookie header. Never give it to a process-wide cookie store.
    check(CookieHandler.getDefault() == null) { "任务连接需要独立的 Dashboard 登录会话。" }
    val connection = base.resolve(path).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
    connection.apply {
        instanceFollowRedirects = false
        useCaches = false
        connectTimeout = 12_000
        readTimeout = if (path == SEND) 90_000 else 15_000
        requestMethod = if (body == null) "GET" else "POST"
        setRequestProperty("Accept", if (path == SEND) "text/event-stream" else "application/json")
        if (jwt != null) setRequestProperty("Authorization", "Bearer $jwt")
    }
    val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
    if (bytes != null) {
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }
    target.checkTarget()
    beforeRequest()
    try {
        if (bytes != null) connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        onHeaders()
        when (status) {
            in 200..299 -> return read(connection)
            401 -> throw WhaleTaskLoginRequiredException(if (path == LOGIN)
                "登录失败，请检查用户名、密码和动态验证码。" else "Dashboard 登录已失效，请重新登录。")
            403 -> error("当前 Dashboard 账号无权使用此任务会话，请检查插件绑定。")
            404 -> error("找不到任务接口，请检查 AstrBot 和任务插件版本。")
            in 300..399 -> error("任务接口返回重定向，请检查本机 Launcher 连接。")
            else -> {
                if (path == SEND && (status in 500..599 || status == -1)) throw WhaleTaskOutcomeUnknownException()
                error("任务接口返回 HTTP $status。")
            }
        }
    } catch (_: JSONException) {
        if (path == SEND) throw WhaleTaskOutcomeUnknownException()
        error("任务接口返回了无法识别的响应。")
    } catch (_: IOException) {
        if (path == SEND) throw WhaleTaskOutcomeUnknownException()
        error("无法连接本机 Dashboard，请检查 Launcher。")
    } finally {
        connection.disconnect()
    }
}
