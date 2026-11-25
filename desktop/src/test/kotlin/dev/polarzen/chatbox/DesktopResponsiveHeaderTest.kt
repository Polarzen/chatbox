package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopResponsiveHeaderTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    private fun render(initialWidth: Dp, density: Density) = mutableStateOf(initialWidth).also { width ->
        ui.mainClock.autoAdvance = false
        val dir = files.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("credentials")))
        val cache = ReplyImageCache(dir.resolve("reply-images"))
        val appearance = mutableStateOf(DesktopAppearance())
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                Box(Modifier.requiredSize(width.value, 600.dp)) {
                    DesktopTheme(appearance.value.dark) {
                        DesktopScreen(store, keys, cache, appearanceActions = {
                            DesktopAppearanceControls(appearance.value, { appearance.value = it }, { appearance.value = it })
                        })
                    }
                }
            }
        }
        ui.mainClock.advanceTimeBy(150)
    }

    @Test
    fun compactRailOverlayAndPinnedPanelPreserveDraftAcrossWindowSizes() {
        val width = render(900.dp, Density(1f))
        val draft = "调整窗口时保留的草稿"
        ui.onNodeWithTag("composer-field").performTextInput(draft)
        fun chatLeft() = ui.onNodeWithTag("partner-name").fetchSemanticsNode().boundsInRoot.left
        val start = chatLeft()
        assertEquals(52f, ui.onNodeWithTag("navigation-rail").fetchSemanticsNode().boundsInRoot.width, 1f)
        ui.onNodeWithTag("sidebar-toggle").performClick().also { ui.mainClock.advanceTimeBy(150) }
        ui.onNodeWithTag("new-session-button").assertIsDisplayed()
        assertEquals(start, chatLeft(), 1f)
        ui.onNodeWithTag("pin-sidebar").performClick().also { ui.mainClock.advanceTimeBy(150) }
        assertTrue(chatLeft() > start + 200f)
        ui.runOnIdle { width.value = 600.dp }
        ui.mainClock.advanceTimeBy(150)
        ui.onNodeWithTag("sidebar-toggle").performClick().also { ui.mainClock.advanceTimeBy(150) }
        ui.onNodeWithTag("new-session-button").assertIsDisplayed()
        assertEquals(start, chatLeft(), 1f)
        ui.onNodeWithTag("close-sidebar-button").performClick().also { ui.mainClock.advanceTimeBy(150) }
        assertEquals(draft, ui.onNodeWithTag("composer-field").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun searchAndSettingsRemainReachableAt125Percent() {
        render(640.dp, Density(1.25f))
        ui.onNodeWithTag("history-search-open").performClick().also { ui.mainClock.advanceTimeBy(150) }
        ui.onNodeWithTag("history-search-query").assertIsDisplayed()
        ui.onNodeWithTag("history-search-cancel").performClick().also { ui.mainClock.advanceTimeBy(150) }
        ui.onNodeWithTag("rail-settings").performClick().also { ui.mainClock.advanceTimeBy(150) }
        ui.onNodeWithTag("connection-control-status").assertIsDisplayed()
        ui.onNodeWithTag("appearance-open").performClick().also { ui.mainClock.advanceTimeBy(150) }
        ui.onNodeWithTag("appearance-menu").assertIsDisplayed()
    }
}
