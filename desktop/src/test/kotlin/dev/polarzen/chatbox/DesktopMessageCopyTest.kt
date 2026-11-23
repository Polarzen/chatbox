package dev.polarzen.chatbox

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

@OptIn(ExperimentalTestApi::class)
class DesktopMessageCopyTest {
    @get:Rule
    val rule = createComposeRule()
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun copiesWholeBodiesAndSelectedWordsToWindowsClipboard() {
        val entry = Entry("question first\nsecond 段", "answer first\n\nthird 段", "已完成")
        installChat(entry)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = clipboard.getContents(null)
        try {
            for ((role, body) in listOf("user" to entry.question, "assistant" to entry.answer)) {
                val bubble = hasAnyAncestor(hasTestTag("message-$role-0"))
                rule.onNode(hasText("复制正文") and bubble).performClick()
                rule.waitForIdle()
                assertEquals(body, clipboard.getData(DataFlavor.stringFlavor))

                val node = rule.onNodeWithText(body, substring = true)
                node.performMouseInput { doubleClick(Offset(4f, height - 6f)) }
                node.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
                rule.waitForIdle()
                assertEquals(body.substringAfterLast('\n').substringBefore(' '), clipboard.getData(DataFlavor.stringFlavor))
            }
        } finally {
            restoreClipboard(clipboard, previous)
        }
    }

    @Test
    fun busyClipboardFeedbackClearsOnSuccessfulButtonOrKeyboardRetry() {
        var occupied = true
        var copied: AnnotatedString? = null
        val clipboard = object : ClipboardManager {
            override fun getText() = copied
            override fun setText(annotatedString: AnnotatedString) {
                if (occupied) throw IllegalStateException("cannot open system clipboard")
                copied = annotatedString
            }
        }
        installChat(Entry("first second", "", "已完成"), clipboard)
        val button = rule.onNodeWithText("复制正文")
        val message = rule.onNodeWithText("first second")
        val notice = rule.onNodeWithText("剪贴板占用中，请再试一次。")
        button.performClick()
        notice.assertIsDisplayed()
        rule.runOnIdle { occupied = false }
        button.performClick()
        notice.assertDoesNotExist()
        assertEquals("first second", copied?.text)

        rule.runOnIdle { occupied = true }
        message.performMouseInput { doubleClick(Offset(4f, 6f)) }
        message.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        notice.assertIsDisplayed()
        rule.runOnIdle { occupied = false }
        message.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        notice.assertDoesNotExist()
        assertEquals("first", copied?.text)
    }

    @Test
    fun attachmentOnlyMessagesHaveNoCopyButton() {
        installChat(Entry("", "", "已完成", attachmentDisplayName = "example.png",
            replyAttachments = listOf(ReplyAttachment(null, 0, ReplyAttachmentType.AUDIO))))
        rule.onNodeWithText("复制正文").assertDoesNotExist()
    }

    @Test
    fun draggingAcrossAttachmentsCopiesOnlyReplyTextAndRetryRemainsClickable() {
        val image = ReplyAttachment("missing-image", 5, ReplyAttachmentType.IMAGE)
        val voice = ReplyAttachment(null, 5, ReplyAttachmentType.AUDIO)
        var retry: ReplyAttachmentKey? = null
        installChat(Entry("", "alphaomega", "已完成", replyAttachments = listOf(image, voice))) { retry = it }
        rule.waitUntil {
            rule.onAllNodes(hasText("图片尚未缓存。")).fetchSemanticsNodes().isNotEmpty()
        }
        val bubble = rule.onNodeWithTag("message-assistant-0")
        val origin = bubble.fetchSemanticsNode().boundsInRoot.topLeft
        val first = rule.onNodeWithText("助手：alpha").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithText("omega").fetchSemanticsNode().boundsInRoot
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = clipboard.getContents(null)
        try {
            bubble.performMouseInput {
                moveTo(Offset(first.left + 1f, first.center.y) - origin)
                press()
                moveTo(Offset(last.right - 1f, last.center.y) - origin)
                release()
            }
            bubble.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            rule.waitForIdle()
            assertEquals("助手：alphaomega", clipboard.getData(DataFlavor.stringFlavor))
        } finally {
            restoreClipboard(clipboard, previous)
        }
        rule.onNodeWithText("重试获取图片").performClick()
        assertEquals("missing-image", retry?.attachmentId)
    }

    private fun restoreClipboard(clipboard: Clipboard, previous: Transferable?) {
        rule.waitUntil(timeoutMillis = 2_000) {
            try {
                clipboard.setContents(previous ?: StringSelection(""), null)
                true
            } catch (_: IllegalStateException) {
                false // Windows briefly locks the clipboard while other programs read its new contents.
            }
        }
    }

    private fun installChat(entry: Entry, clipboard: ClipboardManager? = null, onRetryAttachment: (ReplyAttachmentKey) -> Unit = {}) {
        val images = ReplyImageCache(folder.newFolder("images").toPath())
        val audio = ReplyAudioCache(folder.newFolder("audio").toPath())
        rule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides (clipboard ?: LocalClipboardManager.current)) {
                DesktopChatLayout(
                    sessions = emptyList(), chat = Chat("copy-test", listOf(entry)), onSelectSession = {},
                    onNewSession = {}, canCreate = false, topActions = {}, draft = TextFieldValue(),
                    onDraft = {}, selectedImage = null, onChooseImage = {}, onRemoveImage = {}, onSend = {},
                    canSend = false, notice = null, onRetry = {}, canRetry = false,
                    assistantContent = {
                        DesktopReplyContent("profile", "copy-test", it.answer, it.replyAttachments,
                            images, audio, emptyMap(), emptyMap(), emptySet(), true, onRetryAttachment)
                    },
                )
            }
        }
    }
}
