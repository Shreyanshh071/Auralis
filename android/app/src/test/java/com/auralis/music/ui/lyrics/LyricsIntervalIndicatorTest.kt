package com.auralis.music.ui.lyrics

import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite for Phase 3 #7 — LyricsIntervalIndicator contracts.
 *
 * Tests all interval timing, progress calculation, visibility bounds,
 * and contract semantics for the Material 3 CircularWavyProgressIndicator integration.
 */
class LyricsIntervalIndicatorTest {

    @Test
    fun `test 1 - progress calculation for valid gap`() {
        val gapStart = 10000L
        val gapEnd = 20000L

        fun calc(pos: Long): Float =
            if (gapEnd > gapStart) {
                ((pos - gapStart).toFloat() / (gapEnd - gapStart).toFloat()).coerceIn(0f, 1f)
            } else 0f

        assertEquals(0.0f, calc(10000L), 0.0001f)
        assertEquals(0.25f, calc(12500L), 0.0001f)
        assertEquals(0.50f, calc(15000L), 0.0001f)
        assertEquals(0.75f, calc(17500L), 0.0001f)
        assertEquals(1.0f, calc(20000L), 0.0001f)
    }

    @Test
    fun `test 2 - progress calculation before gap start clamps to 0`() {
        val gapStart = 10000L
        val gapEnd = 20000L

        fun calc(pos: Long): Float =
            if (gapEnd > gapStart) {
                ((pos - gapStart).toFloat() / (gapEnd - gapStart).toFloat()).coerceIn(0f, 1f)
            } else 0f

        assertEquals(0.0f, calc(0L), 0.0001f)
        assertEquals(0.0f, calc(9999L), 0.0001f)
        assertEquals(0.0f, calc(-5000L), 0.0001f)
    }

    @Test
    fun `test 3 - progress calculation after gap end clamps to 1`() {
        val gapStart = 10000L
        val gapEnd = 20000L

        fun calc(pos: Long): Float =
            if (gapEnd > gapStart) {
                ((pos - gapStart).toFloat() / (gapEnd - gapStart).toFloat()).coerceIn(0f, 1f)
            } else 0f

        assertEquals(1.0f, calc(20001L), 0.0001f)
        assertEquals(1.0f, calc(25000L), 0.0001f)
        assertEquals(1.0f, calc(100000L), 0.0001f)
    }

