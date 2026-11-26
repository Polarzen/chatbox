package dev.polarzen.chatbox

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopThemePreviewTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun previewIsLocalUntilAppliedAndCancelLeavesTheSavedThemeAlone() {
        val state = SharedThemeState(temp.root.toPath(), ChatTheme())
        val open = mutableStateOf(true)
        ui.setContent {
            DesktopTheme(state.theme.dark, state.theme) {
                if (open.value) DesktopThemeEditor(state, null) { open.value = false }
            }
        }
        ui.onNodeWithText("色值 #RRGGBB").performTextReplacement("#147D70")
        ui.onNodeWithTag("theme-preview").performScrollTo().assertIsDisplayed()
        ui.runOnIdle { assertEquals(ChatTheme(), state.theme) }
        ui.onNodeWithText("取消").performClick()
        ui.runOnIdle {
            assertEquals(ChatTheme(), state.theme)
            open.value = true
        }
        ui.onNodeWithText("色值 #RRGGBB").performTextReplacement("#147D70")
        ui.onNodeWithText("应用").performClick()
        ui.waitUntil(5_000) { !open.value }
        assertEquals("#147D70", state.theme.accent)
        assertEquals(state.theme, SharedThemeState(temp.root.toPath(), ChatTheme()).theme)
    }
}
