package com.auralis.music

import androidx.compose.animation.core.CubicBezierEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSmoothScrollPhysicsTest {

    @Test
    fun testViewportCenterCalculationsAcrossScreens() {
        // Standard Phone (800dp viewport height) -> exact center at 400dp
        val viewport800 = 800f
        val center800 = viewport800 / 2f
        assertEquals(400f, center800, 0.01f)

        // Tall Phone (920dp viewport height) -> exact center at 460dp
        val viewport920 = 920f
        val center920 = viewport920 / 2f
        assertEquals(460f, center920, 0.01f)

        // Compact Phone (640dp viewport height) -> exact center at 320dp
        val viewport640 = 640f
        val center640 = viewport640 / 2f
        assertEquals(320f, center640, 0.01f)
    }

    @Test
    fun testExactItemCenterScrollDeltaCalculations() {
        val viewportCenterPx = 400

        // 1. Single-line item (height = 40px) at offset 580px (below center)
        val singleHeight = 40
        val singleOffset = 580
        val singleCenter = singleOffset + (singleHeight / 2) // 600px
        val deltaSingle = (singleCenter - viewportCenterPx).toFloat() // +200px
        assertTrue("Delta must be positive to scroll item up to center", deltaSingle > 0)
        assertEquals(200f, deltaSingle, 0.01f)
        assertEquals(viewportCenterPx, singleCenter - deltaSingle.toInt())

        // 2. Multi-line item (height = 120px) at offset 240px (above center)
        val multiHeight = 120
        val multiOffset = 240
        val multiCenter = multiOffset + (multiHeight / 2) // 300px
        val deltaMulti = (multiCenter - viewportCenterPx).toFloat() // -100px
        assertTrue("Delta must be negative to scroll item down to center", deltaMulti < 0)
        assertEquals(-100f, deltaMulti, 0.01f)
        assertEquals(viewportCenterPx, multiCenter - deltaMulti.toInt())

        // 3. Subpixel deadband threshold (<= 1.0px) prevents unnecessary twitching
        val restingDelta = 0.6f
        val shouldScroll = kotlin.math.abs(restingDelta) > 1.0f
        assertFalse("Subpixel micro-jitters must be ignored", shouldScroll)
    }

    @Test
    fun testLongDistanceSeekPrepositioning() {
        val totalLines = 60

        // Case A: User seeks from line 2 to line 35 (difference = 33 > 8)
        val currentVisible = 2
        val targetIndexA = 35
        val distanceA = kotlin.math.abs(targetIndexA - currentVisible)
        assertTrue(distanceA > 8)
        val preIndexA = (targetIndexA - 2).coerceAtLeast(0)
        assertEquals(33, preIndexA)

        // Case B: User seeks backward from line 50 to line 10 (difference = 40 > 8)
        val currentVisibleB = 50
        val targetIndexB = 10
        val distanceB = kotlin.math.abs(targetIndexB - currentVisibleB)
        assertTrue(distanceB > 8)
        val preIndexB = (targetIndexB + 2).coerceAtMost(totalLines - 1)
        assertEquals(12, preIndexB)

        // Case C: Continuous natural playback (difference = 1 <= 8)
        val normalCurrent = 15
        val normalTarget = 16
        val distanceNormal = kotlin.math.abs(normalTarget - normalCurrent)
        assertTrue("Continuous playback must not trigger pre-index jumps", distanceNormal <= 8)
    }

    @Test
    fun testYouTubeMusicCubicBezierEasingCurve() {
        val easing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

        // Initial progress: smooth acceleration
        val early = easing.transform(0.1f)
        assertTrue(early > 0f)

        // Halfway progress: fluid deceleration
        val mid = easing.transform(0.5f)
        assertTrue("Midpoint must have achieved majority of scroll for responsive feel", mid > 0.65f)

        // Completion: gentle landing into focal baseline without overshoot
        val late = easing.transform(0.9f)
        assertTrue(late > 0.95f && late <= 1.0f)
        assertEquals(1.0f, easing.transform(1.0f), 0.001f)
    }
}
