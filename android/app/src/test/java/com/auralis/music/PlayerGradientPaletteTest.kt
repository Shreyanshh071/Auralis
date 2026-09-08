package com.auralis.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.auralis.music.ui.player.PlayerGradientPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGradientPaletteTest {

    @Test
    fun testColorToHsvAccuracy() {
        // Red
        val hsvRed = FloatArray(3)
        PlayerGradientPalette.colorToHsv(Color(1f, 0f, 0f), hsvRed)
        assertEquals(0f, hsvRed[0], 0.01f)
        assertEquals(1f, hsvRed[1], 0.01f)
        assertEquals(1f, hsvRed[2], 0.01f)

        // Green
        val hsvGreen = FloatArray(3)
        PlayerGradientPalette.colorToHsv(Color(0f, 1f, 0f), hsvGreen)
        assertEquals(120f, hsvGreen[0], 0.01f)
        assertEquals(1f, hsvGreen[1], 0.01f)
        assertEquals(1f, hsvGreen[2], 0.01f)

        // Blue
        val hsvBlue = FloatArray(3)
        PlayerGradientPalette.colorToHsv(Color(0f, 0f, 1f), hsvBlue)
        assertEquals(240f, hsvBlue[0], 0.01f)
        assertEquals(1f, hsvBlue[1], 0.01f)
        assertEquals(1f, hsvBlue[2], 0.01f)
    }

    @Test
    fun testVibrantArtworkGradientGeneration() {
        // Vibrant Purple artwork (Olivia Rodrigo - SOUR style)
        val purplePrimary = Color(168 / 255f, 85 / 255f, 247 / 255f)
        val purpleSecondary = Color(140 / 255f, 60 / 255f, 220 / 255f)

        val stops = PlayerGradientPalette.create(
            primary = purplePrimary,
            secondary = purpleSecondary,
            isMonochrome = false
        )

        // Full player stops:
        // Top should be vibrant and alive
        val topHsv = FloatArray(3)
        PlayerGradientPalette.colorToHsv(stops.topVibrant, topHsv)
        assertTrue("Top saturation should be boosted", topHsv[1] >= 0.55f)
        assertTrue("Top brightness should be high enough to be clearly colorful", topHsv[2] in 0.68f..0.88f)

        // Bottom should be atmospheric obsidian (deep base)
        val botHsv = FloatArray(3)
        PlayerGradientPalette.colorToHsv(stops.bottomObsidian, botHsv)
        assertTrue("Bottom brightness should be deep dark base for controls contrast", botHsv[2] in 0.05f..0.12f)

        // Mini player stops:
        // Left stop should be comfortable for text contrast
        val miniLeftHsv = FloatArray(3)
        PlayerGradientPalette.colorToHsv(stops.miniLeft, miniLeftHsv)
        assertTrue("Mini left brightness should ensure white text is readable", miniLeftHsv[2] in 0.32f..0.45f)

        // Right stop should be glowing and luminous
        val miniRightHsv = FloatArray(3)
        PlayerGradientPalette.colorToHsv(stops.miniRight, miniRightHsv)
        assertTrue("Mini right should be vivid and glowing", miniRightHsv[2] in 0.52f..0.72f)
        assertTrue("Mini right should be more luminous than mini left", miniRightHsv[2] > miniLeftHsv[2])
    }

    @Test
    fun testDistinctSecondaryHueIncorporation() {
        // Track with distinct two-tone artwork: Purple primary + Cyan secondary
        val purplePrimary = Color(168 / 255f, 85 / 255f, 247 / 255f)
        val cyanSecondary = Color(14 / 255f, 165 / 255f, 233 / 255f)

        val stops = PlayerGradientPalette.create(
            primary = purplePrimary,
            secondary = cyanSecondary,
            isMonochrome = false
        )

        val topHsv = FloatArray(3)
        PlayerGradientPalette.colorToHsv(stops.topVibrant, topHsv)

        val midHsv = FloatArray(3)
        PlayerGradientPalette.colorToHsv(stops.midHarmonic, midHsv)

        // Mid-tone should carry the distinct secondary hue (cyan ~198°)
        val cyanHsv = FloatArray(3)
        PlayerGradientPalette.colorToHsv(cyanSecondary, cyanHsv)
        assertEquals("Mid-harmonic should reflect distinct secondary hue", cyanHsv[0], midHsv[0], 2.0f)

        // Mini player right should reflect glowing secondary hue
        val miniRightHsv = FloatArray(3)
        PlayerGradientPalette.colorToHsv(stops.miniRight, miniRightHsv)
        assertEquals("Mini right should reflect glowing secondary hue", cyanHsv[0], miniRightHsv[0], 2.0f)
    }

    @Test
    fun testMonochromeArtworkGradientGeneration() {
        // Grayscale album art (e.g. NEFFEX Fight Back)
        val grayPrimary = Color(0xFFE0E0E0)
        val graySecondary = Color(0xFF9E9E9E)

        val stops = PlayerGradientPalette.create(
            primary = grayPrimary,
            secondary = graySecondary,
            isMonochrome = true
        )

        // Stops should be neutral (R ≈ G ≈ B)
        assertEquals(Color(0xFF555964), stops.topVibrant)
        assertEquals(Color(0xFF2E3139), stops.midHarmonic)
        assertEquals(Color(0xFF0C0D0F), stops.bottomObsidian)
        assertEquals(Color(0xFF2D3037), stops.miniLeft)
        assertEquals(Color(0xFF383C45), stops.miniCenter)
        assertEquals(Color(0xFF4C525E), stops.miniRight)
        assertEquals(Color(0xFF7B8394), stops.glowAccent)
    }
}
