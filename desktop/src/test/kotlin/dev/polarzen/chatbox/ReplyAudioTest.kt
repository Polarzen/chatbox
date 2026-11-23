package dev.polarzen.chatbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.*

class ReplyAudioTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun cachedWavSurvivesReopenAndStaysScopedToProfile() {
        val dir = temp.root.toPath()
        val cache = ReplyAudioCache(dir)
        val samples = wav()
        val path = cache.save("profile", "../voice", samples)
        assertEquals(dir, path.parent)
        assertTrue(path.fileName.toString().matches(Regex("[0-9a-f]{64}\\.wav")))
        assertArrayEquals(samples, Files.readAllBytes(path))
        assertEquals(path, ReplyAudioCache(dir).read("profile", "../voice"))
        assertNull(cache.read("different", "../voice"))
        assertNotEquals(path, cache.save("different", "../voice", samples))
        assertEquals(path, cache.save("profile", "../voice", wav(frames = 80)))
        assertArrayEquals(wav(frames = 80), Files.readAllBytes(path))
    }

    @Test fun invalidLengthsTruncatedPcmAndOversizeCannotReplaceGoodAudio() {
        val cache = ReplyAudioCache(temp.root.toPath())
        val original = wav()
        val path = cache.save("profile", "voice", original)
        fun edited(offset: Int, value: Int) = original.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
        }
        for (bad in listOf(
            edited(4, Int.MAX_VALUE), edited(40, Int.MAX_VALUE), edited(40, 3), edited(32, 16 shl 16),
            original.copyOf(original.size - 1), byteArrayOf(0), ByteArray(20 * 1024 * 1024 + 1),
        )) {
            assertThrows(IllegalArgumentException::class.java) { cache.save("profile", "voice", bad) }
            assertArrayEquals(original, Files.readAllBytes(path))
        }
        assertThrows(IllegalArgumentException::class.java) {
            cache.save("profile", "voice", wav(bits = 8, frames = 10 * 1024 * 1024 + 1))
        }
        assertArrayEquals(original, Files.readAllBytes(path))
        Files.write(path, edited(40, Int.MAX_VALUE))
        assertNull(cache.read("profile", "voice"))
    }

    @Test fun paddedTrailingChunkSurvivesReopenAndTruncatedChunkCannotReplaceIt() {
        val cache = ReplyAudioCache(temp.root.toPath())
        val samples = wav()
        val good = samples + "JUNK".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 0, 0, 0, 1, 2, 3, 0)
        fun updateRiffLength(bytes: ByteArray): ByteArray = bytes.also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, it.size - 8)
        }
        val path = cache.save("profile", "voice", updateRiffLength(good))
        assertEquals(path, ReplyAudioCache(temp.root.toPath()).read("profile", "voice"))
        for (truncated in listOf(
            good.copyOf(samples.size + 3), // partial chunk header
            good.copyOf(good.size - 2), // partial payload
            good.copyOf(good.size - 1), // missing alignment byte
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                cache.save("profile", "voice", updateRiffLength(truncated))
            }
            assertArrayEquals(good, Files.readAllBytes(path))
        }
    }

    @Test fun playbackNormalizesSamplesAndReleasesClipOnCompletionCancelAndFailure() = runBlocking {
        val cache = ReplyAudioCache(temp.root.toPath())
        val path = cache.save("p", "id", wav(bits = 8))
        repeat(2) {
            val device = TestDevice()
            val playback = launch { playReplyAudio(path) { device.clip } }
            withTimeout(5000) { device.started.await() }
            assertEquals(320, device.pcm.size)
            assertTrue(device.pcm.all { it == 0.toByte() })
            if (it == 0) {
                device.listener.update(LineEvent(device.clip, LineEvent.Type.STOP, 160))
                withTimeout(5000) { playback.join() }
            } else {
                withTimeout(5000) { playback.cancelAndJoin() }
            }
            assertTrue(device.closed)
        }
        val unavailable = TestDevice(failOpen = true)
        assertThrows(LineUnavailableException::class.java) {
            runBlocking { playReplyAudio(path) { unavailable.clip } }
        }
        assertTrue(unavailable.closed)
    }

    @Test fun windowsAudioDevicePlaysLocalWavToCompletion() {
        assumeTrue(System.getenv("CHATBOX_TEST_AUDIO_DEVICE") == "1")
        val path = ReplyAudioCache(temp.root.toPath()).save("p", "voice", wav(frames = 16000))
        lateinit var device: Clip
        runBlocking {
            withTimeout(8000) {
                playReplyAudio(path) { AudioSystem.getClip().also { device = it } }
            }
        }
        assertFalse(device.isOpen)
    }

    private class TestDevice(private val failOpen: Boolean = false) {
        val started = CompletableDeferred<Unit>()
        lateinit var listener: LineListener
        lateinit var pcm: ByteArray
        var closed = false
        val clip = Proxy.newProxyInstance(Clip::class.java.classLoader, arrayOf(Clip::class.java)) { _, method, args ->
            when (method.name) {
                "addLineListener" -> listener = args[0] as LineListener
                "open" -> {
                    if (failOpen) throw LineUnavailableException("Device unavailable")
                    pcm = (args[0] as AudioInputStream).readAllBytes()
                }
                "start" -> started.complete(Unit)
                "close" -> closed = true
                else -> error("Unexpected device call ${method.name}")
            }
            null
        } as Clip
    }

    private fun wav(bits: Int = 16, frames: Int = 160): ByteArray {
        val format = AudioFormat(16000f, bits, 1, bits != 8, false)
        val samples = ByteArray(frames * format.frameSize) { if (bits == 8) 0x80.toByte() else 0 }
        return ByteArrayOutputStream().use { out ->
            AudioInputStream(samples.inputStream(), format, frames.toLong()).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, out)
            }
            out.toByteArray()
        }
    }
}
