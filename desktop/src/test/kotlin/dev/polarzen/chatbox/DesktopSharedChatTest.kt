package dev.polarzen.chatbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSharedChatTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    @Test fun seedImportsOnlyTranscriptAndAttachmentHints() {
        val chat = Chat("session", listOf(
            Entry("look", "ab", "已完成", attachmentReferences = listOf("C:/sensitive/file.wav"),
                replyAttachments = listOf(ReplyAttachment("private-id", 1, ReplyAttachmentType.IMAGE))),
            Entry("", "", "发送失败：private diagnostics", attachmentDisplayName = "private.png")
        ))
        assertEquals(SharedSeed("https://example.invalid", "alice", "session", listOf(
            SharedSeedTurn("[语音附件]\nlook", "a[图片附件]b", "complete"),
            SharedSeedTurn("[图片附件]", "", "unknown")
        )), desktopSharedSeed("https://example.invalid", "alice", chat))
        assertEquals(SharedSeed("", "", "", emptyList()), desktopSharedSeed("", "alice", chat))
    }

    @Test fun scopedCiphertextRestoresAndDeletionOnlyRemovesThatLocalSession() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val file = files.root.toPath().resolve("history.json")
        val store = DesktopStore(file)
        val profile = store.createProfile("https://example.invalid/A")
        val key = SessionKey(profile.id, profile.currentSession)
        val storage = DesktopSharedStorage(store, key, profile.address)
        val draft = ComposerDraft("local unsent", listOf(files.root.toPath().resolve("saved.png")))
        store.saveDraft(key, store.nextDraftRevision(), draft)
        storage.save("fixture-secret")
        storage.save("fixture-invite")
        val data = Files.readString(file)
        assertFalse(data.contains("fixture-secret"))
        assertFalse(data.contains("fixture-invite"))
        val restored = DesktopStore(file)
        assertEquals("fixture-invite", DesktopSharedStorage(restored, key, profile.address).load())
        assertEquals(draft, restored.readDraft(key))
        assertNull(DesktopSharedStorage(restored, key, "https://example.invalid/a").load())
        val second = restored.createSession(profile.id)
        val other = DesktopSharedStorage(restored, SessionKey(profile.id, second.session), profile.address)
        assertNull(other.load())
        other.save("second-room")
        restored.deleteSession(profile.id, key.sessionId)
        assertNull(DesktopSharedStorage(restored, key, profile.address).load())
        assertEquals("second-room", other.load())
    }

    @Test fun boundSessionOpensSharedAndCanBrowsePreservedLocalDraft() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val dir = files.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        val profile = store.createProfile("http://127.0.0.1:1")
        val key = SessionKey(profile.id, profile.currentSession)
        store.saveDraft(key, store.nextDraftRevision(), ComposerDraft("native draft"))
        DesktopSharedStorage(store, key, profile.address).save(SharedLocal(revoked = true).encode())
        ui.setContent { DesktopScreen(store, NamedKeys(dir, DesktopCredentials(dir.resolve("credentials"))), ReplyImageCache(dir.resolve("images"))) }
        ui.onNodeWithText("此设备的访问权限已撤销").assertIsDisplayed()
        ui.onNodeWithTag("shared-chat-browse-local").performClick()
        ui.onNodeWithText("native draft").assertIsDisplayed()
        ui.onNodeWithTag("send-button").assertIsNotEnabled()
        ui.onNodeWithTag("shared-chat-open").performClick()
        ui.onNodeWithText("此设备的访问权限已撤销").assertIsDisplayed()
    }

    @Test fun firstRunCanOpenSharingWithoutANativeEndpoint() {
        val dir = files.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        ui.setContent { DesktopScreen(store, NamedKeys(dir, DesktopCredentials(dir.resolve("credentials"))), ReplyImageCache(dir.resolve("images"))) }
        ui.onNodeWithTag("shared-chat-open").performClick()
        ui.onNodeWithText("共享服务地址").assertIsDisplayed()
        assertEquals("shared", store.profile(store.activeProfileId)?.kind)
        assertEquals("", store.lastServer)
    }
}
