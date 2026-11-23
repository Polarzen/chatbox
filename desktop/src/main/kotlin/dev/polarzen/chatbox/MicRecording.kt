package dev.polarzen.chatbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

private const val RATE = 16000
private const val FRAME_BYTES = 2
private const val HEADER_BYTES = 44

internal data class MicRecordingResult(val path: Path?, val notice: String?)

internal class MicRecording private constructor() {
    private val completion = CompletableDeferred<MicRecordingResult>()
    val result: Deferred<MicRecordingResult> get() = completion
    @Volatile private var stopping = false
    @Volatile private var discarding = false
    @Volatile private var sampleBytes = 0
    val elapsedMillis: Long get() = sampleBytes.toLong() * 1000 / (RATE * FRAME_BYTES)

    fun stop() { stopping = true }
    @Synchronized fun cancel() { discarding = true; stop() }

    @Synchronized private fun finish(result: MicRecordingResult) {
        if (discarding) {
            result.path?.let { Files.deleteIfExists(it) }
            completion.complete(MicRecordingResult(null, null))
        } else completion.complete(result)
    }

    companion object {
        fun start(
            directory: Path,
            byteLimit: Int = MAX_WAV_BYTES,
            input: (AudioFormat) -> TargetDataLine = { AudioSystem.getTargetDataLine(it) },
        ): MicRecording {
            require(byteLimit in HEADER_BYTES + FRAME_BYTES..MAX_WAV_BYTES)
            val recording = MicRecording()
            thread(name = "Chatbox microphone", isDaemon = true) {
                try {
                    recording.finish(recording.capture(directory, byteLimit, input))
                } catch (e: Exception) {
                    recording.completion.complete(MicRecordingResult(null, "录音失败：${e.message ?: e.javaClass.simpleName}"))
                }
            }
            return recording
        }
    }

    private fun capture(directory: Path, byteLimit: Int, input: (AudioFormat) -> TargetDataLine): MicRecordingResult {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "recording-", ".part")
        val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
        val capacity = (byteLimit - HEADER_BYTES) / FRAME_BYTES * FRAME_BYTES
        var notice: String? = null
        try {
            RandomAccessFile(temporary.toFile(), "rw").use { out ->
                out.seek(HEADER_BYTES.toLong())
                try {
                    if (!stopping) input(format).use { line ->
                        line.open(format)
                        line.start()
                        val buffer = ByteArray(4096)
                        var lastSamples = System.nanoTime()
                        while (!stopping && sampleBytes < capacity) {
                            // available() is the byte count guaranteed readable without blocking.
                            val count = minOf(line.available(), buffer.size, capacity - sampleBytes) / FRAME_BYTES * FRAME_BYTES
                            if (count > 0) {
                                val read = line.read(buffer, 0, count)
                                check(read in FRAME_BYTES..count && read % FRAME_BYTES == 0) { "麦克风已断开或返回无效采样" }
                                out.write(buffer, 0, read)
                                sampleBytes += read
                                lastSamples = System.nanoTime()
                            } else {
                                check(line.isOpen && System.nanoTime() - lastSamples < 5_000_000_000L) { "麦克风没有返回采样" }
                                Thread.sleep(10)
                            }
                        }
                        if (stopping && !discarding && sampleBytes < capacity) {
                            // DirectTDL.read returns zero after stop(). Freeze the readable frame
                            // count once, copy that finite tail, then stop before writing to disk.
                            val available = minOf(line.available(), capacity - sampleBytes)
                            val tail = ByteArray(available / FRAME_BYTES * FRAME_BYTES)
                            var copied = 0
                            try {
                                while (!discarding && copied < tail.size) {
                                    val count = tail.size - copied
                                    val read = line.read(tail, copied, count)
                                    check(read in FRAME_BYTES..count && read % FRAME_BYTES == 0) { "麦克风已断开或返回无效采样" }
                                    copied += read
                                }
                            } finally {
                                try {
                                    line.stop()
                                } finally {
                                    if (!discarding && copied > 0) {
                                        out.write(tail, 0, copied)
                                        sampleBytes += copied
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    notice = "录音中断：${e.message ?: e.javaClass.simpleName}"
                }
                if (discarding) return MicRecordingResult(null, null)
                if (sampleBytes == 0) return MicRecordingResult(null, notice ?: "没有录到有效采样")
                if (sampleBytes == capacity) notice = "已达到录音附件大小上限"
                // A failed sample write may have left an incomplete tail.
                out.setLength((HEADER_BYTES + sampleBytes).toLong())
                out.seek(0)
                out.write(wavHeader(sampleBytes))
                out.fd.sync()
            }
            validatedAudio(temporary)
            if (discarding) return MicRecordingResult(null, null)
            val target = directory.resolve("${UUID.randomUUID()}.wav")
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            return MicRecordingResult(target, notice)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun wavHeader(bytes: Int): ByteArray = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray(Charsets.US_ASCII))
    putInt(36 + bytes)
    put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    putInt(16)
    putShort(1) // PCM
    putShort(1) // mono
    putInt(RATE)
    putInt(RATE * FRAME_BYTES)
    putShort(FRAME_BYTES.toShort())
    putShort(16)
    put("data".toByteArray(Charsets.US_ASCII))
    putInt(bytes)
}.array()
