package dev.polarzen.chatbox

import com.sun.jna.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopHotkeyTest {
    @Test
    fun parsesWin32MasksAndFormatsCanonicalNames() {
        assertEquals("Ctrl+Alt+J", DesktopHotkey.DEFAULT.toString())
        assertEquals(DesktopHotkey.DEFAULT, DesktopHotkey.parse(" alt + CTRL + j "))
        assertEquals(DesktopHotkey(4, '0'.code), DesktopHotkey.parse("Shift+0"))
        assertEquals(DesktopHotkey(8, 0x70), DesktopHotkey.parse("Win+F1"))
        assertEquals(DesktopHotkey(15, 0x87), DesktopHotkey.parse("Ctrl+Win+Shift+Alt+f24"))
        for (mask in 1..15) {
            for (code in ('0'.code..'9'.code) + ('A'.code..'Z'.code) + (0x70..0x87) - 0x7b) {
                val key = DesktopHotkey(mask, code)
                assertEquals(key, DesktopHotkey.parse(key.toString()))
            }
        }
    }

    @Test
    fun rejectsMissingDuplicateReservedAndUnsupportedKeys() {
        for (text in listOf("", "J", "Ctrl", "Ctrl+", "+J", "Ctrl++J", "Ctrl+Ctrl+J", "Alt+A+B",
            "Ctrl+F12", "Alt+F0", "Alt+F25", "Alt+F01", "Alt+字", "Alt+Escape", "Meta+T")) {
            assertNull(text, DesktopHotkey.parse(text))
        }
    }

    @Test(timeout = 15000)
    fun windowsCollisionPreservesBothOriginalRegistrationsAndCloseReleasesThem() {
        assumeTrue(Platform.isWindows())
        val first = DesktopHotkey.parse("Ctrl+Alt+Shift+F23")!!
        val second = DesktopHotkey.parse("Ctrl+Alt+Shift+F24")!!
        WindowsHotkeys {}.use { owner ->
            assertNull(owner.change(first))
            WindowsHotkeys {}.use { other ->
                assertNull(other.change(second))
                assertNotNull(owner.change(second))
                assertNotNull(other.change(first))
                assertNull(other.change(null))
                assertNull(owner.change(second))
                assertNull(other.change(first))
            }
            assertNull(owner.change(first))
        }
    }
}
