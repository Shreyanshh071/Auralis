package com.auralis.music.windows.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverOnlyCanvasTest {
    private val side = CoverOnlyCanvas.COVER_SIDE
    private val width = CoverOnlyCanvas.FRAME_WIDTH
    private val height = CoverOnlyCanvas.FRAME_HEIGHT

    private val cover = IntArray(side * side) { i ->
        val x = i % side; val y = i / side
        (0xFF shl 24) or (((x * 8) and 255) shl 16) or (((y * 8) and 255) shl 8) or (((x xor y) * 16) and 255)
    }

    @Test
    fun coverPastedOnABlurIsFound() {
        val size = 44; val x0 = (width - size) / 2; val y0 = (height - size) / 2 - 12
        val frame = IntArray(width * height) { i ->
            val x = i % width; val y = i / width
            if (x in x0 until x0 + size && y in y0 until y0 + size)
                cover[((y - y0) * side / size) * side + ((x - x0) * side / size)]
            else 0xFF554433.toInt()
        }
        assertTrue(CoverOnlyCanvas.bestMatch(frame, width, height, cover, side, side) >= .85)
    }

    @Test
    fun aRealSceneIsNotTheCover() {
        val random = Random(3)
        val frame = IntArray(width * height) { (0xFF shl 24) or random.nextInt(0xFFFFFF) }
        assertTrue(CoverOnlyCanvas.bestMatch(frame, width, height, cover, side, side) < CoverOnlyCanvas.COVER_AVERAGE)
    }

    @Test
    fun ruleMatchesThePhoneAndTheMeasuredCanvases() {
        assertTrue(CoverOnlyCanvas.isCoverOnly(listOf(.79, .61)))                   // Softcore on the phone
        assertTrue(CoverOnlyCanvas.isCoverOnly(listOf(.94, .86, .78, .75)))         // Softcore on the PC
        assertFalse(CoverOnlyCanvas.isCoverOnly(listOf(.08, .09, .09, .10)))        // a real Canvas
        assertFalse(CoverOnlyCanvas.isCoverOnly(listOf(.95, .10, .10, .10)))        // opens on the cover, then moves on
    }
}
