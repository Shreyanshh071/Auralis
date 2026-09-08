package com.auralis.music

import androidx.compose.ui.graphics.Color
import com.auralis.music.ui.theme.dynamicColorSchemeFromSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicThemeBackgroundTest {

    @Test
    fun testSongArtworkTintsBackgroundAndSurfaces() {
        // Terracotta / warm seed from "Love Me Not" by Ravyn Lenae (Photo 1 & 2)
        val loveMeNotSeed = Color(0xFFA35C4A)

        val scheme = dynamicColorSchemeFromSeed(
            seedColor = loveMeNotSeed,
            isDark = true,
            isAmoled = false,
            appTheme = "Dark Mode"
        )

        // Background and surface must NOT be crushed to static neutral grey #0E0E10 / #161619
        assertNotEquals(
            "Background should be dynamically tinted by artwork, not static charcoal #0E0E10",
            Color(0xFF0E0E10),
            scheme.background
        )
        assertNotEquals(
            "Surface should be dynamically tinted by artwork, not static #161619",
            Color(0xFF161619),
            scheme.surface
        )

        // Terracotta has reddish/warm hue (R > B)
        assertTrue(
            "Terracotta background should reflect warm hue (red component > blue component)",
            scheme.background.red > scheme.background.blue
        )
    }

    @Test
    fun testAmoledModePreservesPureBlack() {
        val seed = Color(0xFFA35C4A)

        val scheme = dynamicColorSchemeFromSeed(
            seedColor = seed,
            isDark = true,
            isAmoled = true,
            appTheme = "Pure AMOLED Black"
        )

        assertEquals("AMOLED background must be pure black", Color.Black, scheme.background)
        assertEquals("AMOLED surface must be pure black", Color.Black, scheme.surface)
    }

    @Test
    fun testMidnightVelvetModePreservesMidnightDark() {
        val seed = Color(0xFFA35C4A)

        val scheme = dynamicColorSchemeFromSeed(
            seedColor = seed,
            isDark = true,
            isAmoled = false,
            appTheme = "Midnight Velvet Dark"
        )

        assertEquals("Midnight Velvet background must be #0A0A0C", Color(0xFF0A0A0C), scheme.background)
        assertEquals("Midnight Velvet surface must be #121215", Color(0xFF121215), scheme.surface)
    }

    @Test
    fun testCuratedPalettesProduceDistinctBackgrounds() {
        val lime = Color(0xFFD4E157)
        val crimson = Color(0xFFE53935)
        val rose = Color(0xFFF06292)
        val purple = Color(0xFFBA68C8)

        val limeScheme = dynamicColorSchemeFromSeed(seedColor = lime, isDark = true, isAmoled = false, appTheme = "Dark Mode")
        val crimsonScheme = dynamicColorSchemeFromSeed(seedColor = crimson, isDark = true, isAmoled = false, appTheme = "Dark Mode")
        val roseScheme = dynamicColorSchemeFromSeed(seedColor = rose, isDark = true, isAmoled = false, appTheme = "Dark Mode")
        val purpleScheme = dynamicColorSchemeFromSeed(seedColor = purple, isDark = true, isAmoled = false, appTheme = "Dark Mode")

        // Top colors must produce distinct, uniquely tinted backgrounds
        assertNotEquals("Lime and Crimson backgrounds should be different", limeScheme.background, crimsonScheme.background)
        assertNotEquals("Crimson and Purple backgrounds should be different", crimsonScheme.background, purpleScheme.background)
        assertNotEquals("Rose and Purple backgrounds should be different", roseScheme.background, purpleScheme.background)

        // None of them should be crushed to static neutral grey #121418
        assertNotEquals(Color(0xFF121418), limeScheme.background)
        assertNotEquals(Color(0xFF121418), crimsonScheme.background)
        assertNotEquals(Color(0xFF121418), roseScheme.background)
        assertNotEquals(Color(0xFF121418), purpleScheme.background)
    }
}
