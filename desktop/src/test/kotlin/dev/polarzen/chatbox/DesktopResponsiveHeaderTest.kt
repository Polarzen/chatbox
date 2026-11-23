package dev.polarzen.chatbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopResponsiveHeaderTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val files = TemporaryFolder()

    @Test
    fun headerReflowsAcrossSidebarAndWindowWidthChangesWithLiveControls() {
        val dir = files.root.toPath()
        val store = DesktopStore(dir.resolve("history.json"))
        val keys = NamedKeys(dir, DesktopCredentials(dir.resolve("credentials")))
        val cache = ReplyImageCache(dir.resolve("reply-images"))
        val appearance = mutableStateOf(DesktopAppearance())
        // The open 232 dp sidebar leaves the conversation pane exactly 639 dp wide.
        val width = mutableStateOf(871.dp)
        ui.setContent {
            Box(Modifier.requiredSize(width.value, 600.dp)) {
                DesktopTheme(appearance.value.dark) {
                    DesktopScreen(store, keys, cache, appearanceActions = {
                        DesktopAppearanceControls(appearance.value, { appearance.value = it }, { appearance.value = it })
                    })
                }
            }
        }

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
