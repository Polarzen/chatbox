package dev.polarzen.chatbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Keep the draft and binding in the session's atomic history transaction. DPAPI wraps the
 * per-scope data key once, so editing the draft does not launch PowerShell on each keystroke. */
internal class DesktopSharedStorage(
    private val store: DesktopStore,
    private val key: SessionKey,
    sourceBase: String,
) : SharedLocalStorage {
    private val origin = if (key.profileId.startsWith("launcher:") && sourceBase.isNotEmpty() &&
        URI(sourceBase).let { it.scheme == "http" && it.host == "127.0.0.1" && it.rawPath.isNullOrEmpty() }
    ) "launcher-loopback" else sourceBase
    private val entropy = MessageDigest.getInstance("SHA-256").digest(
        JSONArray(listOf("shared-chat-v1", key.profileId, key.sessionId, origin)).toString().toByteArray(Charsets.UTF_8)
    )
    private val field = "shared-" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
    private var dataKey: SecretKey? = null
    private var wrappedKey: String? = null
    private var previous: String? = null

    var bound by mutableStateOf(runCatching { store.sharedRecord(key, field) != null }.getOrDefault(true))
        private set

    @Synchronized
    override fun load(): String? {
        val saved = store.sharedRecord(key, field)
        previous = saved
        dataKey = null
        wrappedKey = null
        if (saved == null) return null
        val parts = saved.split('.')
        require(parts.size == 3)
        val decoder = Base64.getDecoder()
        val raw = WindowsDpapi.transform(entropy, decoder.decode(parts[0]), protect = false)
        val secret = try { SecretKeySpec(raw, "AES") } finally { raw.fill(0) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, decoder.decode(parts[1])))
        cipher.updateAAD(entropy)
        val bytes = cipher.doFinal(decoder.decode(parts[2]))
        dataKey = secret
        wrappedKey = parts[0]
        return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    @Synchronized
    override fun save(value: String?) {
        if (value == null) {
            store.saveSharedRecord(key, field, previous, null)
            previous = null
            wrappedKey = null
            dataKey = null
            bound = false
            return
        }
        if (dataKey == null && bound) load()
        val encoder = Base64.getEncoder()
        val secret = dataKey ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
            val raw = it.encoded
            try { wrappedKey = encoder.encodeToString(WindowsDpapi.transform(entropy, raw, protect = true)) }
            finally { raw.fill(0) }
            dataKey = it
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        cipher.updateAAD(entropy)
        val bytes = value.toByteArray(Charsets.UTF_8)
        val ciphertext = try { cipher.doFinal(bytes) } finally { bytes.fill(0) }
        val saved = listOf(checkNotNull(wrappedKey), encoder.encodeToString(cipher.iv), encoder.encodeToString(ciphertext)).joinToString(".")
        store.saveSharedRecord(key, field, previous, saved)
        previous = saved
        bound = true
    }
}

internal fun desktopSharedSeed(sourceBase: String, username: String, chat: Chat): SharedSeed {
    if (sourceBase.isEmpty()) return SharedSeed("", "", "", emptyList())
    val history = chat.entries.map { entry ->
        val question = buildList {
            val references = entry.attachmentReferences.ifEmpty { listOfNotNull(entry.attachmentReference) }
            if (references.isEmpty() && entry.attachmentDisplayName != null) add("[图片附件]")
            for (path in references) add(if (path.endsWith(".wav", true)) "[语音附件]" else "[图片附件]")
            if (entry.question.isNotEmpty()) add(entry.question)
        }.joinToString("\n")
        val answer = buildString {
            var cursor = 0
            for (attachment in entry.replyAttachments.sortedBy { it.textOffset }) {
                val offset = attachment.textOffset.coerceIn(cursor, entry.answer.length)
                append(entry.answer, cursor, offset)
                append("[${attachment.type.label}附件]")
                cursor = offset
            }
            append(entry.answer, cursor, entry.answer.length)
        }
        SharedSeedTurn(question, answer, if (entry.status == "已完成") "complete" else "unknown")
    }
    return SharedSeed(sourceBase, username, chat.session, history)
}
