package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/** Construct the saved format directly, as a previous desktop release would leave it. */
internal fun editLegacyFixture(path: Path, key: SessionKey, change: (JSONObject, JSONObject) -> Unit) {
    val state = JSONObject(Files.readString(path))
    fun find(rows: JSONArray, id: String) = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
    val profile = find(state.getJSONArray("profiles"), key.profileId)
    change(profile, find(profile.getJSONArray("sessions"), key.sessionId))
    Files.writeString(path, state.toString())
}

internal fun putLegacyRoomFixture(path: Path, key: SessionKey, source: String, record: String) {
    val entropy = MessageDigest.getInstance("SHA-256").digest(
        JSONArray(listOf("shared-chat-v1", key.profileId, key.sessionId, source)).toString().toByteArray(Charsets.UTF_8))
    val field = "shared-" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
    val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val wrapped = WindowsDpapi.transform(entropy, secret.encoded, protect = true)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secret)
    cipher.updateAAD(entropy)
    val encrypted = listOf(wrapped, cipher.iv, cipher.doFinal(record.toByteArray(Charsets.UTF_8)))
        .joinToString(".") { Base64.getEncoder().encodeToString(it) }
    editLegacyFixture(path, key) { _, row -> row.put(field, encrypted) }
}
