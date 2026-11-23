package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DesktopSearchViewportTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun locatesExactOccurrenceAcrossMediaAndRelocatesAfterReturningToLatest() {
        val needle = "chosen phrase"
        val prefix = "Lead with $needle\n"
        val suffix = (1..100).joinToString("\n") { n -> "Line $n" + if (n == 62) " $needle" else "" }
        val body = mutableStateOf(suffix)
        val request = mutableStateOf<SearchHitNavigation?>(null)
        val count = mutableIntStateOf(2)
        val precedingMediaHeight = mutableIntStateOf(12)
        val mediaHeight = mutableIntStateOf(8)
        val start = prefix.length + suffix.indexOf(needle)
        fun locate(id: Long) = rule.runOnIdle {
            request.value = SearchHitNavigation(id, 0, true, start, start + needle.length)
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MessageViewport("profile", "session", count.intValue, Modifier.size(320.dp, 200.dp), request.value) {
                item {
                    SearchHitEntry(0) {
                        Column {
                            SearchHitText(needle, isAnswer = false)
                            SearchHitText("Assistant: $prefix", isAnswer = true, prefixLength = 11)
                            Spacer(Modifier.height(precedingMediaHeight.intValue.dp))
                            SearchHitText(body.value, isAnswer = true, bodyOffset = prefix.length,
                                modifier = Modifier.testTag("reply-body"))
                            Spacer(Modifier.height(mediaHeight.intValue.dp))
                        }
                    }
                }
                for (index in 1 until count.intValue) {
                    item {
                        Column {
                            Spacer(Modifier.height(220.dp))
                            Text("Last line of turn $index")
                        }
                    }
                }
            }
        }
        val viewport = rule.onNodeWithTag("message-region")
        fun bounds() = viewport.fetchSemanticsNode().boundsInRoot
        fun hitBox(): Rect {
            val node = rule.onNodeWithTag("reply-body").fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            check(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
            return results.single().getBoundingBox(suffix.indexOf(needle)).translate(node.positionInRoot)
        }
        fun highlighted(): Boolean {
            val region = bounds()
            val hit = hitBox().translate(-region.topLeft)
            val pixels = viewport.captureToImage().toPixelMap()
            return (hit.top.toInt() until hit.bottom.toInt()).any { y ->
                (hit.left.toInt() until hit.right.toInt()).any { x -> pixels[x, y] == lightDesktopPalette.searchBackground }
            }
        }
        fun settle() {
            rule.mainClock.advanceTimeBy(300)
            rule.waitForIdle()
        }
        fun assertLocated() {
            settle()
            assertEquals(bounds().center.y, hitBox().center.y, 2f)
            rule.onNodeWithTag("return-to-latest").assertIsDisplayed()
            assertTrue("The located glyph has a visible highlight", highlighted())
        }
        settle()
        rule.onNodeWithText("Last line of turn 1").assertIsDisplayed()
        locate(1)
        assertLocated()
        val readingY = hitBox().center.y
        rule.runOnIdle {
            body.value += "\nAnother streamed paragraph"
            mediaHeight.intValue = 140
        }
        rule.mainClock.advanceTimeBy(2200)
        assertEquals(readingY, hitBox().center.y, 1f)
        assertFalse("The highlight expires without moving the line", highlighted())
        rule.runOnIdle { precedingMediaHeight.intValue = 300 }
        settle()
        assertEquals("Decoded media above the line preserves its location", readingY, hitBox().center.y, 1f)

        viewport.performMouseInput {
            moveTo(center)
            scroll(-2f)
        }
        rule.waitUntil(4000) {
            rule.mainClock.advanceTimeBy(80)
            hitBox().center.y > readingY + 8f
        }
        settle()
        val manualY = hitBox().center.y
        rule.runOnIdle { precedingMediaHeight.intValue = 360 }
        settle()
        assertTrue("After a wheel gesture media growth cannot reclaim the search anchor",
            hitBox().center.y > manualY + bounds().height / 10f)

        rule.onNodeWithTag("return-to-latest").performClick()
        settle()
        rule.onNodeWithText("Last line of turn 1").assertIsDisplayed()
        rule.onNodeWithTag("return-to-latest").assertDoesNotExist()
        locate(2)
        assertLocated()

        rule.runOnIdle { count.intValue++ }
        settle()
        rule.onNodeWithText("Last line of turn 2").assertIsDisplayed()
        rule.onNodeWithTag("return-to-latest").assertDoesNotExist()
    }
}
