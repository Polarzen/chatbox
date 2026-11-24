package dev.polarzen.chatbox

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class DesktopReplyDestinationTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = folder.root.toPath().resolve("history.json")
    private val reply = Entry("question", "answer", "已完成")

    @Test fun selectsExactProfileSessionAndCompletedEntryWithoutChangingDrafts() {
        val store = DesktopStore(file)
        val address = "https://same.example"
        val first = store.ensureProfile("first", address)
        val second = store.ensureProfile("second", address)
        val target = SessionKey(first.id, first.currentSession)
        val other = SessionKey(second.id, first.currentSession)
        val alternate = SessionKey(first.id, store.createSession(first.id).session)
        for (key in listOf(target, alternate, other)) {
            store.saveProfile(key.profileId, Chat(key.sessionId, listOf(reply, reply.copy(status = "发送中"))))
            store.renameSession(key.profileId, key.sessionId, "same title")
            store.saveDraft(key, store.nextDraftRevision(), ComposerDraft("draft for $key"))
        }
        assertEquals(second.id, store.activeProfileId)
        assertEquals(alternate.sessionId, store.profile(first.id)!!.currentSession)
        val before = Files.readAllBytes(file)
        for (invalid in listOf(ReplyDestination(target, -1), ReplyDestination(target, 2),
                ReplyDestination(target, 1), ReplyDestination(target.copy(profileId = "missing"), 0),
                ReplyDestination(target.copy(sessionId = "missing"), 0))) {
            assertFalse(store.selectReplyIfPresent(invalid))
        }
        assertArrayEquals(before, Files.readAllBytes(file))
        assertTrue(store.selectReplyIfPresent(ReplyDestination(target, 0)))
        val reopened = DesktopStore(file)
        assertEquals(first.id, reopened.activeProfileId)
        assertEquals(target.sessionId, reopened.profile(first.id)!!.currentSession)
        for (key in listOf(target, alternate, other)) {
            assertEquals("draft for $key", reopened.readDraft(key).text)
        }
    }

    @Test fun deletedTargetCannotBeResurrectedOrRedirectedToAnotherCompletedReply() {
        val store = DesktopStore(file)
        val first = store.ensureProfile("first", "https://one.example")
        val target = ReplyDestination(SessionKey(first.id, first.currentSession), 0)
        store.saveSessionContent(first.id, Chat(first.currentSession, listOf(reply)))
        store.deleteSession(first.id, first.currentSession)
        val second = store.ensureProfile("second", "https://two.example")
        store.saveProfile(second.id, Chat(first.currentSession, listOf(reply)))
        val before = Files.readAllBytes(file)
        assertFalse(store.selectReplyIfPresent(target))
        assertArrayEquals(before, Files.readAllBytes(file))
        assertTrue(store.sessions(first.id).isEmpty())
        assertEquals(second.id, store.activeProfileId)
    }

    @Test fun failedWriteKeepsBothSelectionsAndCanBeRetried() {
        val store = DesktopStore(file)
        val first = store.ensureProfile("first", "https://one.example")
        val target = ReplyDestination(SessionKey(first.id, first.currentSession), 0)
        store.saveSessionContent(first.id, Chat(first.currentSession, listOf(reply)))
        val alternate = store.createSession(first.id)
        val second = store.ensureProfile("second", "https://two.example")
        val saved = Files.move(file, file.resolveSibling("saved.json"))
        Files.createDirectory(file)
        val blocker = Files.createFile(file.resolve("non-empty"))
        try {
            assertThrows(IOException::class.java) { store.selectReplyIfPresent(target) }
            assertEquals(second.id, store.activeProfileId)
            assertEquals(alternate.session, store.profile(first.id)!!.currentSession)
        } finally {
            Files.delete(blocker)
            Files.delete(file)
            Files.move(saved, file)
        }
        assertTrue(store.selectReplyIfPresent(target))
        assertEquals(first.id, DesktopStore(file).activeProfileId)
    }
}
