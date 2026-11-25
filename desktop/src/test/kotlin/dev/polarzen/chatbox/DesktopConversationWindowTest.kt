package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopConversationWindowTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    @Test fun rejectedDeviceDraftReturnsToOpenEditorAndPreservesNewTyping() = runBlocking {
        val store = DesktopStore(files.root.toPath().resolve("history.json"))
        val profile = store.createProfile("https://example.invalid")
        val key = SessionKey(profile.id, profile.currentSession)
        val composer = DesktopComposerSession(store, this) { _, _ -> }
        val editor = composer.open(key)
        fun queued(text: String): String {
            composer.input(key, TextFieldValue(text))
            store.saveDraft(key, editor.revision, editor.draft)
            val request = store.queueDeviceSend(key, text, editor.revision, contextRevision = "checked")
            composer.consumed(key, editor.revision)
            return request
        }
        val first = queued("被拒绝的消息")
        store.restoreRejectedDeviceDraft(key, first, 409)
        assertTrue(composer.restoreRejectedDraft(key))
        assertEquals("被拒绝的消息", editor.field.text)
        assertNull(store.restoredDeviceDraft(key))
        val second = queued("另一条退回的消息")
        composer.input(key, TextFieldValue("正在写的新草稿"))
        store.restoreRejectedDeviceDraft(key, second, 409)
        assertTrue(composer.restoreRejectedDraft(key))
        assertEquals("正在写的新草稿", editor.field.text)
        assertEquals(editor.field.text, store.readDraft(key).text)
        val backup = store.sessions(profile.id).single { it.id != key.sessionId }
        assertEquals("另一条退回的消息", store.readDraft(SessionKey(profile.id, backup.id)).text)
    }

    @Test fun renameShowsBothValuesAndResolvesOnlyReviewedNameConflicts() {
        val mine = ConversationPreferences(peerName = "本机名字", recentTurns = 2)
        var saved: ConversationPreferences? = null
        var reviewed = emptySet<String>()
        val handle = ChatPreferenceHandle("room", mine, setOf("peerName", "recentTurns"),
            false, true, true, { null }, {}, { false }, saveReviewed = { value, fields ->
                saved = value
                reviewed = fields
            }, remoteValue = mine.copy(peerName = "远端名字", recentTurns = 0))
        ui.setContent { MaterialTheme { PartnerNameDialog(handle, {}, {}) } }
        ui.onNodeWithText("本机：本机名字").assertIsDisplayed()
        ui.onNodeWithText("另一台设备：远端名字").assertIsDisplayed()
        ui.onNodeWithTag("save-peer-name").performClick()
        assertEquals(mine.normalized(), saved)
        assertEquals(setOf("peerName"), reviewed)
        ui.onNodeWithText("采用另一台设备的值").performClick()
        ui.onNodeWithTag("save-peer-name").performClick()
        assertEquals(mine.copy(peerName = "远端名字"), saved)
        assertEquals(setOf("peerName"), reviewed)
    }

    @Test fun blockedPreviewKeepsUnverifiedMessagesAndIdentifiesTheFittingPrefix() {
        val first = ContextPreviewMessage("user", "已核对的提问")
        val second = ContextPreviewMessage("assistant", "")
        var submitted = false
        ui.setContent {
            MaterialTheme {
                ContextSendDialog("本轮问题", "蓝舟",
                    AstrBotContextPreview(true, "revision", false, "所选内容超过可用容量。", listOf(first, second),
                        fittingVisibleMessagesEstimate = listOf(first)),
                    onConfirm = { submitted = true }, onAdjust = {}, onClose = {})
            }
        }
        ui.onNodeWithText("我 · 预计可容纳").assertIsDisplayed()
        ui.onNodeWithText("蓝舟 · 预计超出上限").assertIsDisplayed()
        ui.onNodeWithText("此设备未核实正文").assertIsDisplayed()
        ui.onNodeWithText("本轮问题").assertIsDisplayed()
        ui.onNodeWithTag("confirm-context-send").assertIsNotEnabled()
        assertFalse(submitted)
    }

    @Test fun menusPreserveOriginalTitleAndDraftAndClaimThresholdOnlyOnce() {
        val dir = files.root.toPath()
        val file = dir.resolve("history.json")
        var store = DesktopStore(file)
        val p = store.createProfile("https://example.invalid")
        val original = Chat(p.currentSession, listOf(Entry("早期问题", "早期回复", "已完成")))
        store.saveSessionContent(p.id, original)
        store.renameSession(p.id, original.session, "独立的主题")
        val session = SessionKey(p.id, original.session)
        val creds = NamedKeys(dir, DesktopCredentials(dir.resolve("keys")))
        val cache = ReplyImageCache(dir.resolve("images"))
        val epoch = mutableIntStateOf(0)
        ui.mainClock.autoAdvance = false
        ui.setContent {
            key(epoch.intValue) {
                Box(Modifier.requiredSize(900.dp, 660.dp)) { MaterialTheme { DesktopScreen(store, creds, cache) } }
            }
        }
        fun frame() { ui.mainClock.advanceTimeBy(450); ui.waitForIdle() }
        fun click(tag: String) { ui.onNodeWithTag(tag).performClick(); frame() }
        frame()
        ui.onNodeWithTag("composer-field").performTextInput("原草稿")
        frame()
        click("conversation-menu")
        click("rename-peer")
        ui.onNodeWithTag("peer-name-field").performTextInput("蓝舟")
        frame()
        ui.onNodeWithTag("peer-name-preview").assertTextEquals("蓝舟")
        click("save-peer-name")
        ui.onNodeWithTag("partner-name").assertTextEquals("蓝舟")
        click("message-menu-assistant-0")
        click("toggle-context-assistant-0")
        ui.onNodeWithTag("context-recent-count").performTextReplacement("0")
        ui.onNodeWithTag("context-remind-after").performTextInput("1")
        frame()
        click("save-context")
        val prefs = store.directJournal(session).value
        assertEquals(0, prefs.recentTurns)
        assertEquals(listOf(ContextPin(1, "assistant")), prefs.pins)
        ui.onNodeWithTag("context-reminder").assertIsDisplayed()
        ui.onNodeWithText("新开会话").performClick()
        frame()
        assertEquals(1, store.sessions(p.id).size)
        ui.onNodeWithText("取消").performClick()
        frame()
        assertEquals(original.session, store.profile(p.id)!!.currentSession)
        ui.onNodeWithText("新开会话").performClick()
        frame()
        click("confirm-new-conversation")
        assertEquals(2, store.sessions(p.id).size)
        click("sidebar-toggle")
        ui.onNodeWithText("独立的主题").performClick()
        frame()
        ui.onNodeWithTag("composer-field").assertTextContains("原草稿")
        ui.onNodeWithTag("context-reminder").assertDoesNotExist()
        ui.runOnIdle { store = DesktopStore(file); epoch.intValue++ }
        frame()
        ui.onNodeWithTag("partner-name").assertTextEquals("蓝舟")
        ui.onNodeWithTag("composer-field").assertTextContains("原草稿")
        ui.onNodeWithTag("context-reminder").assertDoesNotExist()
        assertEquals(original, store.sessionContent(p.id, session.sessionId))
        assertEquals("独立的主题", store.sessions(p.id).single { it.id == session.sessionId }.title)
    }
}
