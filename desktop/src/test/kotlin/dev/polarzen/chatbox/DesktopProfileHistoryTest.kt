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
        store.prepareDeviceHistory(key, "remote-A")
        val first = store.profileSeedPage(key, "remote-A", 0, 50)
        assertEquals(50, first.size)
        assertEquals("[语音附件]\n重复", first.first().question)
        assertEquals("看[图片附件]图", first.first().answer)
        val second = DesktopStore(path)
        second.prepareDeviceHistory(key, "remote-A")
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
        restored.prepareDeviceHistory(key, "remote-A")
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

    @Test fun legacyPendingClearsOnlyForItsOriginalReceiptAndLocalDeleteCreatesNoRemoteRequest() {
        val path = files.root.toPath().resolve("history.json")
        val store = DesktopStore(path)
        val profile = store.ensureProfile("profile", "https://example.invalid")
        val sid = profile.currentSession
        val key = SessionKey(profile.id, sid)
        val pending = SharedPending(UUID.randomUUID().toString(), "相同文字")
        editLegacyFixture(path, key) { p, row ->
            p.put("profileAuthorization", "ciphertext")
            row.put("profileHistory", JSONObject().put("profile", "remote").put("prefix", 0).put("pending",
                JSONObject().put("id", pending.id).put("question", pending.question)))
        }
        val restored = DesktopStore(path)
        assertEquals(pending, restored.profileHistoryPosition(key, "remote").pending)
        val other = SharedTurn(1, UUID.randomUUID().toString(), pending.question, "来自手机", "complete")
        restored.mergeProfilePage(key, "remote", listOf(other))
        assertEquals(pending, restored.profileHistoryPosition(key, "remote").pending)
        restored.mergeProfilePage(key, "remote", listOf(other.copy(seq = 2, id = pending.id, answer = "来自电脑")))
        assertNull(restored.profileHistoryPosition(key, "remote").pending)
        assertEquals(listOf("来自手机", "来自电脑"), restored.sessionContent(profile.id, sid).entries.map { it.answer })
        val nextSid = restored.createSession(profile.id).session
        restored.renameSession(profile.id, nextSid, "另一个会话")
        assertTrue(restored.legacyHiddenSessions(profile.id).isEmpty())
        restored.deleteSession(profile.id, sid)
        assertEquals(setOf(sid), DesktopStore(path).legacyHiddenSessions(profile.id))
        assertTrue(DesktopStore(path).deviceRemoved(profile.id).isEmpty())
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
        store.prepareDeviceHistory(key, "remote")
        assertEquals(1, store.profileSeedPage(key, "remote", 0, 50).size)
        val gap = assertThrows(IllegalStateException::class.java) { store.profileSeedPage(key, "remote", 1, 50) }
        assertTrue(gap.message!!.contains("2–3"))
        assertEquals(3, store.sessionContent(a.id, key.sessionId).entries.size)
    }
}
