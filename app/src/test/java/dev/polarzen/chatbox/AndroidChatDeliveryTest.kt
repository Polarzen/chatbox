package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class AndroidChatDeliveryTest {
    private val turn = MobileTurn("turn", "question")

    @Test fun taskBoundaryAndReadinessAreCheckedBeforeSending() {
        fun issue(text: String, base: String = "https://example.com", key: String = "key",
                  files: List<MobileAsset> = emptyList()) =
            mobileSendIssue(base, "session", key, "user", text, files)
        for (command in listOf("/task", "/task new", "/task\nnew", "/task\tnew")) {
            val message = issue(command, base = "", key = "").orEmpty()
            assertTrue(message.contains("Windows Launcher") && message.contains("Dashboard"))
        }
        assertNull(issue("/tasks"))
        assertTrue(issue("message", base = "http://example.com").orEmpty().contains("HTTPS"))
        assertTrue(issue("message", key = "two keys").orEmpty().contains("密钥"))
        assertTrue(issue("  ").orEmpty().contains("消息"))
        val audio = File.createTempFile("chatbox-audio", ".wav")
        try {
            val files = listOf(MobileAsset(audio.absolutePath, "audio", "录音"))
            assertTrue(issue("", files = files).orEmpty().contains("为空"))
            audio.writeBytes(byteArrayOf(1))
            assertNull(issue("", files = files))
            assertTrue(audio.delete())
            assertTrue(issue("", files = files).orEmpty().contains("重新选择"))
        } finally { audio.delete() }
    }

    @Test fun textAndMediaAreSavedInOrderAndNotifyOnceAfterTerminalSave() {
        val snapshots = mutableListOf<MobileTurn>()
        val reply = MobileReplyDelivery(turn, snapshots::add)
        reply.event(AstrBotTransportEvent.ChatStarted)
        reply.event(AstrBotTransportEvent.FirstContent)
        reply.text("par")
        reply.media("audio", "voice-id")
        reply.text("partial")
        reply.media("image", "img-id")
        var notifications = 0
        val fetches = mutableListOf<String>()
        val note = reply.finish(null, {
            {
                assertEquals("已完成", snapshots.last().status)
                assertTrue(fetches.isEmpty())
                notifications++
            }
        }) { kind, id ->
            fetches += id
            MobileAsset("/cache/$id", kind, id)
        }
        assertEquals("", note)
        assertEquals(1, notifications)
        assertTrue(snapshots.any { it.status == "回复中" && it.answer == "par" && it.replies.size == 1 })
        assertEquals("partial", snapshots.last().answer)
        assertEquals(listOf("voice-id", "img-id"), fetches)
        assertEquals(listOf("audio", "image"), snapshots.last().replies.map { it.kind })
    }

    @Test fun mediaOnlyNotificationsRequirePersistedReadableContentAndCurrentEligibility() {
        for (transition in listOf("none", "foreground", "toggle", "permission", "removedProfile", "cacheFailure", "writeFailure")) {
            val gate = MobileNotificationGate().apply { setForeground(false) }
            val snapshots = mutableListOf<MobileTurn>()
            val reply = MobileReplyDelivery(turn) {
                if (transition == "writeFailure" && it.replies.any { asset -> asset.path.isNotEmpty() })
                    throw IOException("private storage error")
                snapshots += it
            }
            reply.media("image", "image")
            reply.media("audio", "audio")
            var notifications = 0
            var permitted = true
            var profileAvailable = true
            val fetched = mutableListOf<String>()
            val note = reply.finish(null, {
                gate.capture { permitted }?.let { permit ->
                    { gate.show(permit, { profileAvailable && permitted }) {
                        assertTrue(snapshots.last().replies.any { it.path.isNotEmpty() })
                        notifications++
                    } }
                }
            }) { kind, id ->
                fetched += id
                when (transition) {
                    "foreground" -> { gate.setForeground(true); gate.setForeground(false) }
                    "toggle" -> { gate.setEnabled(false); gate.setEnabled(true) }
                    "permission" -> permitted = false
                    "removedProfile" -> profileAvailable = false
                    "cacheFailure" -> throw IOException("private server body")
                }
                MobileAsset("/cache/$id", kind, id)
            }
            assertEquals(transition, listOf("image", "audio"), fetched)
            assertEquals(transition, if (transition == "none") 1 else 0, notifications)
            assertFalse(note.contains("private"))
        }
    }

    @Test fun foregroundCompletionsMissedMediaAndFailedStreamsStaySilent() {
        val gate = MobileNotificationGate()
        assertNull(gate.capture { true })
        gate.setForeground(false)
        assertNull(gate.capture { false })
        val snapshots = mutableListOf<MobileTurn>()
        val reply = MobileReplyDelivery(turn, snapshots::add)
        reply.media("audio", null)
        assertTrue(reply.finish(null, { { error("unexpected notification") } }) { _, _ ->
            error("missing attachment id")
        }.contains("编号"))
        assertEquals("已完成", snapshots.last().status)

        val interrupted = MobileReplyDelivery(turn, snapshots::add)
        interrupted.event(AstrBotTransportEvent.ChatStarted)
        interrupted.text("last text")
        interrupted.media("image", "saved")
        val note = interrupted.finish(IOException("token=private"), { error("failed send requested a notification") }) { kind, id ->
            MobileAsset("/cache/$id", kind, id)
        }
        assertEquals("last text", snapshots.last().answer)
        assertTrue(snapshots.last().replies.last().path.isNotBlank())
        assertTrue(note.contains("未确认"))
        assertFalse(note.contains("private"))
    }

    @Test fun uploadAndDownloadPermissionsSurfaceWithoutLosingTextOrImplyingChatWasSent() {
        val snapshots = mutableListOf<MobileTurn>()
        val sender = MobileReplyDelivery(turn.copy(sent = listOf(MobileAsset("/one", "audio", "voice"))), snapshots::add)
        sender.event(AstrBotTransportEvent.UploadStarted(0, 1))
        val upload = "缺少文件上传权限，请为 API 密钥启用 file 权限。"
        val failed = sender.finish(IllegalStateException(upload), { error("unexpected capture") }) { _, _ -> error("unexpected fetch") }
        assertTrue(failed.contains("未发送") && failed.contains(upload))
        assertEquals("上传中 1/1", snapshots.first().status)
        val receiver = MobileReplyDelivery(turn, snapshots::add)
        receiver.text("text")
        receiver.media("audio", "id")
        val download = "缺少语音下载权限，请为 API 密钥启用 file 权限。"
        val partial = receiver.finish(null, { null }) { _, _ -> throw IllegalStateException(download) }
        assertEquals(download, partial)
        assertEquals("text", snapshots.last().answer)
        assertEquals(download, snapshots.last().replies.single().name)
        assertEquals("已完成", snapshots.last().status)
    }

    @Test fun manualDownloadUsesTheStoredAttachmentScopeAndLeavesOtherMessagesIntact() {
        val first = MobileAsset("/cache/image", "image", "图片", remoteId = "i")
        val missing = MobileAsset("", "audio", "权限不足", remoteId = "a")
        val saved = turn.copy(answer = "reply", status = "已完成", replies = listOf(first, missing))
        var fetches = 0
        var updated: MobileTurn? = null
        val download = { kind: String, id: String ->
            fetches++
            assertEquals("audio", kind)
            assertEquals("a", id)
            MobileAsset("/cache/audio", kind, "语音")
        }
        for ((existing, other) in listOf(null to missing, saved to missing.copy(remoteId = "other"),
            saved to missing.copy(kind = "image"))) {
            assertTrue(retryMobileReplyAsset(existing, other, download) { updated = it }.contains("找不到"))
        }
        assertEquals(0, fetches)
        assertNull(updated)
        assertEquals("", retryMobileReplyAsset(saved, missing, download) { updated = it })
        assertEquals(1, fetches)
        assertEquals(saved.copy(replies = listOf(first, MobileAsset("/cache/audio", "audio", "语音", remoteId = "a"))), updated)
    }

    @Test fun anExplicitChatPermissionRejectionDiffersFromAnUnknownOutcome() {
        fun failed(failure: Exception): String {
            val delivery = MobileReplyDelivery(turn) { }
            delivery.event(AstrBotTransportEvent.ChatStarted)
            return delivery.finish(failure, { error("unexpected notification") }) { _, _ -> error("unexpected fetch") }
        }
        val explicit = failed(IllegalStateException("权限不足，请检查聊天权限。"))
        assertTrue(explicit.contains("被拒绝") && explicit.contains("权限不足"))
        val unknown = failed(IOException("private-token"))
        assertTrue(unknown.contains("未确认") && unknown.contains("日志") && !unknown.contains("private-token"))
    }
}
