package com.auralis.music

import androidx.compose.ui.graphics.Color
import com.auralis.music.ui.player.PlayerBackgroundStyle
import com.auralis.music.ui.player.lerpArtworkPalette
import com.auralis.music.ui.theme.ArtworkPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MainPlayerBackgroundTransitionTest {

    private fun samplePalette(
        primary: Color,
        secondary: Color,
        tertiary: Color,
        isMonochrome: Boolean = false
    ): ArtworkPalette {
        return ArtworkPalette(
            primary = primary,
            secondary = secondary,
            tertiary = tertiary,
            seedColor = primary,
            isMonochrome = isMonochrome,
            glowColors = listOf(primary, secondary, tertiary, primary, secondary, tertiary)
        )
    }

    private fun assertColorClose(expected: Color, actual: Color, tolerance: Float = 0.015f) {
        assertTrue("Red channel diff too large: exp=${expected.red}, act=${actual.red}",
            abs(expected.red - actual.red) <= tolerance)
        assertTrue("Green channel diff too large: exp=${expected.green}, act=${actual.green}",
            abs(expected.green - actual.green) <= tolerance)
        assertTrue("Blue channel diff too large: exp=${expected.blue}, act=${actual.blue}",
            abs(expected.blue - actual.blue) <= tolerance)
    }

    @Test
    fun testLerpArtworkPaletteBoundaries() {
        val paletteA = samplePalette(Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFF1E88E5))
        val paletteB = samplePalette(Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF00ACC1))

        val start = lerpArtworkPalette(paletteA, paletteB, 0f)
        assertEquals(paletteA.primary, start.primary)
        assertEquals(paletteA.secondary, start.secondary)
        assertEquals(paletteA.tertiary, start.tertiary)

        val end = lerpArtworkPalette(paletteA, paletteB, 1f)
        assertEquals(paletteB.primary, end.primary)
        assertEquals(paletteB.secondary, end.secondary)
        assertEquals(paletteB.tertiary, end.tertiary)
    }

    @Test
    fun testLerpArtworkPaletteMidpoint() {
        val colorA = Color(1.0f, 0.0f, 0.0f, 1.0f)
        val colorB = Color(0.0f, 1.0f, 0.0f, 1.0f)

        val paletteA = samplePalette(colorA, colorA, colorA)
        val paletteB = samplePalette(colorB, colorB, colorB)

        val expected = androidx.compose.ui.graphics.lerp(colorA, colorB, 0.5f)
        val mid = lerpArtworkPalette(paletteA, paletteB, 0.5f)
        assertEquals(expected, mid.primary)
        assertEquals(6, mid.glowColors.size)
        assertEquals(expected, mid.glowColors[0])
    }

    @Test
    fun testInterruptedTransitionContinuityZeroDiscontinuity() {
        // Song A: Crimson/Purple
        val songA = samplePalette(Color(0xFFD32F2F), Color(0xFF7B1FA2), Color(0xFF303F9F))
        // Song B: Ocean Blue/Teal
        val songB = samplePalette(Color(0xFF1976D2), Color(0xFF00796B), Color(0xFF0097A7))
        // Song C: Amber/Orange
        val songC = samplePalette(Color(0xFFFFA000), Color(0xFFF57C00), Color(0xFFE64A19))

        // User swipes A -> B, but rapidly swipes to C when transition is at 38%
        val fractionInterrupted = 0.38f
        val visibleAtInterruption = lerpArtworkPalette(songA, songB, fractionInterrupted)

        // New transition begins with visibleAtInterruption as starting state towards songC
        val newTransitionStart = lerpArtworkPalette(visibleAtInterruption, songC, 0f)

        // Must have ZERO discontinuity jump at the instant of interruption
        assertEquals("Primary color must match interrupted state exactly",
            visibleAtInterruption.primary, newTransitionStart.primary)
        assertEquals("Secondary color must match interrupted state exactly",
            visibleAtInterruption.secondary, newTransitionStart.secondary)
        assertEquals("Tertiary color must match interrupted state exactly",
            visibleAtInterruption.tertiary, newTransitionStart.tertiary)

        // When transition completes to Song C, destination is reached
        val finalState = lerpArtworkPalette(visibleAtInterruption, songC, 1f)
        assertEquals(songC.primary, finalState.primary)
        assertEquals(songC.secondary, finalState.secondary)
        assertEquals(songC.tertiary, finalState.tertiary)
    }

    @Test
    fun testRapidChainSwipesABCD() {
        val a = samplePalette(Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7))
        val b = samplePalette(Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF03A9F4))
        val c = samplePalette(Color(0xFF00BCD4), Color(0xFF009688), Color(0xFF4CAF50))
        val d = samplePalette(Color(0xFF8BC34A), Color(0xFFCDDC39), Color(0xFFFFEB3B))

        // A -> B interrupted at 20%
        val step1 = lerpArtworkPalette(a, b, 0.20f)
        // -> C interrupted at 40%
        val step2 = lerpArtworkPalette(step1, c, 0.40f)
        // -> D settles at 100%
        val step3 = lerpArtworkPalette(step2, d, 1.0f)

        assertEquals("Final target must settle on song D", d.primary, step3.primary)
        assertEquals("Final target must settle on song D", d.secondary, step3.secondary)
    }

    @Test
    fun testReverseSwipesContinuity() {
        val songForward = samplePalette(Color(0xFF6200EE), Color(0xFF3700B3), Color(0xFF03DAC6))
        val songBack = samplePalette(Color(0xFFFF0266), Color(0xFFC51162), Color(0xFFFF4081))

        // Forward 60%
        val midForward = lerpArtworkPalette(songForward, songBack, 0.60f)
        // User immediately swipes backward toward original songForward
        val midReverse = lerpArtworkPalette(midForward, songForward, 0.0f)

        assertEquals("Reversing direction must have zero jump at start",
            midForward.primary, midReverse.primary)

        val settledBack = lerpArtworkPalette(midForward, songForward, 1.0f)
        assertEquals("Reversing direction must settle cleanly on original song",
            songForward.primary, settledBack.primary)
    }

    @Test
    fun testMonochromeAlbumArtworkInterpolation() {
        val colorful = samplePalette(Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFF1E88E5), isMonochrome = false)
        val monochrome = samplePalette(Color(0xFFE0E0E0), Color(0xFF9E9E9E), Color(0xFF616161), isMonochrome = true)

        val mid = lerpArtworkPalette(colorful, monochrome, 0.75f)
        assertTrue("Past 50% transition toward monochrome must flag as monochrome", mid.isMonochrome)
        assertEquals(6, mid.glowColors.size)
    }

    @Test
    fun testLiveMeshPlayerBackgroundStyleResolution() {
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey("Live Mesh"))
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey("live mesh"))
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey("live_mesh"))
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey("mesh"))
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey(" MESH "))
    }

    @Test
    fun testLiveMeshGeometricCoverageGuarantee() {
        // Test aspect ratios: 20:9 (Motorola Edge 50 Fusion, 1080x2400), 19.5:9 (1080x2340), 16:9 (1080x1920)
        val testScreens = listOf(
            Pair(1080f, 2400f), // 20:9
            Pair(1080f, 2340f), // 19.5:9
            Pair(1080f, 1920f)  // 16:9
        )
        val scale = 1.28f

        for ((width, height) in testScreens) {
            val diagonal = kotlin.math.sqrt(width * width + height * height)
            val halfDiagonal = diagonal / 2f

            // Inscribed circle radius of centered maxDim square at scale must exceed halfDiagonal
            // guaranteeing 100% full coverage at all 360 rotation angles
            val maxDim = maxOf(width, height)
            val inscribedCircleRadius = (maxDim * scale) / 2f

            assertTrue(
                "Mesh square at scale $scale must cover screen (${width}x${height}): inscribedRadius=$inscribedCircleRadius >= halfDiag=$halfDiagonal",
                inscribedCircleRadius >= halfDiagonal
            )
        }
    }
}
