package dev.polarzen.chatbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class ChatUrlStyleTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun offsetsKeepBalancedUrlPunctuationAndExcludeSurroundingProse() {
        val urls = listOf("https://例子.test/wiki/Topic_(detail)", "HTTP://[::1]:8080/a", "https://b.test/q?a=1&b=2")
        val raw = "🙂链接（${urls[0]}）。[${urls[1]}], (${urls[2]}). xhttps://bad.test or ftp://example.test"
        assertEquals(urls.map {
            val start = raw.indexOf(it)
            TextRange(start, start + it.length)
        }, chatUrlRanges(raw))
    }

    @Test
    fun themesStyleUrlAcrossMediaAndAllowOrdinaryTextSelection() {
        val dark = mutableStateOf(false)
        val user = "See https://example.test/token!"
        val answer = "🙂见 https://split.test/path。结束"
        val split = answer.indexOf("lit.test")
        val parts = listOf(answer.substring(0, split), answer.substring(split))
        val images = ReplyImageCache(temp.newFolder("images").toPath())
        val audio = ReplyAudioCache(temp.newFolder("audio").toPath())
        val clipboard = object : ClipboardManager {
            var copied: AnnotatedString? = null
            override fun getText() = copied
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
        }
        rule.setContent {
            DesktopTheme(dark.value) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Column {
                        SelectionContainer { SearchHitText(user, isAnswer = false) }
                        SelectionContainer {
                            DesktopReplyContent("profile", "session", answer,
                                listOf(ReplyAttachment(null, split, ReplyAttachmentType.IMAGE)), images, audio,
                                emptyMap(), emptyMap(), emptySet(), false, {})
                        }
                    }
                }
            }
        }
        fun layoutOf(literal: String): TextLayoutResult {
            val node = rule.onNodeWithText(literal, useUnmergedTree = true).fetchSemanticsNode()
            assertFalse(node.config.contains(SemanticsActions.OnClick))
            val layouts = mutableListOf<TextLayoutResult>()
            check(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            return layouts.single()
        }
        for (night in listOf(false, true)) {
            rule.runOnIdle { dark.value = night }
            val palette = if (night) darkDesktopPalette else lightDesktopPalette
            for ((literal, urlPart) in listOf(
                user to "https://example.test/token",
                parts[0] to "https://sp",
                parts[1] to "lit.test/path",
            )) {
                val rendered = layoutOf(literal).layoutInput.text
                assertEquals(literal, rendered.text)
                val url = rendered.spanStyles.single()
                assertEquals(urlPart, literal.substring(url.start, url.end))
                assertEquals(palette.accent, url.item.color)
                assertEquals(TextDecoration.Underline, url.item.textDecoration)
            }
        }
        val token = layoutOf(user).getBoundingBox(user.indexOf("token") + 1).center
        val node = rule.onNodeWithText(user, useUnmergedTree = true)
        node.performMouseInput { doubleClick(token) }
        node.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        rule.runOnIdle { assertEquals("token", clipboard.copied?.text) }
    }

    @Test
    fun exactSearchHighlightTakesPrecedenceOverDarkThemeUrlInk() {
        val url = "https://example.test"
        val request = mutableStateOf<SearchHitNavigation?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            DesktopTheme(true) {
                MessageViewport("p", "s", 1, Modifier.size(420.dp, 160.dp).background(darkDesktopPalette.panel),
                    request.value) {
                    item { SearchHitEntry(0) { SearchHitText(url, isAnswer = false, fontSize = 24.sp) } }
                }
            }
        }
        fun painted(color: Color): Boolean {
            val pixels = rule.onNodeWithText(url).captureToImage().toPixelMap()
            return (0 until pixels.height).any { y -> (0 until pixels.width).any { x -> pixels[x, y] == color } }
        }
        rule.runOnIdle { request.value = SearchHitNavigation(1, 0, false, 0, url.length) }
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        assertTrue(painted(darkDesktopPalette.searchBackground))
        assertTrue(painted(darkDesktopPalette.searchInk))
        rule.mainClock.advanceTimeBy(2200)
        rule.waitForIdle()
        assertFalse(painted(darkDesktopPalette.searchBackground))
        assertTrue(painted(darkDesktopPalette.accent))
    }
}
