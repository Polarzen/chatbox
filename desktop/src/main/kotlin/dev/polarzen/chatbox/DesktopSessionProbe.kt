package dev.polarzen.chatbox

import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val DESKTOP_SESSION_PAGE_SIZE = 100

/** Read AstrBot's Open API session index for the supplied identity. */
internal fun fetchDesktopSessions(base: String, token: String, username: String): Set<String> {
    require(token.isNotBlank() && token.none(Char::isWhitespace)) { "请输入有效的聊天 API 密钥。" }
    require(username.isNotBlank()) { "聊天身份为空，无法查询会话。" }

    val sessionsEndpoint = try {
        endpoint(base, allowLoopbackHttp = true).toASCIIString() + "/sessions"
    } catch (_: Exception) {
        throw IllegalArgumentException("请输入有效的 HTTPS 地址，或本机 127.0.0.1 HTTP 地址。")
    }
    val encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8)
    val found = linkedSetOf<String>()
    var page = 1
    var total: Int? = null

    while (total == null || (page - 1L) * DESKTOP_SESSION_PAGE_SIZE < total) {
        val target = URI.create(
            "$sessionsEndpoint?page=$page&page_size=$DESKTOP_SESSION_PAGE_SIZE&platform_id=webchat&username=$encodedUsername"
        )
        val connection = openDesktopSessionConnection(target)
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            if (status !in 200..299) throw desktopSessionHttpError(status)

            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val response = try {
                JSONObject(body)
            } catch (_: Exception) {
                throw IllegalStateException("AstrBot 会话接口返回了无法识别的响应。")
            }
            if (response.optString("status") != "ok") {
                throw IllegalStateException("AstrBot 拒绝了会话查询，请检查身份和 API 密钥。")
            }
            val data = response.optJSONObject("data")
                ?: throw IllegalStateException("AstrBot 会话接口响应缺少数据。")
            val sessions = data.optJSONArray("sessions")
                ?: throw IllegalStateException("AstrBot 会话接口未返回会话列表。")
            val responsePage = data.optInt("page", -1)
            val responsePageSize = data.optInt("page_size", -1)
            val responseTotal = data.optInt("total", -1)
            if (responsePage != page || responsePageSize != DESKTOP_SESSION_PAGE_SIZE || responseTotal < 0) {
                throw IllegalStateException("AstrBot 会话接口返回了不支持的分页数据。")
            }
            if (total != null && responseTotal != total) {
                throw IllegalStateException("AstrBot 会话列表在查询期间发生变化，请重试。")
            }
            total = responseTotal

            for (index in 0 until sessions.length()) {
                val item = sessions.optJSONObject(index)
                    ?: throw IllegalStateException("AstrBot 会话接口返回了无效的会话记录。")
                val sessionId = item.optString("session_id")
                if (item.optString("creator") == username &&
                    item.optString("platform_id") == "webchat" &&
                    sessionId.isNotBlank()
                ) {
                    found += sessionId
                }
            }
            page++
        } catch (_: IOException) {
            throw IllegalStateException("无法连接到 AstrBot 会话接口，请检查地址和网络。")
        } finally {
            connection.disconnect()
        }
    }
    return found
}

private fun openDesktopSessionConnection(target: URI) =
    (if (target.scheme.equals("http", true)) target.toURL().openConnection(Proxy.NO_PROXY)
    else target.toURL().openConnection()) as java.net.HttpURLConnection

private fun desktopSessionHttpError(status: Int): IllegalStateException = IllegalStateException(
    when (status) {
        401 -> "聊天 API 密钥无效或已撤销。"
        403 -> "API 密钥缺少聊天权限。"
        404 -> "找不到会话查询接口，请检查 AstrBot 版本和服务地址。"
        in 300..399 -> "服务返回重定向，请直接填写最终服务地址。"
        else -> "AstrBot 会话查询失败，服务器返回 HTTP $status。"
    }
)
