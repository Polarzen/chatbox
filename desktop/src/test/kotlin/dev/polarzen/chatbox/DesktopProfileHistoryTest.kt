package dev.polarzen.chatbox

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.UUID

class DesktopProfileHistoryTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun sourcePagesSurviveRestartAndMergeNeverReimportsOrRewritesTheOriginal() {
        val path = files.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val a = store.ensureProfile("profile-A", "https://a.invalid")
        val sid = a.currentSession
        val key = SessionKey(a.id, sid)
        val original = Entry("重复", "看图", "已完成", attachmentReferences = listOf("private.wav"),
            replyAttachments = listOf(ReplyAttachment("private-id", 1, ReplyAttachmentType.IMAGE)))
        val old = List(103) { original }
        store.saveSessionContent(a.id, Chat(sid, old))
        store.saveDraft(key, store.nextDraftRevision(), ComposerDraft("预留草稿"))
        val b = store.ensureProfile("profile-B", "https://b.invalid")
        store.saveProfile(b.id, Chat(sid, listOf(Entry("B", "", "已完成"))))
        val before = JSONObject(Files.readString(path)).getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("sessions").getJSONObject(0).getJSONArray("entries")
        store.prepareProfileHistory(a.id, "remote-A")
        val first = store.profileSeedPage(key, "remote-A", 0, 50)
        assertEquals(50, first.size)
        assertEquals("[语音附件]\n重复", first.first().question)
        assertEquals("看[图片附件]图", first.first().answer)
        val second = DesktopStore(path)
        second.prepareProfileHistory(a.id, "remote-A")
        val all = first + second.profileSeedPage(key, "remote-A", 50, 50) + second.profileSeedPage(key, "remote-A", 100, 50)
        assertEquals(103, all.size)
        assertEquals(103, all.map { it.id }.distinct().size)
        assertEquals(first, second.profileSeedPage(key, "remote-A", 0, 50))
        val id = UUID.randomUUID().toString()
        val row = SharedTurn(104, id, "重复", "云端", "complete")
        assertFalse(second.mergeProfilePage(key, "remote-A", listOf(row.copy(status = "running"))))
        assertTrue(second.mergeProfilePage(key, "remote-A", listOf(row)))
        assertFalse(second.mergeProfilePage(key, "remote-A", listOf(row)))
        val restored = DesktopStore(path)
        restored.prepareProfileHistory(a.id, "remote-A")
        assertEquals(DesktopHistoryPosition(103, 104, null), restored.profileHistoryPosition(key, "remote-A"))
        assertEquals(emptyList<ProfileImportTurn>(), restored.profileSeedPage(key, "remote-A", 103, 50))
        assertEquals(old.map { it.answer } + "云端", restored.sessionContent(a.id, sid).entries.map { it.answer })
        val after = JSONObject(Files.readString(path)).getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("sessions").getJSONObject(0).getJSONArray("entries")
        for (index in 0 until before.length()) assertTrue(before.getJSONObject(index).similar(after.getJSONObject(index)))
        assertEquals(ComposerDraft("预留草稿"), restored.readDraft(key))
        assertEquals(b.id, restored.activeProfileId)
        assertEquals("B", restored.sessionContent(b.id, sid).entries.single().question)
        assertThrows(IllegalStateException::class.java) { restored.profileHistoryPosition(key, "remote-B") }
        assertThrows(IllegalStateException::class.java) { restored.mergeProfilePage(key, "remote-A", listOf(row.copy(seq = 106))) }
    }

    @Test fun pendingIdentityAndConsumptionCommitTogetherAndDeletionRequiresAnExplicitLocalDelete() {
        val path = files.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val profile = store.ensureProfile("profile", "https://example.invalid")
        val sid = profile.currentSession
        val key = SessionKey(profile.id, sid)
        store.saveProfileAuthorization(profile.id, null, "ciphertext")
        store.prepareProfileHistory(profile.id, "remote")
        val version = store.nextDraftRevision()
        store.saveDraft(key, version, ComposerDraft("相同文字"))
        val pending = SharedPending(UUID.randomUUID().toString(), "相同文字")
        store.beginProfileSend(key, "remote", version, pending)
        store.saveDraft(key, version, ComposerDraft("迟到的保存"))
        val restored = DesktopStore(path)
        assertEquals(ComposerDraft(), restored.readDraft(key))
        assertEquals(pending, restored.profileHistoryPosition(key, "remote").pending)
        assertThrows(IllegalStateException::class.java) { restored.beginProfileSend(key, "remote", 99, pending) }
        val other = SharedTurn(1, UUID.randomUUID().toString(), pending.question, "来自手机", "complete")
        restored.mergeProfilePage(key, "remote", listOf(other))
        assertEquals(pending, restored.profileHistoryPosition(key, "remote").pending)
        restored.mergeProfilePage(key, "remote", listOf(other.copy(seq = 2, id = pending.id, answer = "来自电脑")))
        assertNull(restored.profileHistoryPosition(key, "remote").pending)
        assertEquals(listOf("来自手机", "来自电脑"), restored.sessionContent(profile.id, sid).entries.map { it.answer })
        val nextSid = restored.createSession(profile.id).session
        restored.renameSession(profile.id, nextSid, "另一个会话")
        assertTrue(restored.profileRemoved(profile.id).isEmpty())
        restored.deleteSession(profile.id, sid)
        assertEquals(listOf(sid), DesktopStore(path).profileRemoved(profile.id))
        restored.acknowledgeProfileRemoval(profile.id, sid)
        assertTrue(DesktopStore(path).profileRemoved(profile.id).isEmpty())
    }

    @Test fun legacyActivityIsUnknownAndInvalidTranscriptReportsExactRangeWithoutMutatingTheFile() {
        val path = files.root.toPath().resolve("history.json")
        val legacy = """{"schemaVersion":2,"sessionOrderVersion":1,"username":"alice","activeProfileId":"A","profiles":[{"id":"A","address":"https://a.invalid","currentSession":"old","sessions":[{"id":"old","entries":[]}]}]}"""
        Files.writeString(path, legacy)
        val store = DesktopStore(path)
        assertEquals(0L, store.sessions("A").single().activity)
        val now = store.createSession("A")
        assertTrue(store.sessions("A").single { it.id == now.session }.activity > 0L)
        val damaged = legacy.replace("\"entries\":[]", "\"entries\":[{\"question\":\"incomplete\"}]")
        Files.writeString(path, damaged)
        val error = assertThrows(DesktopHistoryCorruption::class.java) { DesktopStore(path) }
        assertTrue(error.message!!.contains("old 第 1 轮"))
        assertEquals(damaged, Files.readString(path))
    }

    @Test fun oversizedTurnLeavesEarlierTurnsUploadableAndReportsTheRemainingRange() {
        val store = DesktopStore(files.root.toPath().resolve("history.json"))
        val a = store.ensureProfile("a", "https://a.invalid")
        val key = SessionKey(a.id, a.currentSession)
        store.saveSessionContent(a.id, Chat(key.sessionId, listOf(Entry("一", "一", "已完成"),
            Entry("大", "x".repeat(8 * 1024 * 1024), "已完成"), Entry("三", "三", "已完成"))))
        store.prepareProfileHistory(a.id, "remote")
        assertEquals(1, store.profileSeedPage(key, "remote", 0, 50).size)
        val gap = assertThrows(IllegalStateException::class.java) { store.profileSeedPage(key, "remote", 1, 50) }
        assertTrue(gap.message!!.contains("2–3"))
        assertEquals(3, store.sessionContent(a.id, key.sessionId).entries.size)
    }
}
