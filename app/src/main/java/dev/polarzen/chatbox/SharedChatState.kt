package dev.polarzen.chatbox

import org.json.JSONObject
import java.util.UUID

// Stored single-room records provide ownership evidence for device history imports.
internal data class SharedBinding(val address: String, val room: String, val instance: String, val token: String, val owner: Boolean) {
    init {
        require(room.isNotBlank() && instance.isNotBlank() && token.isNotEmpty() && token.all { it.code in 33..126 })
    }
}

internal data class SharedPending(val id: String, val question: String, val draft: String = question)
internal data class SharedInvite(val code: String, val expiresAt: Long)

internal data class SharedLocal(
    val binding: SharedBinding? = null,
    val draft: String = "",
    val pending: SharedPending? = null,
    val invite: SharedInvite? = null,
    val revoked: Boolean = false
) {
    fun encode(): String? {
        if (revoked) return JSONObject().put("revoked", true).toString()
        val b = binding ?: return null
        val value = JSONObject().put("address", b.address).put("room", b.room).put("instance", b.instance)
            .put("token", b.token).put("owner", b.owner).put("draft", draft)
        pending?.let {
            val row = JSONObject().put("id", it.id).put("question", it.question)
            if (it.draft != it.question) row.put("draft", it.draft)
            value.put("pending", row)
        }
        invite?.let { value.put("invite", JSONObject().put("code", it.code).put("expiresAt", it.expiresAt)) }
        return value.toString()
    }

    companion object {
        fun decode(raw: String?, allowLoopback: Boolean): SharedLocal {
            if (raw == null) return SharedLocal()
            val value = JSONObject(raw)
            if (value.optBoolean("revoked")) return SharedLocal(revoked = true)
            return SharedLocal(
                binding = SharedBinding(sharedAddress(value.getString("address"), allowLoopback),
                    value.getString("room"), value.getString("instance"), value.getString("token"), value.getBoolean("owner")),
                draft = value.optString("draft"),
                pending = value.optJSONObject("pending")?.let {
                    val question = it.getString("question")
                    SharedPending(it.getString("id").also(UUID::fromString), question, it.optString("draft", question))
                },
                invite = value.optJSONObject("invite")?.let {
                    SharedInvite(it.getString("code"), it.getLong("expiresAt"))
                }
            )
        }
    }
}
