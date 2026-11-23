package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class DesktopHistoryInteractionTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    private val literal = "C++[2].*"
    private val prefix = "Introduction before the attachment.\n"
    private val body = (1..130).joinToString("\n") { line ->
        if (line == 70) "Chosen line $literal" else "Reply line $line"
    }
    private val draft = "Unsent draft"
    private lateinit var store: DesktopStore
    private lateinit var home: Chat
    private lateinit var target: Chat
    private lateinit var profile: Profile
    private val composer get() = rule.onNodeWithTag("composer-field")
    private val query get() = rule.onNodeWithTag("history-search-query")

    private fun render(width: Int, height: Int) {
        val dir = files.root.toPath()
        store = DesktopStore(dir.resolve("history.json"))
        val otherProfile = store.createProfile("http://127.0.0.1:7072")
        store.saveSessionContent(otherProfile.id, Chat(otherProfile.currentSession,
            listOf(Entry("Other profile $literal", "", "已完成"))))
        profile = store.createProfile("http://127.0.0.1:7071")
        home = Chat(profile.currentSession, listOf(Entry("Return home anchor", "CC2xyz", "已完成")))
        store.saveSessionContent(profile.id, home)
        target = store.createSession(profile.id).copy(entries = listOf(
            Entry("Target question", prefix + body, "已完成",
                replyAttachments = listOf(ReplyAttachment(null, prefix.length, ReplyAttachmentType.IMAGE))),
        ))
        store.saveSessionContent(profile.id, target)
        for (session in listOf(home, target)) store.renameSession(profile.id, session.session, "同名")
        store.selectSession(profile.id, home.session)

        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("credentials")))
        val cache = ReplyImageCache(dir.resolve("reply-images"))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.requiredSize(width.dp, height.dp)) {
                MaterialTheme { DesktopScreen(store, keys, cache) }
            }
        }
        settle()
        composer.performTextInput(draft)
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
    }

    private fun openByKeyboard() {
        composer.performClick()
        composer.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        settle()
        query.assertIsFocused()
    }

    private fun findResult(text: String) = hasAnyAncestor(hasTestTag("history-search-results")) and
        hasText(text, substring = true)

    private fun searchForSingleHit(term: String) {
        query.performTextInput(term)
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodes(hasText("找到 1 处匹配")).fetchSemanticsNodes().size == 1
        }
        rule.onNode(findResult(term)).assertIsDisplayed()
    }

    private fun assertHomeDraft() {
        assertEquals(home.session, store.profile(profile.id)!!.currentSession)
        assertEquals(draft, composer.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(home, store.sessionContent(profile.id, home.session))
    }

    @Test
    fun defaultWindowJumpsToLiteralInsideSplitReplyAndRestoresHomeDraft() {
        render(1000, 760)
        openByKeyboard()
        searchForSingleHit(literal)
        rule.onNode(findResult(literal)).performClick()
        settle()
        query.assertDoesNotExist()
        assertEquals(target.session, store.profile(profile.id)!!.currentSession)
        assertEquals("", composer.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val text = rule.onNodeWithText(body, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        check(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout))
        val glyph = layout.single().getBoundingBox(body.indexOf(literal)).translate(text.positionInRoot)
        val viewport = rule.onNodeWithTag("message-region").fetchSemanticsNode().boundsInRoot
        assertEquals("The searched line after the attachment is centered", viewport.center.y, glyph.center.y, 2f)

        rule.onNodeWithTag("history-search-open").performClick()
        settle()
        searchForSingleHit("Return home anchor")
        rule.onNode(findResult("Return home anchor")).performClick()
        settle()
        assertHomeDraft()
    }

    @Test
    fun narrowWindowRejectsDeletedHitAndEscapePreservesCurrentSessionAndDraft() {
        render(650, 480)
        openByKeyboard()
        searchForSingleHit(literal)
        rule.runOnIdle { store.deleteSession(profile.id, target.session) }
        rule.onNode(findResult(literal)).performClick()
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodes(hasTestTag("history-search-empty")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("history-search-clear").performClick()
        settle()
        query.assertIsFocused()
        query.performKeyInput { pressKey(Key.Escape) }
        settle()
        query.assertDoesNotExist()
        assertHomeDraft()
    }
}
