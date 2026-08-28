package com.auralis.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCenterArchitectureTest {

    data class LineBounds(val top: Int, val height: Int) {
        val center: Float get() = top + (height / 2f)
    }

    @Test
    fun testTargetScrollAlignsActiveLyricCenterWithViewportCenter() {
        val viewportHeightPx = 800
        val viewportCenter = viewportHeightPx / 2f // 400f
        val maxScroll = 5000

        // Simulate lines with topPadding = 400 (viewportHeight / 2)
        val padding = 400
        val lines = listOf(
            LineBounds(top = padding, height = 44),                   // Line 0: single line
            LineBounds(top = padding + 44 + 26, height = 44),          // Line 1: single line (top = 470)
            LineBounds(top = padding + 44 + 26 + 44 + 26, height = 110), // Line 2: multi-line (top = 540)
            LineBounds(top = 540 + 110 + 26, height = 36)             // Line 3: short line (top = 676)
        )

        for ((index, line) in lines.withIndex()) {
            val activeLyricCenter = line.center
            val targetScroll = (activeLyricCenter - viewportCenter).toInt().coerceIn(0, maxScroll)

            // Calculate where the active line lands on screen after scrolling to targetScroll
            val onScreenCenter = activeLyricCenter - targetScroll

            assertEquals(
                "Line $index center must land exactly at viewportCenter (400f)",
                viewportCenter,
                onScreenCenter,
                0.01f
            )
        }
    }

    @Test
    fun testMultiLineLyricCenteringIsBasedOnEntireBlock() {
        val viewportCenter = 400f
        val maxScroll = 5000

        // 3-line lyric: height = 126px, top = 800px
        val multiLine = LineBounds(top = 800, height = 126)
        // Center of the 3-line block is top + 63px = 863px
        assertEquals(863f, multiLine.center, 0.01f)

        val targetScroll = (multiLine.center - viewportCenter).toInt().coerceIn(0, maxScroll)
        assertEquals(463, targetScroll)

        // After scrolling to 463:
        val onScreenTop = multiLine.top - targetScroll // 800 - 463 = 337
        val onScreenBottom = onScreenTop + multiLine.height // 337 + 126 = 463
        val onScreenCenter = onScreenTop + (multiLine.height / 2f) // 337 + 63 = 400
        assertEquals(viewportCenter, onScreenCenter, 0.01f)

        // Distance from onScreenTop to center == distance from center to onScreenBottom
        val topToCenter = onScreenCenter - onScreenTop
        val centerToBottom = onScreenBottom - onScreenCenter
        assertEquals(topToCenter, centerToBottom, 0.01f)
    }

    @Test
    fun testAdaptiveAnimationDurationCalculation() {
        fun computeDuration(distance: Int): Int = when {
            distance < 120 -> 500
            distance < 400 -> 600
            distance < 1000 -> 750
            else -> 900
        }

        // Small line-to-line shift (e.g. 70px) -> quick 500ms glide
        assertEquals(500, computeDuration(70))

        // Multi-line shift (e.g. 150px) -> fluid 600ms glide
        assertEquals(600, computeDuration(150))

        // Jump across multiple lines (e.g. 500px) -> 750ms smooth transition
        assertEquals(750, computeDuration(500))

        // Large seek jump (e.g. 2000px) -> 900ms transition
        assertEquals(900, computeDuration(2000))
    }

    @Test
    fun testScrollBoundariesRespected() {
        val viewportCenter = 400f
        val maxScroll = 1000

        // Lyric with center at 300 (above viewport center before padding)
        val negativeTarget = (300f - viewportCenter).toInt().coerceIn(0, maxScroll)
        assertEquals("Must clamp to minimum 0 scroll bound", 0, negativeTarget)

        // Lyric with center at 2000 (past maximum scroll limit)
        val overflowTarget = (2000f - viewportCenter).toInt().coerceIn(0, maxScroll)
        assertEquals("Must clamp to maximum scroll bound", maxScroll, overflowTarget)
    }
}
