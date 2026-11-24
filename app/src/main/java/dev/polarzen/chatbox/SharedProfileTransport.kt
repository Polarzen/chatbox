package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal data class ProfileBinding(
    val address: String,
    val profile: String,
    val instance: String,
    val token: String,
    val owner: Boolean,
    val name: String,
) {
    init {
        require(profile.isNotBlank() && instance.isNotBlank())
        require(token.isNotEmpty() && token.all { it.code in 33..126 })
    }
}

internal data class ProfileSession(
    val sid: String,
    val title: String,
    val activity: Long,
    val total: Int,
    val imported: Int,
    val reason: String,
) {
    companion object {
        fun read(value: JSONObject) = ProfileSession(
            value.getString("sid"), value.getString("title"), value.getLong("activity"),
            value.getInt("total"), value.getInt("imported"), value.getString("reason"),
        )
    }
}

internal data class ProfileImportTurn(val id: String, val question: String, val answer: String, val status: String)
internal data class ProfileDevice(val id: String, val name: String)
internal data class ProfileInvite(val code: String, val expiresIn: Long)

internal data class ProfileDirectory(
    val sessions: List<ProfileSession>,
    val more: Boolean,
    val revision: String,
    val complete: Boolean,
    val expected: Int,
    val issue: String,
    val name: String,
)

internal class SharedProfileTransport(private val allowLoopback: Boolean) {
    private val http = SharedChatTransport(allowLoopback)

    suspend fun create(
        address: String, code: String, sourceBase: String, username: String, sourceId: String, name: String,
    ): ProfileBinding {
        val body = JSONObject().put("code", code).put("sourceBase", sourceBase).put("username", username)
            .put("sourceId", sourceId).put("name", name)
        return connect(address, "create", body, owner = true)
    }

    suspend fun join(address: String, code: String): ProfileBinding =
        connect(address, "join", JSONObject().put("code", code), owner = false)

    private suspend fun connect(address: String, path: String, body: JSONObject, owner: Boolean): ProfileBinding {
        val base = sharedAddress(address, allowLoopback)
        val reply = http.request(base, "profile/$path", null, body)
        require(reply.getBoolean("owner") == owner)
        return ProfileBinding(base, reply.getString("profile"), reply.getString("instance"),
            reply.getString("token"), owner, reply.getString("name"))
    }

    private suspend fun request(binding: ProfileBinding, path: String, body: JSONObject? = null) =
        http.request(binding.address, "profile/$path", binding.token, body)

    suspend fun invite(binding: ProfileBinding): ProfileInvite {
        val reply = request(binding, "invite", JSONObject())
        return ProfileInvite(reply.getString("code"), reply.getLong("expiresIn"))
    }

    suspend fun devices(binding: ProfileBinding): List<ProfileDevice> {
        val devices = request(binding, "devices").getJSONArray("devices")
        return List(devices.length()) { i ->
            val device = devices.getJSONObject(i)
            ProfileDevice(device.getString("id"), device.getString("name"))
        }
    }

    suspend fun revoke(binding: ProfileBinding, device: String) {
        request(binding, "revoke", JSONObject().put("device", device))
    }

    suspend fun status(binding: ProfileBinding, complete: Boolean, expected: Int, issue: String) {
        request(binding, "status", JSONObject().put("complete", complete).put("expected", expected).put("issue", issue))
    }

    suspend fun upsert(
        binding: ProfileBinding, sid: String, title: String, activity: Long, total: Int, legacyToken: String? = null,
    ): ProfileSession {
        val body = JSONObject().put("sid", sid).put("title", title).put("activity", activity).put("total", total)
        if (legacyToken != null) body.put("legacyToken", legacyToken)
        return ProfileSession.read(request(binding, "session", body)).also { require(it.sid == sid) }
    }

    suspend fun importTurns(binding: ProfileBinding, sid: String, start: Int, turns: List<ProfileImportTurn>): Int {
        val rows = JSONArray()
        for (turn in turns) {
            rows.put(JSONObject().put("id", turn.id).put("question", turn.question)
                .put("answer", turn.answer).put("status", turn.status))
        }
        val reply = request(binding, "import", JSONObject().put("sid", sid).put("start", start).put("turns", rows))
        return reply.getInt("imported")
    }

    suspend fun remove(binding: ProfileBinding, sid: String) {
        request(binding, "remove", JSONObject().put("sid", sid))
    }

    suspend fun sessions(
        binding: ProfileBinding, offset: Int = 0, limit: Int = 100, revision: String? = null,
    ): ProfileDirectory {
        val path = "sessions?offset=$offset&limit=$limit" + (revision?.let { "&revision=" + encode(it) } ?: "")
        val reply = request(binding, path)
        val rows = reply.getJSONArray("sessions")
        val sessions = List(rows.length()) { i -> ProfileSession.read(rows.getJSONObject(i)) }
        val more = reply.getBoolean("more")
        require(!more || sessions.isNotEmpty())
        val version = reply.get("revision")
        require(version is Number || version is String && version.isNotEmpty())
        return ProfileDirectory(sessions, more, version.toString(), reply.getBoolean("complete"),
            reply.getInt("expected"), reply.getString("issue"), reply.getString("name"))
    }

    suspend fun turns(binding: ProfileBinding, sid: String, after: Long = 0L, limit: Int = 50): SharedPage {
        val path = "turns?sid=" + encode(sid) + "&after=$after&limit=$limit"
        return SharedPage.read(request(binding, path), after)
    }

    suspend fun turn(binding: ProfileBinding, sid: String, id: String): SharedTurn {
        val path = "turn?sid=" + encode(sid) + "&id=" + encode(id)
        return SharedTurn.read(request(binding, path)).also { require(it.id == id) }
    }

    suspend fun send(binding: ProfileBinding, sid: String, id: String, question: String): SharedTurn {
        val body = JSONObject().put("sid", sid).put("id", id).put("question", question)
        return SharedTurn.read(request(binding, "send", body)).also { require(it.id == id) }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
