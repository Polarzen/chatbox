package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.*

class ReplyAudioTest {
    @get:Rule val temp = TemporaryFolder()

    @OptIn(ExperimentalTestApi::class)
    @Test fun cachedDurationStaysVisibleWhenOutputDeviceIsUnavailable() = runComposeUiTest {
        val path = ReplyAudioCache(temp.root.toPath()).save("profile", "voice", wav(frames = 16000 * 8))
        val acquisitions = AtomicInteger()
        setContent {
            Column {
                ReplyAudioPlayback(path) {
                    acquisitions.incrementAndGet()
                    throw LineUnavailableException("没有输出设备")
                }
            }
        }
        waitUntilExactlyOneExists(hasText("0:08"), 5000)
        onNodeWithText("0:08").assertIsDisplayed()
        assertEquals(0, acquisitions.get())
        onNodeWithText("播放语音").performClick()
        val error = "语音播放失败：没有输出设备"
        waitUntilExactlyOneExists(hasText(error), 5000)
        onNodeWithText(error).assertIsDisplayed()
        onNodeWithText("0:08").assertIsDisplayed()
        onNodeWithText("播放语音").assertIsDisplayed()
        assertEquals(1, acquisitions.get())
    }

    @Test fun durationUsesCompleteFramesAndIgnoresMetadataWithoutADevice() {
        val dir = temp.root.toPath()
        val cache = ReplyAudioCache(dir)
        val fixtures = listOf(
            Triple(wav(frames = 1), 0L, "<1 秒"),
            Triple(wav(frames = 16000 * 8 + 15999), 8999L, "0:08"),
            Triple(wav(frames = 16000 * 65, channels = 2), 65000L, "1:05"),
        )
        for ((index, fixture) in fixtures.withIndex()) {
            val (samples, millis, label) = fixture
            val bytes = samples + "JUNK".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size - 8)
            val path = cache.save("p", index.toString(), bytes)
            val audio = requireNotNull(ReplyAudioCache(dir).readAudio("p", index.toString()))
            assertEquals(path, audio.path)
            assertEquals(millis, audio.durationMillis)
            assertEquals(label, formatAudioDuration(audio.durationMillis))
        }
    }

    @Test fun captureReleasesInputAndKeepsOnlyFinalizedSamples() = runBlocking {
        val dir = temp.root.toPath()
        fun device(disconnect: Boolean, closed: CompletableDeferred<Unit>): TargetDataLine {
            var remaining = 320
            return Proxy.newProxyInstance(TargetDataLine::class.java.classLoader, arrayOf(TargetDataLine::class.java)) { _, method, args ->
                when (method.name) {
                    "open", "start", "stop" -> null
                    "isOpen" -> true
                    "available" -> if (remaining == 0 && disconnect) throw IllegalStateException("disconnected") else remaining
                    "read" -> {
                        val n = minOf(remaining, args[2] as Int)
                        (args[0] as ByteArray).fill(17, 0, n)
                        remaining -= n
                        n
                    }
                    "close" -> { closed.complete(Unit); null }
                    else -> error("Unexpected call ${method.name}")
                }
            } as TargetDataLine
        }
        withTimeout(8000) {
            for (mode in listOf("stop", "cancel", "limit", "disconnect")) {
                val closed = CompletableDeferred<Unit>()
                val recording = MicRecording.start(dir, if (mode == "limit") 364 else 2048) {
                    device(mode == "disconnect", closed)
                }
                while (recording.elapsedMillis == 0L && !recording.result.isCompleted) delay(5)
                when (mode) {
                    "stop" -> recording.stop()
                    "cancel" -> recording.cancel()
                }
                val result = recording.result.await()
                assertTrue(closed.isCompleted)
                if (mode == "cancel") {
                    assertNull(result.path)
                    assertNull(result.notice)
                } else {
                    val path = requireNotNull(result.path)
                    assertEquals(10L, validatedAudio(path).durationMillis)
                    assertArrayEquals(ByteArray(320) { 17 }, Files.readAllBytes(path).drop(44).toByteArray())
                    assertEquals(mode != "stop", result.notice != null)
                }
            }
            val denied = MicRecording.start(dir) { throw SecurityException("denied") }.result.await()
            assertNull(denied.path)
            assertTrue(denied.notice.orEmpty().contains("denied"))
            assertEquals(3L, Files.list(dir).use { it.count() })
        }
    }

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

    @Test fun stopPreservesAllCompleteFramesInOneSnapshot() = runBlocking {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val samples = ByteArray(12289) { (it % 251).toByte() }
        var position = 0
        var snapshots = 0
        var stopped = false
        var closed = false
        val line = Proxy.newProxyInstance(TargetDataLine::class.java.classLoader, arrayOf(TargetDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "open" -> null
                "start" -> {
                    started.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS))
                    null
                }
                "available" -> { check(++snapshots == 1); samples.size }
                "read" -> {
                    check(!stopped)
                    val count = minOf(args[2] as Int, 2048)
                    samples.copyInto(args[0] as ByteArray, args[1] as Int, position, position + count)
                    position += count
                    count
                }
                "stop" -> { stopped = true; null }
                "close" -> { closed = true; null }
                else -> error("Unexpected call ${method.name}")
            }
        } as TargetDataLine
        val recording = MicRecording.start(temp.root.toPath()) { line }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        recording.stop()
        proceed.countDown()
        val result = withTimeout(5000) { recording.result.await() }
        assertNull(result.notice)
        assertTrue(stopped && closed)
        val bytes = Files.readAllBytes(requireNotNull(result.path))
        assertArrayEquals(samples.copyOf(12288), bytes.copyOfRange(44, bytes.size))
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
        assertNull(cache.readAudio("profile", "voice"))
        cache.save("profile", "voice", original)
        assertEquals(10L, requireNotNull(cache.readAudio("profile", "voice")).durationMillis)
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

    private fun wav(bits: Int = 16, frames: Int = 160, channels: Int = 1): ByteArray {
        val format = AudioFormat(16000f, bits, channels, bits != 8, false)
        val samples = ByteArray(frames * format.frameSize) { if (bits == 8) 0x80.toByte() else 0 }
        return ByteArrayOutputStream().use { out ->
            AudioInputStream(samples.inputStream(), format, frames.toLong()).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, out)
            }
            out.toByteArray()
        }
    }
}
