package dev.polarzen.chatbox

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val MOBILE_REPLY_LIMIT = 20 * 1024 * 1024
internal const val MOBILE_RECORD_RATE = 16_000

internal fun mobilePcmHeader(sampleBytes: Int): ByteArray {
    require(sampleBytes in 2..(MOBILE_REPLY_LIMIT - 44) && sampleBytes % 2 == 0)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + sampleBytes)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(MOBILE_RECORD_RATE)
        putInt(MOBILE_RECORD_RATE * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(sampleBytes)
    }.array()
}

/** Validate physical chunk boundaries and derive duration without loading or decoding samples. */
internal fun mobileWavDuration(file: File): Long = RandomAccessFile(file, "r").use { input ->
    val length = input.length()
    fun invalid(): Nothing = throw IllegalArgumentException("WAV 格式无效或文件已损坏。")
    fun intLE() = Integer.reverseBytes(input.readInt())
    fun shortLE() = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
    fun tag() = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
    if (length !in 44L..MOBILE_REPLY_LIMIT.toLong() || tag() != "RIFF" ||
        intLE().toLong() != length - 8 || tag() != "WAVE") invalid()
    var frameBytes = 0
    var sampleRate = 0
    var channels = 0
    var duration: Long? = null
    while (input.filePointer < length) {
        if (length - input.filePointer < 8) invalid()
        val id = tag()
        val size = intLE()
        val end = input.filePointer + size
        if (size < 0 || end > length) invalid()
        when (id) {
            "fmt " -> {
                if (size < 16 || frameBytes != 0) invalid()
                val format = shortLE()
                channels = shortLE()
                sampleRate = intLE()
                val byteRate = intLE()
                frameBytes = shortLE()
                val bits = shortLE()
                val validBits = when (format) {
                    1 -> bits in listOf(8, 16, 24, 32)
                    3 -> bits == 32 || bits == 64
                    6, 7 -> bits == 8
                    else -> false
                }
                if (!validBits || channels !in 1..8 || sampleRate !in 1..384_000 ||
                    frameBytes != channels * (bits / 8) || byteRate != frameBytes * sampleRate) invalid()
            }
            "data" -> {
                if (duration != null || frameBytes == 0 || size <= 0 || size % frameBytes != 0) invalid()
                val frames = size.toLong() / frameBytes
                if (frames * channels * 2 > MOBILE_REPLY_LIMIT) invalid()
                duration = frames * 1000 / sampleRate
            }
        }
        val paddedEnd = if (id == "data" && end == length) end else end + (size and 1)
        if (paddedEnd > length) invalid()
        input.seek(paddedEnd)
    }
    duration ?: invalid()
}
