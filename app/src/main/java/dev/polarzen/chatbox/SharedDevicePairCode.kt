package dev.polarzen.chatbox

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer

internal data class DevicePairCode(val address: String, val code: String) {
    init {
        require(address == address.trim())
        sharedAddress(address, allowLoopback = false)
        require(code.isNotBlank() && code.none(Char::isISOControl))
    }
}

internal fun encodeDevicePairCode(pair: DevicePairCode): String =
    "chatbox://pair?v=1&url=" + URLEncoder.encode(pair.address, "UTF-8") +
        "&code=" + URLEncoder.encode(pair.code, "UTF-8")

internal fun decodeDevicePairCode(raw: String): DevicePairCode {
    try {
        val uri = URI(URI(raw).toASCIIString())
        require(uri.scheme == "chatbox" && uri.rawAuthority == "pair" && uri.rawPath.isEmpty() && uri.rawFragment == null)
        val fields = mutableMapOf<String, String>()
        for (field in requireNotNull(uri.rawQuery).split('&')) {
            val key = field.substringBefore('=')
            require(key in setOf("v", "url", "code") && key !in fields && '=' in field)
            // Preserve percent-decoded bytes until the UTF-8 decoder checks them.
            val bytes = URLDecoder.decode(field.substringAfter('='), "ISO-8859-1").toByteArray(Charsets.ISO_8859_1)
            fields[key] = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        }
        require(fields["v"] == "1")
        return DevicePairCode(requireNotNull(fields["url"]), requireNotNull(fields["code"]))
    } catch (_: Exception) {
        // URI parser exceptions can include the entire input, including the one-time code.
        throw IllegalArgumentException("二维码不是有效的 Chatbox 设备配对码。")
    }
}
