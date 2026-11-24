package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReplyNotificationTest {
    private val text = Entry("question", "answer", "已完成")
    private val imageOnly = text.copy(answer = " ", replyAttachments =
        listOf(ReplyAttachment("image", 0, ReplyAttachmentType.IMAGE)))
    private val permit = ReplyToastPermit { true }

    @Test fun persistedTextNotifiesAtTerminalOnceAcrossFurtherMediaCallbacks() {
        var count = 0
        val live = LiveReplyNotification { count++ }
        live.complete(text, persisted = true) { permit }
        assertEquals(1, count)
        live.initialAttachmentSaved()
        live.initialAttachmentSaved()
        live.complete(text, persisted = true) { permit }
        assertEquals(1, count)
    }

    @Test fun mediaOnlyWaitsForBothPersistenceAndInitialCacheSaveInEitherOrder() {
        for (cachedFirst in listOf(true, false)) {
            var count = 0
            var background = true
            val live = LiveReplyNotification { count++ }
            if (cachedFirst) live.initialAttachmentSaved()
            assertEquals(0, count)
            live.complete(imageOnly, persisted = true) { permit.takeIf { background } }
            if (!cachedFirst) {
                assertEquals(0, count)
                background = false
                live.initialAttachmentSaved()
            }
            live.initialAttachmentSaved()
            assertEquals(1, count)
        }
    }

    @Test fun failedUnconfirmedUnpersistedAndForegroundRepliesStaySilent() {
        val delivered = mutableListOf<Entry>()
        for ((entry, persisted, background) in listOf(
            Triple(text.copy(status = "发送失败：连接服务器超时。"), true, true),
            Triple(text.copy(status = "上次回复未确认"), true, true),
            Triple(text, false, true),
            Triple(imageOnly, true, false),
        )) {
            val live = LiveReplyNotification { delivered.add(entry) }
            live.complete(entry, persisted) { permit.takeIf { background } }
            live.initialAttachmentSaved()
        }
        assertTrue(delivered.isEmpty())
    }

    @Test fun cancelledStreamAndCompletedReplyWithoutReadableContentStaySilent() {
        val delivered = mutableListOf<String>()
        val cancelled = LiveReplyNotification { delivered.add("cancelled") }
        cancelled.initialAttachmentSaved()
        val empty = LiveReplyNotification { delivered.add("empty") }
        empty.complete(text.copy(answer = " \n"), persisted = true) { permit }
        val missingImage = LiveReplyNotification { delivered.add("missing") }
        missingImage.complete(imageOnly, persisted = true) { permit }
        assertTrue(delivered.isEmpty())
    }
}
