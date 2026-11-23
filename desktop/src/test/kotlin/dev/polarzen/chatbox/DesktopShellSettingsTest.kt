package dev.polarzen.chatbox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopShellSettingsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun missingFileUsesDefaultsAndEditsSurviveRestart() {
        val dir = temp.root.toPath().resolve("settings")
        val store = DesktopShellSettingsStore(dir)
        assertEquals(DesktopShellSettings(true, DesktopHotkey.DEFAULT, false, true), store.load())
        val changed = DesktopShellSettings(false, requireNotNull(DesktopHotkey.parse("Ctrl+Alt+J")), true, false)
        store.save(changed)
        assertEquals(changed, DesktopShellSettingsStore(dir).load())
        store.save(DesktopShellSettings())
        assertEquals(DesktopShellSettings(), DesktopShellSettingsStore(dir).load())
        assertEquals(listOf("shell.json"), dir.toFile().list()!!.toList())
    }

    @Test
    fun damagedFileOrInvalidFieldsUseDefaults() {
        val dir = temp.root.toPath()
        val file = dir.resolve("shell.json")
        val store = DesktopShellSettingsStore(dir)
        Files.writeString(file, "{")
        assertEquals(DesktopShellSettings(), store.load())
        Files.writeString(file, """{"hotkeyEnabled":"false","hotkey":"wrong","closeToTray":true,"notifyReplies":false}""")
        assertEquals(DesktopShellSettings(closeToTray = true, notifyReplies = false), store.load())
    }
}

@OptIn(ExperimentalTestApi::class)
class DesktopShellSettingsDialogTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun rejectedShortcutPreservesDraftAndOtherSettingsCanBeSavedWithShortcutDisabled() {
        val attempts = mutableListOf<DesktopShellSettings>()
        var dismissed = false
        ui.setContent {
            DesktopTheme(false) {
                DesktopShellSettingsDialog(DesktopShellSettings(), trayAvailable = false, onSave = {
                    attempts.add(it)
                    if (it.hotkeyEnabled) "快捷键已被占用" else null
                }, onDismiss = { dismissed = true })
            }
        }
        ui.onNodeWithTag("shell-close-to-tray").assertIsNotEnabled()
        ui.onNodeWithTag("shell-hotkey").performTextReplacement("invalid")
        ui.onNodeWithTag("shell-save").assertIsNotEnabled()
        ui.onNodeWithTag("shell-hotkey").performTextReplacement("Ctrl+Alt+J")
        ui.onNodeWithTag("shell-notify-replies").performClick()
        ui.onNodeWithTag("shell-save").performClick()
        ui.onNodeWithText("快捷键已被占用").assertIsDisplayed()
        ui.onNodeWithText("当前快捷键：${DesktopHotkey.DEFAULT}").assertIsDisplayed()
        ui.onNodeWithTag("shell-hotkey").assertTextContains("Ctrl+Alt+J")
        ui.runOnIdle { assertFalse(dismissed) }
        ui.onNodeWithTag("shell-hotkey-enabled").performClick()
        ui.onNodeWithTag("shell-save").performClick()
        ui.runOnIdle {
            val expected = DesktopShellSettings(hotkey = requireNotNull(DesktopHotkey.parse("Ctrl+Alt+J")), notifyReplies = false)
            assertEquals(listOf(expected, expected.copy(hotkeyEnabled = false)), attempts)
            assertTrue(dismissed)
        }
    }
}
