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
