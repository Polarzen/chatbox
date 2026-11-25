package dev.polarzen.chatbox

import org.json.JSONArray
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Decrypt an existing room binding only when device history migration needs its owner proof. */
internal fun readLegacyRoomRecord(store: DesktopStore, key: SessionKey, sourceBase: String): String? {
    val origin = if (key.profileId.startsWith("launcher:") && sourceBase.isNotEmpty() &&
        URI(sourceBase).let { it.scheme == "http" && it.host == "127.0.0.1" && it.rawPath.isNullOrEmpty() }
    ) "launcher-loopback" else sourceBase
    val entropy = MessageDigest.getInstance("SHA-256").digest(
        JSONArray(listOf("shared-chat-v1", key.profileId, key.sessionId, origin)).toString().toByteArray(Charsets.UTF_8)
    )
    val field = "shared-" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
    val saved = store.sharedRecord(key, field) ?: return null
    val parts = saved.split('.')
    require(parts.size == 3)
    val decoder = Base64.getDecoder()
    val raw = WindowsDpapi.transform(entropy, decoder.decode(parts[0]), protect = false)
    val secret = try { SecretKeySpec(raw, "AES") } finally { raw.fill(0) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, decoder.decode(parts[1])))
    cipher.updateAAD(entropy)
    val bytes = cipher.doFinal(decoder.decode(parts[2]))
    return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
}

internal fun desktopSharedSeedTurn(entry: Entry): SharedSeedTurn {
    val question = buildList {
        // Older histories sometimes kept only the display name. Use its extension as
        // the type hint without copying a private name or path into shared history.
        val references = entry.attachmentReferences.ifEmpty {
            listOfNotNull(entry.attachmentReference ?: entry.attachmentDisplayName)
        }
        for (reference in references) add(if (reference.endsWith(".wav", true)) "[语音附件]" else "[图片附件]")
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
    return SharedSeedTurn(question, answer, if (entry.status == "已完成") "complete" else "unknown")
}
