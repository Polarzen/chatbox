package dev.polarzen.chatbox

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopAppearanceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun preferencesRoundTripAndResetInsideApplicationDirectory() {
        val dir = temp.root.toPath().resolve("Chatbox")
        val store = DesktopAppearanceStore(dir)
        assertEquals(DesktopAppearance(), store.load())
        val changed = DesktopAppearance(pinned = true, dark = true, opacityPercent = 50)
        store.save(changed)
        assertEquals(changed, DesktopAppearanceStore(dir).load())
        store.save(DesktopAppearance())
        assertEquals(DesktopAppearance(), DesktopAppearanceStore(dir).load())
        assertEquals(listOf("appearance.json"), dir.toFile().list()!!.toList())
    }

    @Test
    fun invalidFieldsUseTheirOwnDefaults() {
        val dir = temp.root.toPath()
        val file = dir.resolve("appearance.json")
        val store = DesktopAppearanceStore(dir)
        Files.writeString(file, "broken")
        assertEquals(DesktopAppearance(), store.load())
        for (badOpacity in listOf("49", "101", "77.4", "\"75\"", "null")) {
            Files.writeString(file, """{"pinned":"true","dark":true,"opacityPercent":$badOpacity}""")
            assertEquals(DesktopAppearance(dark = true), store.load())
        }
        Files.writeString(file, """{"pinned":true,"dark":4,"opacityPercent":75}""")
        assertEquals(DesktopAppearance(pinned = true, opacityPercent = 75), store.load())
    }

    @Test
    fun bothPalettesKeepBodyTextSelectionsAndSearchMatchesLegible() {
        fun contrast(a: Color, b: Color): Float {
            val first = a.luminance()
            val second = b.luminance()
            return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
        }
        for (palette in listOf(lightDesktopPalette, darkDesktopPalette)) {
            for ((foreground, background) in listOf(
                palette.ink to palette.canvas, palette.ink to palette.panel,
                palette.muted to palette.accentSurface, palette.ink to palette.selection,
                palette.accent to palette.accentSurface, palette.onAccent to palette.accent,
                palette.searchInk to palette.searchBackground,
            )) {
                assertTrue("Insufficient text contrast", contrast(foreground, background) >= 4.5f)
            }
        }
    }
}
