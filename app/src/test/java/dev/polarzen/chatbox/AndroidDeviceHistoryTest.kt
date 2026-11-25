package dev.polarzen.chatbox

import org.junit.Assert.*
import org.junit.Test

class AndroidDeviceHistoryTest {
    @Test fun confirmedEmptyRoomCanBeRestoredUntilHiddenByItsAuthority() {
        val source = DeviceProfile("owner","device-source","https://source.invalid","user","电脑","device","service",true)
        val key = DeviceRoomKey("owner","empty-session")
        val draft = DeviceRoomDraft(key,"",100L)
        val row = deviceDraftRow(draft,listOf(source),emptySet())!!
        assertEquals(row,initialChatRow(listOf(row),"device","owner","empty-session",false,"",""))
        assertNull(deviceDraftRow(draft,listOf(source),setOf(key)))
        assertNull(deviceDraftRow(draft,emptyList(),emptySet()))
        assertTrue(deviceDraftRow(draft.copy(text="未发送的文字"),listOf(source),setOf(key))!!.reason.isNotBlank())
    }
    private val asset = MobileAsset("/private/input/cat.png","image","private photo","remote-secret")
    private val voice = MobileAsset("/private/reply/w.wav","audio","private voice","another-secret")
    private val row = AndroidDeviceRow("profile","sid","title",0,"电脑","user",null,"")

    @Test fun outgoingLimitUsesUtf8Bytes() {
        assertFalse(deviceQuestionTooLarge("a".repeat(65536)))
        assertTrue(deviceQuestionTooLarge("a".repeat(65537)))
        val atLimit = "🧪".repeat(16384)
        assertFalse(deviceQuestionTooLarge(atLimit))
        assertTrue(deviceQuestionTooLarge(atLimit + "问"))
    }

    @Test fun onlyIdleOwnersFromThePairedServiceCanProveAnOldRoom() {
        val address = "https://example.test"
        val binding = SharedBinding(address,"room","instance","owner-token",true)
        val saved = SharedLocal(binding = binding, draft = "unsent")
        fun proof(state: SharedLocal?) = legacyOwnerProof(state,"instance",setOf(address))
        assertEquals(binding.token,proof(saved))
        assertNull(proof(null))
        assertNull(proof(saved.copy(revoked = true)))
        assertNull(proof(saved.copy(pending = SharedPending("old-operation","unconfirmed"))))
        assertNull(proof(saved.copy(binding = binding.copy(owner = false))))
        assertNull(proof(saved.copy(binding = binding.copy(instance = "different"))))
        assertNull(proof(saved.copy(binding = binding.copy(address = "https://elsewhere.test"))))
    }

    @Test fun onlySubmittedTextAndTypeHintsCrossTheBoundary() {
        val turn = MobileTurn("id","猫","录音","已完成",listOf(asset),listOf(voice))
        val sent = deviceImport(turn)!!
        assertEquals("猫\n[图片附件 · 共享记录无法打开]",sent.question)
        assertEquals("录音\n[语音附件 · 共享记录无法打开]",sent.answer)
        assertEquals("id",sent.id)
        for (state in listOf(DEVICE_MEDIA_WAITING,"发送中","上传中 1/2","未发送：文件缺失")) {
            assertNull(deviceImport(turn.copy(status = state)))
        }
        assertEquals("unknown",deviceImport(turn.copy(status = "上次回复未确认"))!!.status)
    }

    @Test fun originalAttachmentsAndDraftSurviveAuthoritativePrefixAndLaterDuplicateQuestion() {
        val original = MobileTurn("old","同文","回复","已完成",listOf(asset),listOf(voice))
        val imported = deviceImport(original)!!
        val draft = MobileDraft("正在输入",listOf(asset))
        val local = MobileSession("sid",turns = listOf(original),draft = draft)
        val remote = listOf(SharedTurn(1,"authoritative",imported.question,imported.answer,"complete"),
            SharedTurn(2,"independent",imported.question,imported.answer,"complete"))
        val merged = deviceMerge(local,row,remote,"",legacyAlias = true)
        assertEquals(listOf("old","independent"),merged.turns.map { it.id })
        assertEquals(listOf(asset),merged.turns.first().sent)
        assertEquals(listOf(voice),merged.turns.first().replies)
        assertEquals(draft,merged.draft)
        assertEquals(listOf("authoritative","independent","old"),
            deviceMerge(local,row,remote,"").turns.map { it.id })
        val pc = deviceMerge(null,row,remote,"本机远端会话草稿")
        assertEquals("本机远端会话草稿",pc.draft.text)
        assertTrue(pc.turns.all { it.sent.isEmpty() && it.replies.isEmpty() })
    }

    @Test fun boundedImportMakesForwardProgressForLargeMultibyteReplies() {
        val record = ProfileImportTurn("id","","虎".repeat(600_000),"complete")
        val records = listOf(record,record.copy(id = "other"))
        assertEquals(listOf(record),deviceImportBatch(records,0))
        assertEquals(listOf(records.last()),deviceImportBatch(records,1))
    }

    @Test fun anotherRunningTurnWaitsWhileConfirmedOperationsKeepTheirOriginalIdentity() {
        assertEquals(DeviceSendFailure.WAIT_FOR_ROOM,deviceSendFailure(SharedHttpFailure(409,SharedConflict.RUNNING),false))
        assertEquals(DeviceSendFailure.REJECTED,deviceSendFailure(SharedHttpFailure(409,SharedConflict.UNRESOLVED),false))
        assertEquals(DeviceSendFailure.KEEP_PENDING,deviceSendFailure(SharedHttpFailure(503),false))
        assertEquals(DeviceSendFailure.KEEP_PENDING,deviceSendFailure(SharedHttpFailure(409),true))
    }
}
