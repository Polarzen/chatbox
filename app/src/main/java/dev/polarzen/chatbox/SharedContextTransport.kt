package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal const val CUSTOM_CONTEXT_MEDIA_NOTICE = "自定义上下文目前仅支持文本。请移除附件，或恢复默认上下文。"
internal const val CONTEXT_UNAVAILABLE = "此 AstrBot 服务未提供可用的上下文预览。"
internal const val CONTEXT_CHANGED = "上下文或草稿已更改，请重新预览。"

internal data class ContextPreviewMessage(val role: String, val text: String, val seq: Long? = null,
                                         val checkpoint: String? = null) {
    val displayText: String get() = text.ifEmpty { "已选中这条历史消息，本机没有可核对的正文。" }
}
internal data class AstrBotContextPreview(
    val supported: Boolean, val revision: String, val canSend: Boolean, val reason: String,
    val selected: List<ContextPreviewMessage>, val estimatedTokens: Long? = null, val maxTokens: Long? = null,
    val fittingVisibleMessagesEstimate: List<ContextPreviewMessage>? = null, val networkMessage: String? = null,
    val nearLimit: Boolean = false,
) {
    companion object {
        fun unavailable(reason: String) = AstrBotContextPreview(false, "", false, reason, emptyList())

        fun read(result: JSONObject, context: JSONObject? = null): AstrBotContextPreview {
            val supported = result.get("supported") as Boolean
            val reason = result.get("reason") as String
            if (!supported) return unavailable(reason.ifBlank { CONTEXT_UNAVAILABLE })
            val revision = result.get("revision") as String
            val canSend = result.get("canSend") as Boolean
            require(!canSend || revision.isNotBlank())
            val messages = result.getJSONArray("selected")
            val evidence = buildMap<Long, JSONObject> {
                for (field in listOf("pins", "hints")) {
                    val hints = context?.optJSONArray(field) ?: continue
                    for (index in 0 until hints.length()) {
                        val hint = hints.getJSONObject(index)
                        put(hint.getLong("seq"), hint)
                    }
                }
            }
            fun readMessages(rows: JSONArray) = List(rows.length()) { i ->
                val entry = rows.getJSONObject(i)
                val role = entry.get("role") as String
                require(role in setOf("user", "assistant"))
                val seq = (entry.opt("seq") as? Number)?.toLong()?.takeIf { it > 0 }
                val text = (entry.opt("text") as? String).orEmpty()
                val known = evidence[seq]?.optString(if (role == "user") "question" else "answer")
                val visible = if (seq != null && (context == null || known == text)) text else ""
                ContextPreviewMessage(role, visible, seq.takeIf { visible.isNotEmpty() },
                    (entry.opt("checkpoint") as? String)?.takeIf(String::isNotBlank))
            }
            return AstrBotContextPreview(true, revision, canSend, reason, readMessages(messages),
                (result.opt("estimatedTokens") as? Number)?.toLong(), (result.opt("maxTokens") as? Number)?.toLong(),
                result.optJSONArray("fittingVisibleMessagesEstimate")?.let(::readMessages),
                nearLimit = result.opt("nearLimit") == true)
        }
    }
}

/** Build pin evidence from completed turns in this conversation only. AstrBot verifies each hint. */
internal fun astrBotContext(preferences: ConversationPreferences, turns: List<ContextTurn>): JSONObject {
    val prefs = preferences.normalized()
    val recent = requireNotNull(prefs.recentTurns) { "请先选择保留最近多少轮。" }
    val bySeq = turns.associateBy { it.seq }
    fun hint(turn: ContextTurn): JSONObject = JSONObject().put("seq", turn.seq)
        .put("question", turn.question).put("answer", turn.answer).also { row ->
            turn.checkpoint?.takeIf(String::isNotBlank)?.let { row.put("checkpoint", it) }
        }
    val pins = JSONArray()
    for (pin in prefs.pins) {
        val turn = bySeq[pin.seq]
        require(turn != null && turn.status == "complete" &&
            (if (pin.role == "user") turn.question else turn.answer).isNotBlank()) { "固定消息已变化，请重新选择。" }
        pins.put(hint(turn).put("role", pin.role))
    }
    val context = JSONObject().put("recent", recent).put("pins", pins)
    val hints = JSONArray()
    var remaining = 512 * 1024
    for (turn in turns.sortedByDescending { it.seq }.asSequence().filter { it.status == "complete" }.take(recent)) {
        if (turn.question.length + turn.answer.length > remaining || turn.question.isBlank() || turn.answer.isBlank()) continue
        val row = hint(turn)
        val bytes = row.toString().toByteArray(Charsets.UTF_8).size + 1
        if (bytes <= remaining) {
            remaining -= bytes
            hints.put(row)
        }
    }
    if (hints.length() > 0) context.put("hints", hints)
    return context
}

internal fun previewAstrBotContext(
    base: String, token: String, user: String, session: String, message: String,
    context: JSONObject, allowLoopbackHttp: Boolean = false,
): AstrBotContextPreview {
    val target = URI(endpoint(base, allowLoopbackHttp).toASCIIString() + "/context/preview")
    val connection = openAstrBotConnection(target)
    try {
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val bytes = JSONObject().put("username", user).put("session_id", session).put("message", message)
            .put("context", context).toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        if (status !in 200..299) return AstrBotContextPreview.unavailable(when (status) {
            401, 403 -> "服务拒绝上下文预览，请检查聊天权限。"
            else -> CONTEXT_UNAVAILABLE
        })
        if (!connection.contentType?.substringBefore(';')?.trim().equals("application/json", true))
            return AstrBotContextPreview.unavailable(CONTEXT_UNAVAILABLE)
        val maxChars = 4 * 1024 * 1024
        if (connection.contentLengthLong > maxChars) return AstrBotContextPreview.unavailable("上下文预览超过大小限制。")
        val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            buildString {
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(length + count <= maxChars)
                    append(buffer, 0, count)
                }
            }
        }
        val response = JSONObject(raw)
        require(response.getString("status") == "ok")
        return AstrBotContextPreview.read(response.getJSONObject("data"), context).copy(networkMessage = message)
    } catch (_: Exception) {
        return AstrBotContextPreview.unavailable(CONTEXT_UNAVAILABLE)
    } finally {
        connection.disconnect()
    }
}

internal fun sendAstrBotContext(
    base: String, token: String, user: String, session: String, message: String,
    context: JSONObject, revision: String, turnId: String, allowLoopbackHttp: Boolean = false,
    onReplyImage: (String?, Int) -> Unit = { _, _ -> },
    onReplyAudio: (String?, Int) -> Unit = { _, _ -> },
    onEvent: (AstrBotTransportEvent) -> Unit = {},
    onText: (String) -> Unit,
) {
    require(revision.isNotBlank() && UUID.fromString(turnId).toString() == turnId)
    val fields = JSONObject().put("context", context).put("revision", revision).put("turn_id", turnId)
    sendToAstrBotPayload(base, token, user, session, allowLoopbackHttp, onReplyImage, onReplyAudio, onEvent,
        onText, fields) { message }
}
