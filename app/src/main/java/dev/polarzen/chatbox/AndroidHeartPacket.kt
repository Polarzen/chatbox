package dev.polarzen.chatbox

/** Bluetooth Heart Rate Measurement (0x2A37), including its optional fields. */
internal fun androidHeartBpm(packet: ByteArray): Int? {
    if (packet.size < 2) return null
    val flags = packet[0].toInt()
    if (flags and 0x06 == 0x04) return null // Contact sensing supported, no skin contact.
    val wide = flags and 0x01 != 0
    val fixedSize = 1 + (if (wide) 2 else 1) + (if (flags and 0x08 != 0) 2 else 0)
    if (packet.size < fixedSize) return null
    if (flags and 0x10 != 0) {
        val rrBytes = packet.size - fixedSize
        if (rrBytes < 2 || rrBytes % 2 != 0) return null
    }
    val low = packet[1].toInt() and 0xff
    val bpm = if (wide) low or ((packet[2].toInt() and 0xff) shl 8) else low
    return bpm.takeIf { it > 0 }
}
