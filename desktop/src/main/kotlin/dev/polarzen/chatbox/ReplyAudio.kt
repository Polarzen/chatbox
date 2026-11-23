package dev.polarzen.chatbox

import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

private const val MAX_WAV_BYTES = 20 * 1024 * 1024

internal class ReplyAudioCache(val directory: Path) {
    fun read(profileId: String, attachmentId: String): Path? = try {
        val path = audioPath(profileId, attachmentId)
        readWav(path)
        path
    } catch (_: Exception) {
        null
    }

    fun save(profileId: String, attachmentId: String, bytes: ByteArray): Path {
        require(bytes.size in 1..MAX_WAV_BYTES) { "WAV exceeds the 20 MiB limit or is empty" }
        val copy = bytes.copyOf()
        parseWav(copy)
        Files.createDirectories(directory)
        val target = audioPath(profileId, attachmentId)
        val temporary = Files.createTempFile(directory, "reply-audio-", ".tmp")
        try {
            Files.write(temporary, copy)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    private fun audioPath(profileId: String, attachmentId: String): Path {
        val profile = profileId.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(4).putInt(profile.size).array())
        digest.update(profile)
        val hash = HexFormat.of().formatHex(digest.digest(attachmentId.toByteArray(Charsets.UTF_8)))
        return directory.resolve("$hash.wav")
    }
}

private data class Wav(val bytes: ByteArray, val offset: Int, val size: Int, val format: AudioFormat)

private fun readWav(path: Path): Wav {
    require(Files.size(path) in 1..MAX_WAV_BYTES.toLong())
    return Files.newInputStream(path).use { parseWav(it.readNBytes(MAX_WAV_BYTES + 1)) }
}

// Check the physical payload before handing any frame length to the audio device.
private fun parseWav(bytes: ByteArray): Wav {
    require(bytes.size in 44..MAX_WAV_BYTES) { "Invalid WAV size" }
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
    require(tag(0) == "RIFF" && tag(8) == "WAVE" && header.getInt(4) == bytes.size - 8) { "Invalid WAV header" }
    var offset = 12
    var blockAlign = 0
    var audio: Wav? = null
    while (offset <= bytes.size - 8) {
        val tag = tag(offset)
        val size = header.getInt(offset + 4)
        offset += 8
        require(size in 0..(bytes.size - offset)) { "Truncated WAV chunk" }
        when (tag) {
            "fmt " -> {
                require(blockAlign == 0 && size >= 16) { "Invalid WAV format chunk" }
                blockAlign = header.getShort(offset + 12).toInt() and 0xffff
                require(blockAlign > 0) { "Invalid WAV block alignment" }
            }
            "data" -> {
                require(audio == null) { "Duplicate WAV data chunk" }
                require(blockAlign > 0 && size > 0) { "Missing WAV format or samples" }
                val format = AudioSystem.getAudioFileFormat(bytes.inputStream()).format
                val channels = format.channels
                require(channels in 1..8 && format.sampleRate in 1f..384000f &&
                    format.sampleSizeInBits in setOf(8, 16, 24, 32, 64) &&
                    format.frameSize == channels * (format.sampleSizeInBits / 8) &&
                    blockAlign == format.frameSize && size % format.frameSize == 0) { "Invalid WAV sample format" }
                val decodedSize = size.toLong() / format.frameSize * channels * 2
                require(decodedSize <= MAX_WAV_BYTES) { "Decoded WAV exceeds the 20 MiB limit" }
                audio = Wav(bytes, offset, size, format)
            }
        }
        offset += size
        // Some WAV writers omit the final alignment byte when the samples end at EOF.
        if (offset != bytes.size || tag != "data") offset += size and 1
    }
    require(offset == bytes.size) { "Truncated WAV chunk header or padding" }
    return requireNotNull(audio) { "Missing WAV samples" }
}

internal suspend fun playReplyAudio(path: Path, acquireClip: () -> Clip = { AudioSystem.getClip() }) {
    withContext(Dispatchers.IO) {
        val wav = readWav(path)
        val source = wav.bytes.inputStream(wav.offset, wav.size)
        val target = AudioFormat(wav.format.sampleRate, 16, wav.format.channels, true, false)
        AudioInputStream(source, wav.format, (wav.size / wav.format.frameSize).toLong()).use { input ->
            AudioSystem.getAudioInputStream(target, input).use { pcm ->
                currentCoroutineContext().ensureActive()
                acquireClip().use { clip ->
                    val ended = CompletableDeferred<Unit>()
                    clip.addLineListener { event -> if (event.type == LineEvent.Type.STOP) ended.complete(Unit) }
                    clip.open(pcm)
                    currentCoroutineContext().ensureActive()
                    clip.start()
                    ended.await()
                }
            }
        }
    }
}

@Composable
internal fun ReplyAudioPlayback(path: Path) {
    var playing by remember(path) { mutableStateOf(false) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path, playing) {
        if (playing) {
            try {
                playReplyAudio(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.localizedMessage?.takeIf { it.isNotBlank() } ?: "音频格式或播放设备不可用"
            }
            playing = false
        }
    }
    TextButton(onClick = { failure = null; playing = !playing }) {
        Text(if (playing) "停止播放" else "播放语音")
    }
    failure?.let { Text("语音播放失败：$it") }
}
