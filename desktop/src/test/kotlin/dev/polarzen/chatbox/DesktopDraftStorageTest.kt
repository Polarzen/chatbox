package dev.polarzen.chatbox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class DesktopDraftStorageTest {
    @get:Rule val folder = TemporaryFolder()
    private val file: Path get() = folder.root.toPath().resolve("history.json")

    @Test fun oldAndRenamedSessionsKeepIndependentTextAndOrderedMissingPathsOutsideSearch() {
        val store = DesktopStore(file)
        val a = store.ensureProfile("first", "https://one.example")
        val b = store.ensureProfile("second", "https://two.example")
        val sameId = a.currentSession
        store.saveProfile(b.id, Chat(sameId))
        val keys = listOf(SessionKey(a.id, sameId), SessionKey(b.id, sameId),
            SessionKey(a.id, store.createSession(a.id).session))
        val paths = listOf("two.png", "麦克风.wav", "one.png").map { file.resolveSibling(it) }
        keys.forEachIndexed { index, key ->
            assertEquals(ComposerDraft(), store.readDraft(key))
            store.saveDraft(key, store.nextDraftRevision(), ComposerDraft("输入\n结束 $index", paths))
            store.renameSession(key.profileId, key.sessionId, "同名")
        }
        val restored = DesktopStore(file)
        keys.forEachIndexed { index, key ->
            assertEquals(ComposerDraft("输入\n结束 $index", paths), restored.readDraft(key))
        }
        assertTrue(restored.historySearchSnapshot(a.id).search("结束").isEmpty())
        val deleted = keys.last()
        store.deleteSession(deleted.profileId, deleted.sessionId)
        store.saveDraft(deleted, store.nextDraftRevision(), ComposerDraft("late"))
        assertFalse(DesktopStore(file).sessions(a.id).any { it.id == deleted.sessionId })

        store.saveDraft(keys.first(), store.nextDraftRevision(), ComposerDraft())
        val row = JSONObject(Files.readString(file)).getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("sessions").getJSONObject(0)
        assertFalse(row.has("draft"))
    }

    @Test fun revisionFenceSurvivesEditorRecreationAndLateReplySavesPreserveTheNextDraft() {
        val store = DesktopStore(file)
        val first = store.ensureProfile("profile", "https://one.example")
        val key = SessionKey(first.id, first.currentSession)
        val pending = Chat(key.sessionId, listOf(Entry("round one", "", "发送中")))
        val earlier = store.nextDraftRevision()
        val sending = store.nextDraftRevision()
        store.saveDraft(key, sending, ComposerDraft("round one"))
        store.saveDraft(key, earlier, ComposerDraft("obsolete"))
        assertThrows(IllegalStateException::class.java) { store.commitDraft(key, earlier, pending) }
        assertTrue(store.sessionContent(key.profileId, key.sessionId).entries.isEmpty())
        assertEquals("round one", store.readDraft(key).text)

        store.commitDraft(key, sending, pending)
        store.saveDraft(key, sending, ComposerDraft("delayed autosave"))
        val afterCommit = DesktopStore(file)
        assertEquals(ComposerDraft(), afterCommit.readDraft(key))
        assertEquals("上次回复未确认", afterCommit.sessionContent(key.profileId, key.sessionId).entries.single().status)
        val recreatedRevision = store.nextDraftRevision()
        assertTrue(recreatedRevision > sending)
        store.saveDraft(key, recreatedRevision, ComposerDraft("next"))
        assertThrows(IllegalStateException::class.java) { store.commitDraft(key, sending, pending) }
        for (status in listOf("失败", "发送中", "已完成")) {
            store.saveSessionContent(key.profileId, pending.copy(entries = listOf(pending.entries.single().copy(status = status))))
            assertEquals("next", DesktopStore(file).readDraft(key).text)
        }
    }

    @Test fun commitAndAutosaveCanRetryTheSameRevisionAfterAnIoFailure() {
        val store = DesktopStore(file)
        val profile = store.ensureProfile("profile", "https://one.example")
        val key = SessionKey(profile.id, profile.currentSession)
        val original = ComposerDraft("first")
        store.saveDraft(key, store.nextDraftRevision(), original)
        val revision = store.nextDraftRevision()
        val pending = Chat(key.sessionId, listOf(Entry("second", "", "发送中")))
        val saved = Files.move(file, file.resolveSibling("history-original.json"))
        Files.createDirectory(file)
        val blocker = Files.createFile(file.resolve("non-empty"))
        try {
            assertThrows(IOException::class.java) { store.saveDraft(key, revision, ComposerDraft("second")) }
            assertThrows(IOException::class.java) { store.commitDraft(key, revision, pending) }
            assertEquals(original, store.readDraft(key))
            assertEquals(original, DesktopStore(saved).readDraft(key))
            assertTrue(store.sessionContent(key.profileId, key.sessionId).entries.isEmpty())
        } finally {
            Files.delete(blocker)
            Files.delete(file)
            Files.move(saved, file)
        }
        store.saveDraft(key, revision, ComposerDraft("second"))
        assertEquals("second", DesktopStore(file).readDraft(key).text)
        store.commitDraft(key, revision, pending)
        assertEquals("second", DesktopStore(file).sessionContent(key.profileId, key.sessionId).entries.single().question)
        assertEquals(ComposerDraft(), DesktopStore(file).readDraft(key))
    }

    @Test fun legacyDraftFollowsVerifiedAdoption() {
        val path = file.resolveSibling("missing.wav")
        val legacy = JSONObject().put("session", "session").put("draft",
            JSONObject().put("text", "recover").put("attachments", JSONArray().put(path.toString())))
        val address = "http://127.0.0.1:8080"
        Files.writeString(file, JSONObject().put("servers", JSONObject().put(address, legacy)).toString())
        val store = DesktopStore(file)
        val offline = store.profiles.single()
        val target = store.ensureProfile("verified", address)
        assertEquals(setOf("session"), store.adoptVerifiedSessions(target.id, address, setOf("session")))
        val restored = DesktopStore(file)
        assertEquals(ComposerDraft("recover", listOf(path)), restored.readDraft(SessionKey(target.id, "session")))
        assertEquals(ComposerDraft(), restored.readDraft(SessionKey(offline.id, "session")))
    }

    @Test fun startupCleanupProtectsEveryDraftAndBothLegacyAndOrderedHistoryReferences() {
        val store = DesktopStore(file)
        val a = store.ensureProfile("a", "https://one.example")
        val b = store.ensureProfile("b", "https://two.example")
        val dir = Files.createDirectories(store.managedMediaDirectory)
        val draft = Files.createFile(dir.resolve("screenshot-10.png"))
        val legacy = Files.createFile(dir.resolve("screenshot-11.png"))
        val ordered = Files.createFile(dir.resolve("${UUID.randomUUID()}.wav"))
        val outside = Files.createFile(file.resolveSibling("screenshot-12.png"))
        val ordinary = Files.createFile(dir.resolve("my-image.png"))
        val nested = Files.createDirectories(dir.resolve("nested")).resolve("screenshot-99.png")
        Files.createFile(nested)
        val orphans = listOf("screenshot-13.png", "recording-42.part", "${UUID.randomUUID()}.wav")
            .map { Files.createFile(dir.resolve(it)) }
        store.saveDraft(SessionKey(a.id, a.currentSession), store.nextDraftRevision(), ComposerDraft(attachments = listOf(draft)))
        store.saveSessionContent(b.id, Chat(b.currentSession, listOf(Entry("", "", "发送中",
            attachmentReference = legacy.toUri().toString(), attachmentReferences = listOf(ordered.toString(), outside.toString())))))
        DesktopStore(file).cleanupUnreferencedManagedMedia()
        for (path in listOf(draft, legacy, ordered, outside, ordinary, nested)) assertTrue(path.toString(), Files.exists(path))
        for (path in orphans) assertFalse(path.toString(), Files.exists(path))
    }

    @Test fun cleanupLeavesAnExternalDirectoryBehindAWindowsJunctionAlone() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val store = DesktopStore(file)
        val target = Files.createDirectory(file.resolveSibling("external"))
        val original = Files.createFile(target.resolve("screenshot-100.png"))
        val junction = store.managedMediaDirectory
        val mklink = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/j", junction.toString(), target.toString())
            .redirectErrorStream(true).start()
        val output = mklink.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, mklink.waitFor())
        try {
            store.cleanupUnreferencedManagedMedia()
            assertTrue(Files.exists(original))
        } finally {
            Files.delete(junction)
        }
    }
}
