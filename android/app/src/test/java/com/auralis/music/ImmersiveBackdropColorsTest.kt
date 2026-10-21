package com.auralis.music

import com.auralis.music.ui.player.ImmersiveBackdropColors
import org.junit.Assert.*
import org.junit.Test

class ImmersiveBackdropColorsTest {
    @Test fun `borders outside the square cover cannot create a black seam`() {
        val grey = 0xFF888888.toInt()
        for ((width, height) in listOf(12 to 8, 8 to 12)) {
            val source = IntArray(width * height) { index ->
                val x = index % width; val y = index / width
                if ((width > height && x in 2..9) || (height > width && y in 2..9)) grey else 0xFF000000.toInt()
            }
            val cropped = ImmersiveBackdropColors.texture(source, width, height)
            val reference = ImmersiveBackdropColors.texture(IntArray(64) { grey }, 8, 8)
            assertArrayEquals(reference, cropped)
        }
    }
    @Test fun `unrelated pink higher in the cover does not become a patch below it`() {
        val source = IntArray(100) { if (it / 10 < 7) 0xFFFF00FF.toInt() else 0xFF909090.toInt() }
        val result = ImmersiveBackdropColors.texture(source, 10, 10)
        result.forEach { pixel ->
            assertEquals((pixel ushr 16) and 255, (pixel ushr 8) and 255)
            assertEquals((pixel ushr 8) and 255, pixel and 255)
            assertTrue((pixel and 255) > 80)
        }
    }
    @Test fun `lower field changes gently without shuffled rows or hard color bands`() {
        val source = IntArray(16 * 16) { if (it % 16 < 8) 0xFF705080.toInt() else 0xFFB04060.toInt() }
        val result = ImmersiveBackdropColors.texture(source, 16, 16, size = 32)
        for (y in 1 until 32) for (x in 0 until 32) for (shift in listOf(0,8,16)) {
            val above = (result[(y-1)*32+x] ushr shift) and 255
            val below = (result[y*32+x] ushr shift) and 255
            assertTrue(below <= above)
            assertTrue(above - below <= 2)
        }
    }
}