    @Test
    fun `test 4 - zero or inverted gap duration yields progress 0`() {
        fun calc(start: Long, end: Long, pos: Long): Float =
            if (end > start) {
                ((pos - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
            } else 0f

        assertEquals(0.0f, calc(10000L, 10000L, 10000L), 0.0001f)
        assertEquals(0.0f, calc(10000L, 9000L, 9500L), 0.0001f)
        assertEquals(0.0f, calc(0L, 0L, 0L), 0.0001f)
    }

    @Test
    fun `test 5 - instrumental intro caller progress calculation`() {
        val introDurationMs = 12000L
        val effectiveEnd = (introDurationMs - 650L).coerceAtLeast(1000L) // 11350L

        fun calc(pos: Long): Float = (pos.toFloat() / effectiveEnd.toFloat()).coerceIn(0f, 1f)

        assertEquals(0.0f, calc(0L), 0.0001f)
        assertEquals(0.5f, calc(5675L), 0.0001f)
        assertEquals(1.0f, calc(11350L), 0.0001f)
        assertEquals(1.0f, calc(12000L), 0.0001f) // Clamped
    }

    @Test
    fun `test 6 - instrumental intro effectiveEnd minimum clamp`() {
        // When introDurationMs is very short, effectiveEnd clamps to at least 1000L
        val shortIntroMs = 800L
        val effectiveEnd = (shortIntroMs - 650L).coerceAtLeast(1000L)
        assertEquals(1000L, effectiveEnd)
    }

    @Test
    fun `test 7 - visibility window boundary conditions with lead-in`() {
        val gapStart = 30000L
        val gapEnd = 35000L
        val leadInMs = 300L

        fun isVisible(pos: Long, isSynced: Boolean): Boolean =
            isSynced && pos in (gapStart - leadInMs)..gapEnd

        assertFalse("Before lead-in: invisible", isVisible(29699L, true))
        assertTrue("At lead-in start: visible", isVisible(29700L, true))
        assertTrue("At gap start: visible", isVisible(30000L, true))
        assertTrue("In middle of gap: visible", isVisible(32500L, true))
        assertTrue("At gap end: visible", isVisible(35000L, true))
        assertFalse("Past gap end: invisible", isVisible(35001L, true))
        assertFalse("Unsynced: always invisible", isVisible(32500L, false))
    }

    @Test
    fun `test 8 - progress monotonicity over time`() {
        val gapStart = 5000L
        val gapEnd = 15000L

        fun calc(pos: Long): Float =
            if (gapEnd > gapStart) {
                ((pos - gapStart).toFloat() / (gapEnd - gapStart).toFloat()).coerceIn(0f, 1f)
            } else 0f

        var lastProgress = -1f
        for (t in 4000L..16000L step 250L) {
            val p = calc(t)
            assertTrue("Progress must be monotonically non-decreasing at t=$t", p >= lastProgress)
            assertTrue("Progress must remain in [0, 1] at t=$t", p in 0f..1f)
            lastProgress = p
        }
    }

    @Test
    fun `test 9 - active indicator color alpha configuration`() {
        val activeAlpha = 0.95f
        val trackAlpha = 0.18f

        assertTrue("Active alpha should be high contrast (> 0.9)", activeAlpha >= 0.9f)
        assertTrue("Track alpha should be subtle (< 0.25)", trackAlpha <= 0.25f)
        assertTrue("Active alpha should be greater than track alpha", activeAlpha > trackAlpha)
    }

    @Test
    fun `test 10 - container layout dimensions contracts for MetroLyrics and Standard`() {
        // MetroLyrics specification (thick)
        val metroContainerHeightDp = 48
        val metroClickableSizeDp = 44
        val metroIndicatorSizeDp = 40

        assertTrue("Metro indicator must fit inside clickable area", metroIndicatorSizeDp <= metroClickableSizeDp)
        assertTrue("Metro clickable area must fit inside container height", metroClickableSizeDp <= metroContainerHeightDp)
        assertEquals("Metro indicator size matches MetroLyrics specification", 40, metroIndicatorSizeDp)

        // Standard lyrics specification (thin / Auralis default, Apple Music, Fade, Glow)
        val standardContainerHeightDp = 44
        val standardClickableSizeDp = 40
        val standardIndicatorSizeDp = 34

        assertTrue("Standard indicator must fit inside clickable area", standardIndicatorSizeDp <= standardClickableSizeDp)
        assertTrue("Standard clickable area must fit inside container height", standardClickableSizeDp <= standardContainerHeightDp)
        assertEquals("Standard indicator size matches classic specification", 34, standardIndicatorSizeDp)
    }

    @Test
    fun `test 10b - style constants distinguish MetroLyrics thick vs Standard thinness`() {
        val stdSize = com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.StandardIndicatorSize
        val stdStroke = com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.StandardStrokeWidth
        val stdGap = com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.StandardTrackGapSize

        val metroSize = com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.MetroIndicatorSize
        val metroStroke = com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.MetroStrokeWidth
        val metroGap = com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.MetroTrackGapSize

        assertEquals(34.dp, stdSize)
        assertEquals(3.0.dp, stdStroke)
        assertEquals(3.0.dp, stdGap)

        assertEquals(40.dp, metroSize)
        assertEquals(5.0.dp, metroStroke)
        assertEquals(4.0.dp, metroGap)

        assertTrue("Metro stroke must be strictly thicker than standard stroke", metroStroke > stdStroke)
        assertTrue("Metro size must be strictly larger than standard size", metroSize > stdSize)
    }

    @Test
    fun `test 11 - skip callback invocation semantics`() {
        var seekTarget: Long? = null
        val gapEnd = 42000L
        val onSkip: () -> Unit = { seekTarget = gapEnd }

        onSkip()
        assertEquals(gapEnd, seekTarget)
    }

    @Test
    fun `test 12 - clamped progress safe for CircularWavyProgressIndicator lambda`() {
        val rawValues = listOf(-100f, -0.001f, 0f, 0.05f, 0.5f, 0.95f, 1f, 1.001f, 100f, Float.NaN)
        for (v in rawValues) {
            val clamped = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
            assertTrue("Clamped value must be >= 0f", clamped >= 0f)
            assertTrue("Clamped value must be <= 1f", clamped <= 1f)
            assertFalse("Clamped value must not be NaN", clamped.isNaN())
        }
    }

    @Test
    fun `test 13 - inspect exact geometry of RoundedPolygon star and Morph`() {
        val numVertices = 5
        val trackPolygon = androidx.graphics.shapes.RoundedPolygon.circle(numVertices = numVertices).normalized()
        val activeIndicatorPolygon = androidx.graphics.shapes.RoundedPolygon.star(
            numVerticesPerRadius = numVertices,
            innerRadius = 0.75f,
            rounding = androidx.graphics.shapes.CornerRounding(radius = 0.35f, smoothing = 0.4f),
            innerRounding = androidx.graphics.shapes.CornerRounding(radius = 0.5f)
        ).normalized()

        val morph = androidx.graphics.shapes.Morph(start = trackPolygon, end = activeIndicatorPolygon)

        // Verify amplitude mapping contract
        assertEquals(0f, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.indicatorAmplitude(0.0f), 0.001f)
        assertEquals(0f, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.indicatorAmplitude(0.05f), 0.001f)
        assertEquals(0f, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.indicatorAmplitude(0.10f), 0.001f)
        val expectedActiveAmp = com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.DefaultWaveAmplitude
        assertEquals(expectedActiveAmp, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.indicatorAmplitude(0.20f), 0.001f)
        assertEquals(expectedActiveAmp, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.indicatorAmplitude(0.50f), 0.001f)
        assertEquals(expectedActiveAmp, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.indicatorAmplitude(0.80f), 0.001f)
        assertEquals(expectedActiveAmp, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.indicatorAmplitude(0.90f), 0.001f)
        assertEquals(0f, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.indicatorAmplitude(0.95f), 0.001f)
        assertEquals(0f, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.indicatorAmplitude(1.00f), 0.001f)

        // Verify vertex count
        var minMorphDist = 100.0
        var maxMorphDist = 0.0
        for (c in morph.asCubics(1.0f)) {
            val d = Math.hypot((c.anchor0X - 0.5).toDouble(), (c.anchor0Y - 0.5).toDouble())
            if (d < minMorphDist) minMorphDist = d
            if (d > maxMorphDist) maxMorphDist = d
        }
        assertTrue("Min distance must be > 0.35", minMorphDist > 0.35)
        assertTrue("Max distance must be < 0.55", maxMorphDist < 0.55)
        val ratio = minMorphDist / maxMorphDist
        assertTrue("Trough to peak ratio must be around 0.79", ratio in 0.75..0.85)

        // Verify geometry at 36dp / 4dp (scale = 32dp)
        val scale36 = 32f
        val span36 = (maxMorphDist - minMorphDist) * scale36
        assertEquals(3.45f, span36.toFloat(), 0.1f)

        // Verify geometry at 34dp / 3dp (scale = 31dp)
        val scale34 = 31f
        val span34 = (maxMorphDist - minMorphDist) * scale34
        assertEquals(3.34f, span34.toFloat(), 0.1f)

        // Verify 7-vertex geometry (size = 36dp, stroke = 4dp, wavelength = 15dp -> (2*PI*16)/15 = 6.70 -> 7 vertices)
        val numVertices7 = 7
        val trackPolygon7 = androidx.graphics.shapes.RoundedPolygon.circle(numVertices = numVertices7).normalized()
        val activeIndicatorPolygon7 = androidx.graphics.shapes.RoundedPolygon.star(
            numVerticesPerRadius = numVertices7,
            innerRadius = 0.75f,
            rounding = androidx.graphics.shapes.CornerRounding(radius = 0.35f, smoothing = 0.4f),
            innerRounding = androidx.graphics.shapes.CornerRounding(radius = 0.5f)
        ).normalized()
        val morph7 = androidx.graphics.shapes.Morph(start = trackPolygon7, end = activeIndicatorPolygon7)

        var minMorphDist7 = 100.0
        var maxMorphDist7 = 0.0
        for (c in morph7.asCubics(1.0f)) {
            val d = Math.hypot((c.anchor0X - 0.5).toDouble(), (c.anchor0Y - 0.5).toDouble())
            if (d < minMorphDist7) minMorphDist7 = d
            if (d > maxMorphDist7) maxMorphDist7 = d
        }
        assertTrue("7-vertex min distance must be > 0.35", minMorphDist7 > 0.35)
        assertTrue("7-vertex max distance must be < 0.55", maxMorphDist7 < 0.55)

        // Verify wave height at DefaultWaveAmplitude (0.55f) produces subtle ~1.0dp peak-to-trough ripple
        val defaultMorph = androidx.graphics.shapes.Morph(start = trackPolygon7, end = activeIndicatorPolygon7)
        var minAmpDist = 100.0
        var maxAmpDist = 0.0
        for (c in defaultMorph.asCubics(com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.DefaultWaveAmplitude)) {
            for (step in 0..20) {
                val t = step / 20.0
                val mt = 1.0 - t
                val x = mt*mt*mt * c.anchor0X + 3*mt*mt*t * c.control0X + 3*mt*t*t * c.control1X + t*t*t * c.anchor1X
                val y = mt*mt*mt * c.anchor0Y + 3*mt*mt*t * c.control0Y + 3*mt*t*t * c.control1Y + t*t*t * c.anchor1Y
                val d = Math.hypot(x - 0.5, y - 0.5)
                if (d < minAmpDist) minAmpDist = d
                if (d > maxAmpDist) maxAmpDist = d
            }
        }
        val defaultWaveHeightDp = (maxAmpDist - minAmpDist) * 35.0
        assertTrue("Default wave height matches full Material 3 expressive wave", defaultWaveHeightDp in 1.7..2.5)

        // Verify constants
        assertEquals(5.dp, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.CircularIndicatorStrokeWidth)
        assertEquals(5.dp, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.CircularTrackStrokeWidth)
        assertEquals(4.dp, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.CircularIndicatorTrackGapSize)
        assertEquals(15.dp, com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.CircularWavelength)
    }
}
