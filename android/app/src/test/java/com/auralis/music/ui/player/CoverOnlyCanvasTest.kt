package com.auralis.music.ui.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverOnlyCanvasTest {
    private val coverSide = 32

    /** A busy, non-uniform "album cover". */
    private val cover = IntArray(coverSide * coverSide) { i ->
        val x = i % coverSide; val y = i / coverSide
        val r = (x * 8) and 255; val g = (y * 8) and 255; val b = ((x xor y) * 16) and 255
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun frame(fill: (Int, Int) -> Int, width: Int = 54, height: Int = 96) =
        IntArray(width * height) { i -> fill(i % width, i / width) }

    @Test
    fun coverPastedOnABlurIsDetected() {
        val width = 54; val height = 96
        val size = 44; val x0 = (width - size) / 2; val y0 = (height - size) / 2 - 3
        val pixels = frame({ x, y ->
            if (x in x0 until x0 + size && y in y0 until y0 + size)
                cover[((y - y0) * coverSide / size) * coverSide + ((x - x0) * coverSide / size)]
            else (0xFF shl 24) or (0x55 shl 16) or (0x44 shl 8) or 0x33 // the blurred surround
        })
        assertTrue(CoverOnlyCanvas.frameShowsCover(pixels, width, height, cover, coverSide, coverSide))
    }

    @Test
    fun aRealMovingSceneIsNotTheCover() {
        val random = Random(7)
        val pixels = frame({ _, _ -> (0xFF shl 24) or random.nextInt(0xFFFFFF) })
        assertFalse(CoverOnlyCanvas.frameShowsCover(pixels, 54, 96, cover, coverSide, coverSide))
    }

    @Test
    fun theRuleUsesMeasuredScoresFromRealCanvases() {
        // Softcore: its pasted cover slowly zooms, so one frame matches closely and the other less.
        assertTrue(CoverOnlyCanvas.isCoverOnly(listOf(.79, .61)))   // Softcore on the phone
        assertTrue(CoverOnlyCanvas.isCoverOnly(listOf(.94, .86, .78, .75)))
        assertFalse(CoverOnlyCanvas.isCoverOnly(listOf(.08, .09, .09, .10))) // a real Canvas
        assertFalse(CoverOnlyCanvas.isCoverOnly(listOf(.95, .10, .10, .10))) // opens on the cover, then moves on
        // A real scene, and a Canvas that opens on the cover and then moves on, are kept.
    }

    @Test
    fun aFlatFrameIsNotTheCover() {
        val pixels = frame({ _, _ -> 0xFF202020.toInt() })
        assertFalse(CoverOnlyCanvas.frameShowsCover(pixels, 54, 96, cover, coverSide, coverSide))
    }
}
