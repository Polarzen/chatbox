package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import org.json.JSONObject
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
            Entry("", "", "发送失败：private diagnostics", attachmentDisplayName = "private.png"),
            Entry("听听", "", "已完成", attachmentDisplayName = "personal recording.WaV")
        ))
        assertEquals(listOf(
            SharedSeedTurn("[语音附件]\nlook", "a[图片附件]b", "complete"),
            SharedSeedTurn("[图片附件]", "", "unknown"),
            SharedSeedTurn("[语音附件]\n听听", "", "complete")
        ), chat.entries.map(::desktopSharedSeedTurn))
    }

    @Test fun scopedCiphertextRestoresAndDeletionOnlyRemovesThatLocalSession() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val file = files.root.toPath().resolve("history.json")
        val store = DesktopStore(file)
        val profile = store.createProfile("https://example.invalid/A")
        val key = SessionKey(profile.id, profile.currentSession)
        val draft = ComposerDraft("local unsent", listOf(files.root.toPath().resolve("saved.png")))
        store.saveDraft(key, store.nextDraftRevision(), draft)
        putLegacyRoomFixture(file, key, profile.address, "fixture-invite")
        val data = Files.readString(file)
        assertFalse(data.contains("fixture-invite"))
        val restored = DesktopStore(file)
        assertEquals("fixture-invite", readLegacyRoomRecord(restored, key, profile.address))
        assertEquals(draft, restored.readDraft(key))
        assertNull(readLegacyRoomRecord(restored, key, "https://example.invalid/a"))
        val second = restored.createSession(profile.id)
        val other = SessionKey(profile.id, second.session)
        assertNull(readLegacyRoomRecord(restored, other, profile.address))
        putLegacyRoomFixture(file, other, profile.address, "second-room")
        val reopened = DesktopStore(file)
        reopened.deleteSession(profile.id, key.sessionId)
        assertNull(readLegacyRoomRecord(reopened, key, profile.address))
        assertEquals("second-room", readLegacyRoomRecord(reopened, other, profile.address))
    }

    @Test fun historicalBindingsOpenTheNativeChatAndPreserveItsDraftOnReselection() {
        val dir = files.root.toPath()
        val file = dir.resolve("history.json")
        var store = DesktopStore(file)
        val profile = store.createProfile("http://127.0.0.1:1")
        val key = SessionKey(profile.id, profile.currentSession)
        store.saveSessionContent(profile.id, Chat(key.sessionId, listOf(Entry("原问题", "本机回答", "已完成"))))
        store.renameSession(profile.id, key.sessionId, "有旧绑定的会话")
        store.saveDraft(key, store.nextDraftRevision(), ComposerDraft("native draft"))
        val other = store.createSession(profile.id)
        store.renameSession(profile.id, other.session, "普通会话")
        store.selectSession(profile.id, key.sessionId)
        val pendingRoom = JSONObject().put("address", "https://old.invalid").put("room", "old-room")
            .put("instance", "old-instance").put("token", "old-token").put("owner", true).put("draft", "")
            .put("pending", JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("question", "未确认")).toString()
        putLegacyRoomFixture(file, key, profile.address, pendingRoom)
        editLegacyFixture(file, SessionKey(profile.id, other.session)) { p, row ->
            p.put("profileAuthorization", "old credential")
            row.put("profileHistory", JSONObject().put("profile", "old-profile").put("prefix", 0))
        }
        store = DesktopStore(file)
        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("credentials")))
        val sync = DesktopDeviceSync(store, keys) { error("Local use must not start the device host") }
        ui.mainClock.autoAdvance = false
        ui.setContent {
            Box(Modifier.requiredSize(1000.dp, 700.dp)) {
                DesktopScreen(store, keys, ReplyImageCache(dir.resolve("images")), deviceSync = sync)
            }
        }
        ui.mainClock.advanceTimeBy(120)
        ui.onNodeWithText("native draft").assertIsDisplayed()
        ui.onNodeWithText("此会话保留了已同步的历史。", substring = true).assertIsDisplayed()
        ui.onNodeWithTag("send-button").assertIsNotEnabled()
        ui.onNodeWithTag("shared-chat-open").assertDoesNotExist()
        ui.onNodeWithTag("sidebar-toggle").performClick()
        ui.mainClock.advanceTimeBy(120)
        ui.onNodeWithTag("pin-sidebar").performClick()
        ui.mainClock.advanceTimeBy(120)
        ui.onNodeWithText("普通会话").performClick()
        ui.mainClock.advanceTimeBy(120)
        ui.onNodeWithText("有旧绑定的会话").performClick()
        ui.mainClock.advanceTimeBy(120)
        ui.onNodeWithText("本机回答", substring = true).assertIsDisplayed()
        ui.onNodeWithText("native draft").assertIsDisplayed()
        ui.onNodeWithTag("profile-share-open").assertDoesNotExist()
        ui.onNodeWithTag("rail-settings").performClick()
        ui.mainClock.advanceTimeBy(220)
        ui.onNodeWithTag("connection-control-status").performClick()
        ui.mainClock.advanceTimeBy(120)
        ui.onNodeWithTag("device-setup-open").performClick()
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("device-setup-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("device-setup-dialog").assertIsDisplayed()
        assertNull(store.deviceOwner())
        assertEquals(pendingRoom, readLegacyRoomRecord(store, key, profile.address))
        assertEquals("old credential", store.profileAuthorization(profile.id))
    }
}
