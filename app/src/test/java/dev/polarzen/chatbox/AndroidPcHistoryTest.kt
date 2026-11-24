package dev.polarzen.chatbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPcHistoryTest {
    @Test fun outgoingLimitUsesUtf8Bytes() {
        assertFalse(pcQuestionTooLarge("a".repeat(65536)))
        assertTrue(pcQuestionTooLarge("a".repeat(65537)))
        val atLimit = "🧪".repeat(16384)
        assertFalse(pcQuestionTooLarge(atLimit))
        assertTrue(pcQuestionTooLarge(atLimit + "问"))
    }

    @Test fun sameIdRetryReleasesDraftOnlyOnDefiniteSizeRejection() {
        assertTrue(pcDraftRejected(413, retriedAfterMissingLookup = true))
        assertTrue(pcDraftRejected(409, retriedAfterMissingLookup = false))
        for (status in listOf(400, 401, 403, 409, 422, 429, 500, 503)) {
            assertFalse(pcDraftRejected(status, retriedAfterMissingLookup = true))
        }
    }
}
