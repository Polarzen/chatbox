package dev.polarzen.chatbox

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class DesktopStreamingSelectionTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun selectedWordSurvivesAppendsAndAttachmentAtEndOfText() {
        val answer = mutableStateOf("first middle last")
        val attachments = mutableStateOf(emptyList<ReplyAttachment>())
        val images = ReplyImageCache(folder.newFolder("images").toPath())
        val audio = ReplyAudioCache(folder.newFolder("audio").toPath())
        val clipboard = object : ClipboardManager {
            var copied: AnnotatedString? = null
            override fun getText() = copied
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
        }
        rule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                SelectionContainer {
                    DesktopReplyContent("profile", "session", answer.value, attachments.value,
                        images, audio, emptyMap(), emptyMap(), emptySet(), false, {})
                }
            }
        }
        val node = rule.onNodeWithText("middle", substring = true)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val position = layout.getBoundingBox(layout.layoutInput.text.text.indexOf("middle") + 2).center
        node.performMouseInput { doubleClick(position) }
        fun assertCopy() {
            rule.runOnIdle { clipboard.copied = null }
            node.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            rule.runOnIdle { assertEquals("middle", clipboard.copied?.text) }
        }
        assertCopy()
        rule.runOnIdle { answer.value += " streamed suffix" }
        assertCopy()
        rule.runOnIdle {
            attachments.value = listOf(ReplyAttachment(null, answer.value.length, ReplyAttachmentType.IMAGE))
        }
        assertCopy()
        rule.runOnIdle { answer.value += " text after attachment" }
        assertCopy()
    }
}
