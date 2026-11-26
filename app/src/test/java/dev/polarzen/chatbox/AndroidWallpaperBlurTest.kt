package dev.polarzen.chatbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWallpaperBlurTest {
    @Test fun constantAndZeroRadiusAreStable() {
        val solid = IntArray(6) { 0xff6941a0.toInt() }
        assertArrayEquals(solid, blurWallpaperPixels(solid, 1, 6, 8))
        val source = intArrayOf(0xffffffff.toInt(), 0xff000000.toInt())
        assertArrayEquals(source, blurWallpaperPixels(source, 2, 1, 0))
    }

    @Test fun contrastingNeighborsBlendWithoutColorCast() {
        val source = intArrayOf(-1, 0xff000000.toInt(), 0xff000000.toInt())
        val softened = blurWallpaperPixels(source, 3, 1, 1)
        val red = softened[1] ushr 16 and 255
        assertTrue(red in 1..254)
        assertEquals(red, softened[1] and 255)
        assertEquals(red, softened[1] ushr 8 and 255)
        assertEquals(-1, source[0])
    }
}
