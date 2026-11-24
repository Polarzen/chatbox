package dev.polarzen.chatbox

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidMediaWavTest {
    @get:Rule val folder = TemporaryFolder()

    private fun saved(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }

    @Test fun recordedHeaderMatchesReferenceMonoPcmAndDurationSurvivesReopening() {
        val bytes = mobilePcmHeader(48_000) + ByteArray(48_000)
        // Python wave, one channel, 16 bits, 16 kHz, 24,000 silent frames.
        val reference = "UklGRqS7AABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YYC7AAA="
        assertArrayEquals(Base64.getDecoder().decode(reference), bytes.copyOf(44))
        assertEquals(1_500L, mobileWavDuration(saved(bytes)))
    }

    @Test fun acceptsPaddedMetadataAndStereoReplyAtAnotherRate() {
        val bytes = ByteBuffer.allocate(44 + 10 + 176_400).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEJUNK".toByteArray()); putInt(1); putShort(0)
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(44_100); putInt(176_400)
            putShort(4); putShort(16); put("data".toByteArray()); putInt(176_400)
        }.array()
        assertEquals(1000L, mobileWavDuration(saved(bytes)))
    }

    @Test fun rejectsTruncationInconsistentAlignmentAndDisguisedNonWavReplies() {
        val good = mobilePcmHeader(8) + ByteArray(8)
        val invalid = listOf(good.copyOf(good.size - 1), good.clone().apply { this[32] = 4 },
            good.clone().apply { this[40] = 40 }, "<html>a server error</html>".toByteArray())
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { mobileWavDuration(saved(it)) } }
    }
}
