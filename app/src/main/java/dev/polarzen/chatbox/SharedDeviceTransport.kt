package dev.polarzen.chatbox

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class DeviceBinding(
    val address: String, val instance: String, val device: String, val token: String, val owner: Boolean,
) {
    init {
        require(instance.isNotBlank() && instance.none(Char::isISOControl))
        require(UUID.fromString(device).toString() == device)
        require(token.isNotEmpty() && token.all { it.code in 33..126 })
    }

    override fun toString() = "DeviceBinding(instance=$instance, device=$device, owner=$owner)"

    companion object {
        fun create(address: String, instance: String, owner: Boolean = false) = DeviceBinding(
            address, instance, UUID.randomUUID().toString(),
            (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", ""), owner,
        )
    }
}

internal class DeviceAddressFailure : Exception("设备服务地址未通过身份验证。")
internal data class DevicePreview(val instance: String, val pcProfiles: Int, val pcSessions: Int, val expiresIn: Long)
internal data class DeviceMe(val instance: String, val device: String, val owner: Boolean, val address: String)
internal data class DeviceProfile(
    val profile: String, val sourceId: String, val base: String, val username: String, val name: String,
    val originDevice: String, val upstream: String, val available: Boolean,
)
internal data class DeviceSession(
    val profile: String, val sid: String, val title: String, val activity: Long, val total: Int,
    val imported: Int, val reason: String, val originDevice: String, val inboxStatus: String = "",
)
internal data class DeviceDirectory(val sessions: List<DeviceSession>, val more: Boolean, val revision: String)
internal data class DeviceOperation(val profile: String, val sid: String, val turn: SharedTurn, val dispatch: Boolean = false)
internal class DeviceContextAwaiting : Exception()

internal class SharedDeviceTransport(private val allowLoopback: Boolean) {
    private val http = SharedChatTransport(allowLoopback)

    private fun origin(value: String, loopback: Boolean = allowLoopback): String {
        val uri = URI(sharedAddress(value, loopback))
        require(uri.rawPath.isEmpty() && !uri.rawAuthority.endsWith(':'))
        val scheme = uri.scheme.lowercase()
        val port = uri.port
        val defaultPort = if (scheme == "https") 443 else 80
        return scheme + "://" + uri.host.lowercase() + if (port == -1 || port == defaultPort) "" else ":$port"
    }

    suspend fun health(address: String): String {
        val result = http.request(origin(address), "device/health", null)
        require(result.getInt("version") == 1)
        return result.getString("instance").also { require(it.isNotBlank()) }
    }

    suspend fun preview(address: String, code: String): DevicePreview {
        val r = http.request(origin(address), "device/preview", null, JSONObject().put("code", code))
        return DevicePreview(r.getString("instance"), r.getInt("pcProfiles"), r.getInt("pcSessions"), r.getLong("expiresIn"))
    }

    suspend fun join(
        binding: DeviceBinding, code: String, username: String, phoneProfiles: Int, phoneSessions: Int,
    ): DeviceMe {
        require(!binding.owner)
        val body = JSONObject().put("code", code).put("device", binding.device).put("token", binding.token)
            .put("username", username).put("phoneProfiles", phoneProfiles).put("phoneSessions", phoneSessions)
        readMe(binding, http.request(origin(binding.address), "device/join", null, body))
        return me(binding)
    }

    private fun readMe(binding: DeviceBinding, r: JSONObject): DeviceMe {
        val me = DeviceMe(r.getString("instance"), r.getString("device"), r.getBoolean("owner"), r.getString("address"))
        if (me.instance != binding.instance || me.device != binding.device || me.owner != binding.owner)
            throw DeviceAddressFailure()
        return me
    }

    // Phone endpoints can be reassigned between runs. Authenticate the destination before every bearer
    // request. The signed published address also prevents a different tunnel from relaying the challenge.
    private suspend fun signedAddress(binding: DeviceBinding, target: String): String = addressCheck {
        val nonce = UUID.randomUUID().toString()
        val r = http.request(target, "device/prove", null, JSONObject().put("device", binding.device).put("nonce", nonce))
        require(r.getString("instance") == binding.instance && r.getString("device") == binding.device)
        val published = r.getString("address")
        val message = listOf("chatbox-device-address-v1", binding.instance, binding.device, published, nonce)
            .joinToString("\n").toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(binding.token.toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val signature = r.getString("proof")
        require(signature.matches(Regex("[a-f0-9]{64}")))
        val bytes = ByteArray(32) { signature.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        require(MessageDigest.isEqual(mac.doFinal(message), bytes))
        if (published.isEmpty()) "" else origin(published)
    }

    private suspend fun <T> addressCheck(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw DeviceAddressFailure()
    }

    private suspend fun request(binding: DeviceBinding, path: String, body: JSONObject? = null): JSONObject {
        val address = origin(binding.address)
        if (binding.owner) {
            if (URI(address).scheme != "http") throw DeviceAddressFailure()
        } else if (signedAddress(binding, address) != address) throw DeviceAddressFailure()
        return http.request(address, "device/$path", binding.token, body)
    }

    private suspend fun meAt(binding: DeviceBinding, address: String): DeviceMe {
        val who = readMe(binding, http.request(address, "device/me", binding.token))
        if (!binding.owner && addressCheck { origin(who.address) } != address) throw DeviceAddressFailure()
        return who
    }

    private suspend fun confirmCandidate(binding: DeviceBinding, address: String): DeviceMe {
        if (signedAddress(binding, address) != address) throw DeviceAddressFailure()
        return meAt(binding, address)
    }

    suspend fun me(binding: DeviceBinding): DeviceMe {
        val current = origin(binding.address)
        if (binding.owner) {
            if (URI(current).scheme != "http") throw DeviceAddressFailure()
            return meAt(binding, current)
        }
        val published = signedAddress(binding, current)
        if (published.isEmpty()) throw DeviceAddressFailure()
        return if (published == current) meAt(binding, current) else confirmCandidate(binding, published)
    }

    suspend fun verifyAddress(binding: DeviceBinding, address: String): DeviceBinding {
        if (binding.owner) throw DeviceAddressFailure()
        val candidate = addressCheck { origin(address) }
        confirmCandidate(binding, candidate)
        return binding.copy(address = candidate)
    }

    suspend fun refreshAddress(binding: DeviceBinding): DeviceBinding {
        if (binding.owner) throw DeviceAddressFailure()
        val who = me(binding)
        return binding.copy(address = addressCheck { origin(who.address) })
    }

    suspend fun invite(binding: DeviceBinding, pcProfiles: Int, pcSessions: Int): ProfileInvite {
        val r = request(binding, "invite", JSONObject().put("pcProfiles", pcProfiles).put("pcSessions", pcSessions))
        return ProfileInvite(r.getString("code"), r.getLong("expiresIn"))
    }

    suspend fun endpoint(binding: DeviceBinding, address: String): String {
        val candidate = origin(address, loopback = false)
        val r = request(binding, "endpoint", JSONObject().put("address", candidate))
        return origin(r.getString("address"), loopback = false).also { require(it == candidate) }
    }

    suspend fun theme(binding: DeviceBinding, since: Long? = null): SharedThemeSnapshot {
        require(since == null || since >= 0)
        val response = request(binding, if (since == null) "theme" else "theme?since=$since")
        val revision = response.getLong("revision")
        require(revision >= 0)
        val changed = response.has("theme")
        require(changed || revision == since)
        val value = if (changed && !response.isNull("theme"))
            ChatTheme.fromJson(response.getJSONObject("theme")) else null
        return SharedThemeSnapshot(revision, value, changed)
    }

    suspend fun updateTheme(binding: DeviceBinding, expectedRevision: Long, theme: ChatTheme?): Long {
        require(expectedRevision >= 0)
        val body = JSONObject().put("expectedRevision", expectedRevision)
            .put("theme", theme?.toJson() ?: JSONObject.NULL)
        return request(binding, "theme", body).getLong("revision").also { require(it >= 0) }
    }

    suspend fun devices(binding: DeviceBinding): List<ProfileDevice> {
        val rows = request(binding, "devices").getJSONArray("devices")
        return List(rows.length()) { i ->
            val r = rows.getJSONObject(i)
            ProfileDevice(r.getString("device"), r.getString("name"))
        }
    }

    suspend fun revoke(binding: DeviceBinding, device: String) {
        request(binding, "revoke", JSONObject().put("device", device))
    }

    private fun readProfile(r: JSONObject) = DeviceProfile(
        r.getString("profile"), r.getString("sourceId"), r.getString("base"), r.getString("username"),
        r.getString("name"), r.getString("originDevice"), if (r.isNull("upstream")) "" else r.getString("upstream"),
        r.getBoolean("available"),
    )

    suspend fun profiles(binding: DeviceBinding): List<DeviceProfile> {
        val rows = request(binding, "profiles").getJSONArray("profiles")
        return List(rows.length()) { readProfile(rows.getJSONObject(it)) }
    }

    suspend fun profile(
        binding: DeviceBinding, sourceId: String, base: String, username: String, name: String,
        upstream: String? = null, legacyToken: String? = null,
    ): DeviceProfile {
        val body = JSONObject().put("sourceId", sourceId).put("base", base).put("username", username).put("name", name)
        upstream?.let { body.put("upstream", it) }
        legacyToken?.let { body.put("legacyToken", it) }
        return readProfile(request(binding, "profile", body))
    }

    private fun readSession(r: JSONObject) = DeviceSession(
        r.getString("profile"), r.getString("sid"), r.getString("title"), r.getLong("activity"),
        r.getInt("total"), r.getInt("imported"), r.getString("reason"), r.getString("originDevice"), r.optString("inboxStatus"),
    )

    suspend fun sessions(
        binding: DeviceBinding, offset: Int = 0, limit: Int = 100, revision: String? = null,
    ): DeviceDirectory {
        val path = "sessions?offset=$offset&limit=$limit" + (revision?.let { "&revision=" + encode(it) } ?: "")
        val r = request(binding, path)
        val rows = r.getJSONArray("sessions")
        val sessions = List(rows.length()) { readSession(rows.getJSONObject(it)) }
        val more = r.getBoolean("more")
        val version = r.get("revision")
        require(!more || sessions.isNotEmpty())
        require(version is Number || version is String && version.isNotEmpty())
        if (revision != null && revision != version.toString()) throw SharedHttpFailure(409)
        return DeviceDirectory(sessions, more, version.toString())
    }

    suspend fun session(
        binding: DeviceBinding, profile: String, sid: String, title: String, activity: Long, total: Int,
        legacyToken: String? = null,
    ): DeviceSession {
        val body = target(profile, sid).put("title", title).put("activity", activity).put("total", total)
        legacyToken?.let { body.put("legacyToken", it) }
        return readSession(request(binding, "session", body))
    }

    suspend fun importTurns(
        binding: DeviceBinding, profile: String, sid: String, start: Int, turns: List<ProfileImportTurn>,
    ): Int {
        val rows = JSONArray()
        for (turn in turns) rows.put(JSONObject().put("id", turn.id).put("question", turn.question)
            .put("answer", turn.answer).put("status", turn.status))
        return request(binding, "import", target(profile, sid).put("start", start).put("turns", rows)).getInt("imported")
    }

    suspend fun turns(binding: DeviceBinding, profile: String, sid: String, after: Long = 0L, limit: Int = 50): SharedPage {
        val path = "turns?${query(profile, sid)}&after=$after&limit=$limit"
        return SharedPage.read(request(binding, path), after)
    }

    suspend fun turn(binding: DeviceBinding, profile: String, sid: String, id: String): SharedTurn =
        SharedTurn.read(request(binding, "turn?${query(profile, sid)}&id=${encode(id)}")).also { require(it.id == id) }

    suspend fun chatPreferences(binding: DeviceBinding, profile: String, sid: String): ConversationPreferences =
        readChatPreferences(request(binding, "chat-preferences?${query(profile, sid)}"))

    suspend fun updateChatPreferences(
        binding: DeviceBinding, profile: String, sid: String,
        expected: ConversationPreferences, desired: ConversationPreferences,
    ): ConversationPreferences {
        val diff = conversationPreferenceChanges(expected, desired)
        if (diff.changes.length() == 0) return chatPreferences(binding, profile, sid)
        val body = target(profile, sid).put("expected", diff.expected).put("changes", diff.changes)
        return readChatPreferences(request(binding, "chat-preferences", body))
    }

    private fun readChatPreferences(json: JSONObject): ConversationPreferences {
        require(listOf("profileName", "peerName", "recentTurns", "pins", "remindAfter").all(json::has))
        return ConversationPreferences.fromJson(json)
    }

    suspend fun claimContextReminder(binding: DeviceBinding, profile: String, sid: String, threshold: Int): Boolean {
        require(threshold in 1..10000)
        val result = request(binding, "claim-context-reminder", target(profile, sid).put("threshold", threshold))
        return result.get("claimed").let { require(it is Boolean); it }
    }

    suspend fun observeContextNotice(
        binding: DeviceBinding, profile: String, sid: String, kind: String, through: Long, active: Boolean,
    ): Boolean {
        require(kind == "capacity" || kind == "rolling")
        require(through >= 0)
        val body = target(profile, sid).put("kind", kind).put("through", through).put("active", active)
        val claimed = request(binding, "context-notice", body).get("claimed")
        require(claimed is Boolean)
        return claimed
    }

    private fun readOperation(r: JSONObject, id: String): DeviceOperation {
        if (r.optString("contextState") == "pending") {
            require(r.getString("id") == id && r.getString("profile").isNotEmpty() && r.getString("sid").isNotEmpty())
            throw DeviceContextAwaiting()
        }
        val turn = SharedTurn.read(r.getJSONObject("turn"))
        require(turn.id == id)
        return DeviceOperation(r.getString("profile"), r.getString("sid"), turn)
    }

    private suspend fun submit(
        binding: DeviceBinding, path: String, profile: String, sid: String, id: String, question: String,
        revision: String? = null,
    ): DeviceOperation {
        val body = target(profile, sid).put("id", id).put("question", question)
        if (revision != null) body.put("revision", revision)
        val r = request(binding, path, body)
        if (r.has("contextState")) require(revision != null && path == "send" && r.getString("profile") == profile &&
            r.getString("sid") == sid && r.getString("question") == question)
        val op = readOperation(r, id).also {
            require(it.profile == profile && it.sid == sid && it.turn.question == question)
        }
        if (path != "media-begin") return op
        val dispatch = r.getBoolean("dispatch")
        require(!dispatch || op.turn.status == "running")
        return op.copy(dispatch = dispatch)
    }

    suspend fun start(binding: DeviceBinding, profile: String, sid: String, id: String, question: String) =
        submit(binding, "start", profile, sid, id, question)

    suspend fun contextPreview(binding: DeviceBinding, profile: String, sid: String, question: String): AstrBotContextPreview =
        AstrBotContextPreview.read(request(binding, "context-preview", target(profile, sid).put("question", question)))
            .copy(networkMessage = question)

    suspend fun send(binding: DeviceBinding, profile: String, sid: String, id: String, question: String,
                     revision: String? = null) =
        submit(binding, "send", profile, sid, id, question, revision)

    suspend fun beginMedia(binding: DeviceBinding, profile: String, sid: String, id: String, question: String) =
        submit(binding, "media-begin", profile, sid, id, question)

    suspend fun endMedia(
        binding: DeviceBinding, profile: String, sid: String, id: String, answer: String, status: String,
    ): DeviceOperation {
        require(status == "complete" || status == "unknown")
        val body = target(profile, sid).put("id", id).put("answer", answer).put("status", status)
        return readOperation(request(binding, "media-end", body), id).also {
            require(it.profile == profile && it.sid == sid && it.turn.answer == answer && it.turn.status == status)
        }
    }

    suspend fun operation(binding: DeviceBinding, id: String): DeviceOperation =
        readOperation(request(binding, "operation?id=${encode(id)}"), id)

    suspend fun remove(binding: DeviceBinding, profile: String, sid: String) {
        request(binding, "remove", target(profile, sid))
    }

    private fun target(profile: String, sid: String) = JSONObject().put("profile", profile).put("sid", sid)
    private fun query(profile: String, sid: String) = "profile=${encode(profile)}&sid=${encode(sid)}"
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
