package dev.polarzen.chatbox

import org.json.JSONObject
import java.util.Base64
import java.util.Locale

internal const val THEME_IMAGE_BYTES = 512 * 1024
internal const val THEME_IMAGE_EDGE = 1600
private const val ENCODED_LIMIT = ((THEME_IMAGE_BYTES + 2) / 3) * 4
private val themeHex = Regex("#[A-Fa-f0-9]{6}")

internal data class ChatTheme(
    val dark: Boolean = false,
    val accent: String? = null,
    val wallpaper: String? = null,
    val blur: Int = 0,
) {
    init {
        require(accent == null || themeHex.matches(accent))
        require(blur in 0..100)
        require(wallpaper == null || wallpaper.length in 4..ENCODED_LIMIT)
    }

    fun toJson(): JSONObject = JSONObject().put("dark", dark)
        .put("accent", accent?.uppercase(Locale.ROOT) ?: JSONObject.NULL)
        .put("wallpaper", wallpaper ?: JSONObject.NULL).put("blur", blur)

    companion object {
        fun fromJson(json: JSONObject): ChatTheme {
            val image = json.get("wallpaper").let { if (it === JSONObject.NULL) null else it as String }
            image?.let(::validateThemeJpeg)
            val accent = json.get("accent").let {
                if (it === JSONObject.NULL) null else (it as String).uppercase(Locale.ROOT)
            }
            return ChatTheme(json.get("dark") as Boolean, accent, image, json.get("blur") as Int)
        }
    }

    override fun toString() = "ChatTheme(dark=$dark, accent=$accent, hasWallpaper=${wallpaper != null}, blur=$blur)"
}

// Check the decoded dimensions before handing a cached or remote image to a platform decoder.
private fun validateThemeJpeg(value: String) {
    require(value.length in 4..ENCODED_LIMIT && value.length % 4 == 0)
    val bytes = Base64.getDecoder().decode(value)
    fun octet(i: Int) = bytes[i].toInt() and 255
    fun word(i: Int) = (octet(i) shl 8) or octet(i + 1)
    require(bytes.size in 4..THEME_IMAGE_BYTES && word(0) == 0xffd8 && word(bytes.size - 2) == 0xffd9)
    var offset = 2
    while (offset + 4 < bytes.size) {
        require(octet(offset++) == 0xff)
        while (offset < bytes.size && octet(offset) == 0xff) offset++
        require(offset + 3 <= bytes.size)
        val marker = octet(offset++)
        val length = word(offset)
        require(length >= 2 && offset + length <= bytes.size)
        if (marker == 0xc0 || marker == 0xc1 || marker == 0xc2) {
            require(length >= 8)
            require(word(offset + 3) in 1..THEME_IMAGE_EDGE && word(offset + 5) in 1..THEME_IMAGE_EDGE)
            return
        }
        require(marker != 0xda && marker != 0xd9)
        offset += length
    }
    throw IllegalArgumentException("无效的背景图片")
}

internal data class SharedThemeSnapshot(val revision: Long, val theme: ChatTheme?, val changed: Boolean)
