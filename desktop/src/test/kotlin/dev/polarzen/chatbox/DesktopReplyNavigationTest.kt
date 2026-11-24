package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DesktopReplyNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun wakeRoutesTheNextPasteToTheComposerWithoutClickingIt() {
        val wake = mutableStateOf(0L)
        val draft = mutableStateOf(TextFieldValue("unsent"))
        var pastes = 0
        rule.setContent {
            Column {
                BasicTextField("other", {}, modifier = Modifier.testTag("other"))
                Composer(
                    draft = draft.value, onDraft = { draft.value = it }, selectedImage = null,
                    onChooseImage = {}, onRemoveImage = {}, onSend = {}, canSend = true,
                    composerFocusSerial = wake.value,
                    onPasteAttachment = { pastes++; true },
                )
            }
        }
        val other = rule.onNodeWithTag("other")
        other.performClick().assertIsFocused()
        rule.runOnIdle { wake.value++ }
        rule.onNodeWithTag("composer-field").assertIsFocused()
        rule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        rule.runOnIdle {
            assertEquals(1, pastes)
            assertEquals("unsent", draft.value.text)
        }
        other.performClick()
        rule.runOnIdle { draft.value = TextFieldValue("modified") }
        other.assertIsFocused()
    }

    @Test
    fun olderMediaReplyStaysVisibleBelowAResizedQuestionAndNewTurnsResumeFollowing() {
        val question = mutableStateOf("question line\n".repeat(45))
        val count = mutableStateOf(12)
        val request = mutableStateOf<ReplyTurnNavigation?>(null)
        rule.setContent {
            val entries = List(count.value) { i ->
                if (i == 2) Entry(question.value, "", "已完成",
                    replyAttachments = listOf(ReplyAttachment("picture", 0, ReplyAttachmentType.IMAGE)))
                else Entry("question $i", "answer $i", "已完成")
            }
            Box(Modifier.requiredSize(520.dp, 400.dp)) {
                DesktopChatLayout(
                    sessions = emptyList(), chat = Chat("session", entries), onSelectSession = {},
                    onNewSession = {}, canCreate = true, topActions = {}, draft = TextFieldValue(),
                    onDraft = {}, selectedImage = null, onChooseImage = {}, onRemoveImage = {},
                    onSend = {}, canSend = false, notice = null, onRetry = {}, canRetry = false,
                    replyNavigation = request.value,
                    assistantContent = { entry ->
                        if (entry.replyAttachments.isNotEmpty()) Box(Modifier.size(70.dp).testTag("picture"))
                        else Text(entry.answer)
                    },
                )
            }
        }
        rule.onNodeWithTag("message-assistant-11").assertIsDisplayed()
        rule.runOnIdle { request.value = ReplyTurnNavigation(1L, 2) }
        val picture = rule.onNodeWithTag("picture")
        picture.assertIsDisplayed()
        val anchoredY = picture.fetchSemanticsNode().positionInRoot.y
        rule.runOnIdle { question.value += "another line\n".repeat(25) }
        picture.assertIsDisplayed()
        assertEquals(anchoredY, picture.fetchSemanticsNode().positionInRoot.y, 2f)
        rule.onNodeWithTag("return-to-latest").performClick()
        rule.onNodeWithTag("message-assistant-11").assertIsDisplayed()
        rule.runOnIdle { request.value = ReplyTurnNavigation(2L, 2) }
        picture.assertIsDisplayed()
        rule.runOnIdle { count.value++ }
        rule.onNodeWithTag("message-assistant-12").assertIsDisplayed()
    }
}
