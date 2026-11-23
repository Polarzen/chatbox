package dev.polarzen.chatbox

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DesktopAppearanceControlsTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun pinThemeSliderAndResetsUseGlobalAppearanceCallbacks() {
        val appearance = mutableStateOf(DesktopAppearance())
        val commits = mutableListOf<DesktopAppearance>()
        ui.setContent {
            DesktopTheme(appearance.value.dark) {
                DesktopAppearanceControls(appearance.value, { appearance.value = it }, {
                    appearance.value = it
                    commits += it
                })
            }
        }
        ui.onNodeWithTag("appearance-pin").performClick()
        ui.onNodeWithTag("appearance-open").performClick()
        ui.onNodeWithText("浅色（Light）").assertIsDisplayed()
        ui.onNodeWithText("深色（Dark）").performClick()
        ui.runOnIdle { assertEquals(DesktopAppearance(pinned = true, dark = true), commits.last()) }
        val slider = ui.onNodeWithTag("appearance-opacity")
        slider.performMouseInput {
            moveTo(Offset(width - 10.dp.toPx(), centerY))
            press()
            moveTo(center)
        }
        val preview = ui.runOnIdle {
            assertEquals(2, commits.size)
            appearance.value.opacityPercent.also { assertTrue(it in 51..99) }
        }
        ui.onNodeWithText("${preview}%").assertIsDisplayed()
        slider.performMouseInput { release() }
        ui.runOnIdle { assertEquals(appearance.value, commits.last()) }
        ui.onNodeWithText("恢复 100%").performClick()
        ui.runOnIdle { assertEquals(DesktopAppearance(pinned = true, dark = true), commits.last()) }
        ui.onNodeWithText("恢复默认外观").performClick()
        ui.runOnIdle { assertEquals(DesktopAppearance(), commits.last()) }
    }
}
