package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopResponsiveHeaderTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    private fun render(initialWidth: Dp, density: Density, height: Dp = 600.dp) = mutableStateOf(initialWidth).also { width ->
        val dir = files.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("credentials")))
        val cache = ReplyImageCache(dir.resolve("reply-images"))
        val appearance = mutableStateOf(DesktopAppearance())
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                Box(Modifier.requiredSize(width.value, height)) {
                    DesktopTheme(appearance.value.dark) {
                        DesktopScreen(store, keys, cache, appearanceActions = {
                            DesktopAppearanceControls(appearance.value, { appearance.value = it }, { appearance.value = it })
                            // The desktop shell supplies a 36 dp menu button after the appearance controls.
                            Spacer(Modifier.width(36.dp))
                        })
                    }
                }
            }
        }
    }

    @Test
    fun headerReflowsAcrossSidebarAndWindowWidthChangesWithLiveControls() {
        // The open 232 dp sidebar leaves the conversation pane exactly 639 dp wide.
        val width = render(871.dp, Density(1f))
        val draft = "Unsent draft before resizing"
        ui.onNodeWithTag("composer-field").performTextInput(draft)
        assertHeader(compact = true)
        ui.onNodeWithTag("sidebar-toggle").performClick()
        assertHeader(compact = false)
        ui.onNodeWithTag("sidebar-toggle").performClick()
        assertHeader(compact = true)

        ui.onNodeWithTag("appearance-open").performClick()
        ui.onNodeWithTag("appearance-menu").assertIsDisplayed()
        for (windowWidth in listOf(872.dp, 871.dp)) {
            ui.runOnIdle { width.value = windowWidth }
            assertHeader(compact = windowWidth == 871.dp)
            ui.onNodeWithTag("appearance-menu").assertIsDisplayed()
            assertEquals(draft, ui.onNodeWithTag("composer-field").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text)
        }
    }

    @Test
    fun heartPanelSurvivesResizingFromMinimumWidthAndComposerRemainsEditable() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        // The focused composer keeps scheduling caret frames while the panel is open.
        ui.mainClock.autoAdvance = false
        val width = render(480.dp, Density(1f), height = 400.dp)
        ui.mainClock.advanceTimeByFrame()
        assertHeader(compact = true)
        ui.onNodeWithContentDescription("展开密钥菜单", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("composer-field").performTextInput("Before")
        ui.mainClock.advanceTimeByFrame()
        val heart = ui.onNodeWithText("心率").assertIsDisplayed()
        val heartBounds = heart.fetchSemanticsNode().boundsInRoot
        val firstAction = ui.onNodeWithTag("history-search-open").fetchSemanticsNode().boundsInRoot
        assertTrue(heartBounds.width > 0 && heartBounds.right <= firstAction.left)
        heart.performClick()
        ui.mainClock.advanceTimeBy(200)
        for (windowWidth in listOf(640.dp, 480.dp)) {
            ui.runOnIdle { width.value = windowWidth }
            ui.mainClock.advanceTimeByFrame()
            assertHeader(compact = windowWidth == 480.dp)
            ui.onNodeWithText("手环心率").assertIsDisplayed()
        }
        ui.onNodeWithText("关闭面板").performClick()
        ui.mainClock.advanceTimeBy(200)
        ui.onNodeWithTag("composer-field").performTextInput(" after")
        ui.mainClock.advanceTimeByFrame()
        assertEquals("Before after", ui.onNodeWithTag("composer-field").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun firstWidePaneAt125PercentScaleKeepsShortConnectionLabel() {
        render(640.dp, Density(1.25f))
        assertHeader(compact = false)
        ui.onNodeWithTag("connection-control-status").assertTextEquals("未连")
        val toggle = ui.onNodeWithTag("sidebar-toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val title = ui.onNodeWithText("新会话").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val actions = ui.onNodeWithTag("history-search-open").fetchSemanticsNode().boundsInRoot
        assertTrue("The title should fit between the toggle and actions",
            title.width > 0 && title.left >= toggle.right && title.right <= actions.left)
    }

    private fun assertHeader(compact: Boolean) {
        val toggle = ui.onNodeWithTag("sidebar-toggle").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("history-search-open", "appearance-pin", "appearance-open",
            "connection-control-status", "connection-control-key-button")) {
            val control = ui.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            if (compact) {
                assertTrue("$tag should occupy the second header row", control.top >= toggle.bottom)
            } else {
                assertTrue("$tag should share the sidebar toggle's row", control.center.y in toggle.top..toggle.bottom)
            }
        }
    }
}
