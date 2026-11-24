package dev.polarzen.chatbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder

data class SharedSeed(val sourceBase: String, val username: String, val session: String, val history: List<SharedSeedTurn>)
data class SharedSeedTurn(val question: String, val answer: String, val status: String)

internal interface SharedLocalStorage {
    fun load(): String?
    fun save(value: String?)
}

internal enum class SharedConflict { RUNNING, UNRESOLVED, OTHER }
internal class SharedHttpFailure(val status: Int, val conflict: SharedConflict = SharedConflict.OTHER) : Exception()

internal fun sharedAddress(input: String, allowLoopback: Boolean): String {
    val uri = URI(input.trim())
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.port == -1 || uri.port in 1..65535)
    val local = allowLoopback && uri.scheme.equals("http", true) && uri.host == "127.0.0.1" &&
        uri.rawAuthority.matches(Regex("127[.]0[.]0[.]1(:[0-9]+)?"))
    require(uri.scheme.equals("https", true) || local)
    return uri.toASCIIString().trimEnd('/')
}

internal data class SharedTurn(val seq: Long, val id: String, val question: String, val answer: String, val status: String) {
    companion object {
        fun read(value: JSONObject): SharedTurn = SharedTurn(
            value.getLong("seq").also { require(it > 0) }, value.getString("id").also { require(it.isNotBlank()) },
            value.getString("question"), value.getString("answer"),
            value.getString("status").also { require(it == "running" || it == "complete" || it == "unknown") }
        )
    }
}

internal data class SharedPage(val turns: List<SharedTurn>, val more: Boolean)

internal class SharedChatTransport(private val allowLoopback: Boolean) {
    suspend fun connect(address: String, code: String, seed: SharedSeed?): SharedBinding {
        val body = JSONObject().put("code", code)
        if (seed != null) {
            val history = JSONArray()
            seed.history.forEach {
                history.put(JSONObject().put("question", it.question).put("answer", it.answer).put("status", it.status))
            }
            body.put("sourceBase", seed.sourceBase).put("username", seed.username)
                .put("session", seed.session).put("history", history)
        }
        val reply = request(address, if (seed == null) "join" else "create", null, body)
        return SharedBinding(address, reply.getString("room"), reply.getString("instance"),
            reply.getString("token"), reply.getBoolean("owner")).also { require(seed == null || it.owner) }
    }

    suspend fun page(binding: SharedBinding, after: Long): SharedPage {
        val value = request(binding.address, "turns?after=$after&limit=50", binding.token)
        val rows = value.getJSONArray("turns")
        val turns = ArrayList<SharedTurn>(rows.length())
        var previous = 0L
        for (i in 0 until rows.length()) {
            val row = SharedTurn.read(rows.getJSONObject(i))
            require(row.seq >= previous)
            previous = row.seq
            if (row.seq > after) turns.add(row)
        }
        val more = value.getBoolean("more")
        require(!more || turns.isNotEmpty())
        return SharedPage(turns, more)
    }

    suspend fun turn(binding: SharedBinding, id: String): SharedTurn {
        val path = "turn?id=" + URLEncoder.encode(id, "UTF-8")
        return SharedTurn.read(request(binding.address, path, binding.token)).also { require(it.id == id) }
    }

    suspend fun send(binding: SharedBinding, pending: SharedPending) {
        request(binding.address, "send", binding.token, JSONObject().put("id", pending.id).put("question", pending.question))
    }

    suspend fun manage(binding: SharedBinding, path: String): JSONObject =
        request(binding.address, path, binding.token, JSONObject())

    private suspend fun request(address: String, path: String, token: String?, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val uri = URI(sharedAddress(address, allowLoopback) + "/v1/" + path)
            val url = uri.toURL()
            val conn = (if (uri.scheme.equals("http", true)) url.openConnection(Proxy.NO_PROXY)
                else url.openConnection()) as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val data = body.toString().toByteArray(Charsets.UTF_8)
                    // Streaming mode also prevents HttpURLConnection from replaying a buffered POST.
                    conn.setFixedLengthStreamingMode(data.size)
                    conn.outputStream.use { it.write(data) }
                }
                val status = conn.responseCode
                if (status == 409) {
                    val conflict = runCatching {
                        val raw = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            val buffer = CharArray(1025)
                            var count = 0
                            while (count < buffer.size) {
                                val n = reader.read(buffer, count, buffer.size - count)
                                if (n < 0) break
                                count += n
                            }
                            if (count < buffer.size) String(buffer, 0, count) else ""
                        }
                        when (raw?.let { JSONObject(it).optString("error") }) {
                            "会话已有回复正在生成，请稍后再发。" -> SharedConflict.RUNNING
                            "上游执行结果未知，请服务主人确认 AstrBot 已空闲并运行 resolve。" -> SharedConflict.UNRESOLVED
                            else -> SharedConflict.OTHER
                        }
                    }.getOrDefault(SharedConflict.OTHER)
                    throw SharedHttpFailure(status, conflict)
                }
                if (status !in 200..299) throw SharedHttpFailure(status)
                val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            } finally {
                conn.disconnect()
            }
        }
}
