package com.auralis.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.ktx.toHct
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

    @Test
    fun testWhiteThemedMonochromeArtworkProducesCleanNeutralSchemeWithoutRedTint() {
        val whiteThemeSeed = Color(0xFFE2E8F0)

        val scheme = dynamicColorSchemeFromSeed(
            seedColor = whiteThemeSeed,
            isDark = true,
            isAmoled = false,
            appTheme = "Dark Mode",
            isMonochrome = true
        )

        // Primary accent in dark mode should be bright silver/platinum (> 0.75)
        assertTrue("Monochrome primary should be bright silver accent", scheme.primary.red >= 0.70f)
        assertTrue("Monochrome primary should be bright silver accent", scheme.primary.green >= 0.70f)
        assertTrue("Monochrome primary should be bright silver accent", scheme.primary.blue >= 0.70f)

        // Primary must be neutral (red, green, and blue components roughly equal, NO red/salmon tint)
        val maxDiff = maxOf(
            kotlin.math.abs(scheme.primary.red - scheme.primary.green),
            kotlin.math.abs(scheme.primary.red - scheme.primary.blue),
            kotlin.math.abs(scheme.primary.green - scheme.primary.blue)
        )
        assertTrue("Monochrome primary should have balanced R, G, B without red tint (diff: $maxDiff)", maxDiff < 0.08f)

        // Background should also be neutral charcoal/slate
        val bgMaxDiff = maxOf(
            kotlin.math.abs(scheme.background.red - scheme.background.green),
            kotlin.math.abs(scheme.background.red - scheme.background.blue),
            kotlin.math.abs(scheme.background.green - scheme.background.blue)
        )
        assertTrue("Monochrome background should be neutral without color skew (diff: $bgMaxDiff)", bgMaxDiff < 0.05f)
    }

    @Test
    fun testDiverseHuesProduceDiverseAccentsAcrossSpectrum() {
        val cyan = Color(0xFF00BCD4)
        val green = Color(0xFF4CAF50)
        val purple = Color(0xFF9C27B0)
        val amber = Color(0xFFFF9800)
        val blue = Color(0xFF2196F3)

        val cyanScheme = dynamicColorSchemeFromSeed(seedColor = cyan, isDark = true, isAmoled = false)
        val greenScheme = dynamicColorSchemeFromSeed(seedColor = green, isDark = true, isAmoled = false)
        val purpleScheme = dynamicColorSchemeFromSeed(seedColor = purple, isDark = true, isAmoled = false)
        val amberScheme = dynamicColorSchemeFromSeed(seedColor = amber, isDark = true, isAmoled = false)
        val blueScheme = dynamicColorSchemeFromSeed(seedColor = blue, isDark = true, isAmoled = false)

        // All primaries across distinct spectrum hues must be clearly different
        assertNotEquals("Cyan and Green primaries must differ", cyanScheme.primary, greenScheme.primary)
        assertNotEquals("Green and Purple primaries must differ", greenScheme.primary, purpleScheme.primary)
        assertNotEquals("Purple and Amber primaries must differ", purpleScheme.primary, amberScheme.primary)
        assertNotEquals("Amber and Blue primaries must differ", amberScheme.primary, blueScheme.primary)
        assertNotEquals("Blue and Cyan primaries must differ", blueScheme.primary, cyanScheme.primary)

        // Green scheme primary should have green as dominant or strong component
        assertTrue("Green primary should have strong green channel", greenScheme.primary.green > 0.65f)
        // Cyan scheme primary should have strong green and blue channels
        assertTrue("Cyan primary should have strong blue channel", cyanScheme.primary.blue > 0.65f)
        // Purple scheme primary should have strong red and blue channels
        assertTrue("Purple primary should have strong blue channel", purpleScheme.primary.blue > 0.65f)
        assertTrue("Purple primary should have strong red channel", purpleScheme.primary.red > 0.65f)
    }

    @Test
    fun testInspectColors() {
        val red = Color(0xFFE53935)
        val currentsPurple = Color(0xFF5B4372)
        val radioheadYellow = Color(0xFFE0A040)
        
        for ((name, seed) in listOf("Red" to red, "Currents" to currentsPurple, "RadioheadYellow" to radioheadYellow)) {
            val scheme = dynamicColorSchemeFromSeed(seedColor = seed, isDark = true, isAmoled = false)
            val hctBg = scheme.background.toHct()
            val hctOnBg = scheme.onBackground.toHct()
            val hctPri = scheme.primary.toHct()

            println("SEED: $name (${seed.toArgb().toUInt().toString(16)}) -> " +
                    "BG: ${scheme.background.toArgb().toUInt().toString(16)} (Chroma: ${hctBg.chroma}), " +
                    "onBG: ${scheme.onBackground.toArgb().toUInt().toString(16)} (Chroma: ${hctOnBg.chroma}), " +
                    "PRI: ${scheme.primary.toArgb().toUInt().toString(16)} (Chroma: ${hctPri.chroma})")

            // Background must be sophisticated ambient tint, NOT garish or aggressive
            assertTrue("Background chroma for $name should be <= 4.5 to avoid aggressive mud ($hctBg)", hctBg.chroma <= 4.5)
            // Text must be clean, crisp off-white without heavy color skews (no pink/orange text)
            assertTrue("onBackground chroma for $name should be <= 2.5 for clean readability ($hctOnBg)", hctOnBg.chroma <= 2.5)
            // Primary must remain vibrant
            assertTrue("Primary accent for $name must remain vibrant ($hctPri)", hctPri.chroma >= 20.0)
        }
    }

    @Test
    fun testInspectLightModeColors() {
        val tealCyan = Color(0xFF006A6A)
        val radioheadYellow = Color(0xFFE0A040)
        val lime = Color(0xFF536600)
        
        for ((name, seed) in listOf("TealCyan" to tealCyan, "RadioheadYellow" to radioheadYellow, "Lime" to lime)) {
            val scheme = dynamicColorSchemeFromSeed(seedColor = seed, isDark = false, isAmoled = false)
            val hctBg = scheme.background.toHct()
            val hctOnBg = scheme.onBackground.toHct()
            val hctPri = scheme.primary.toHct()

            println("LIGHT SEED: $name -> " +
                    "BG: ${scheme.background.toArgb().toUInt().toString(16)} (Tone: ${hctBg.tone}), " +
                    "onBG: ${scheme.onBackground.toArgb().toUInt().toString(16)} (Tone: ${hctOnBg.tone}), " +
                    "PRI: ${scheme.primary.toArgb().toUInt().toString(16)} (Tone: ${hctPri.tone})")
            
            // Text must be strictly dark tone <= 18 and chroma <= 2.5
            assertTrue("Light mode onBackground tone for $name should be <= 18 ($hctOnBg)", hctOnBg.tone <= 18.0)
            assertTrue("Light mode onBackground chroma for $name should be <= 2.5 ($hctOnBg)", hctOnBg.chroma <= 2.5)
            // Background must be strictly light tone >= 92
            assertTrue("Light mode background tone for $name should be >= 92 ($hctBg)", hctBg.tone >= 92.0)
            // Text luminance must be dark, background luminance must be light
            assertTrue("Light mode onBackground luminance must be dark (< 0.15)", scheme.onBackground.luminance() < 0.15f)
            assertTrue("Light mode background luminance must be light (> 0.85)", scheme.background.luminance() > 0.85f)
        }
    }

    @Test
    fun testLightModeContrastsAreStrictlyDarkTextOnLightBackground() {
        val seeds = listOf(
            "Cyan" to Color(0xFF00BCD4),
            "Radiohead Yellow" to Color(0xFFE0A040),
            "Lime" to Color(0xFF536600),
            "Red" to Color(0xFFE53935),
            "Currents Purple" to Color(0xFF5B4372),
            "White/Monochrome" to Color(0xFFFFFFFF),
            "Black/Monochrome" to Color(0xFF000000)
        )

        for ((name, seed) in seeds) {
            val isMono = name.contains("Monochrome")
            val scheme = dynamicColorSchemeFromSeed(seedColor = seed, isDark = false, isMonochrome = isMono)

            // 1. Text (onBackground, onSurface) must ALWAYS be dark and legible
            assertTrue(
                "Light mode onBackground for $name must have luminance < 0.15 (actual: ${scheme.onBackground.luminance()})",
                scheme.onBackground.luminance() < 0.15f
            )
            assertTrue(
                "Light mode onSurface for $name must have luminance < 0.15 (actual: ${scheme.onSurface.luminance()})",
                scheme.onSurface.luminance() < 0.15f
            )

            // 2. Background and surface must ALWAYS be light
            assertTrue(
                "Light mode background for $name must have luminance > 0.80 (actual: ${scheme.background.luminance()})",
                scheme.background.luminance() > 0.80f
            )
            assertTrue(
                "Light mode surface for $name must have luminance > 0.80 (actual: ${scheme.surface.luminance()})",
                scheme.surface.luminance() > 0.80f
            )

            // 3. Contrast ratio between background and text must be >= 7:1 (WCAG AAA)
            val bgLum = scheme.background.luminance()
            val textLum = scheme.onBackground.luminance()
            val contrast = (bgLum + 0.05f) / (textLum + 0.05f)
            assertTrue(
                "Light mode text contrast for $name must exceed 7.0:1 (actual: $contrast:1)",
                contrast >= 7.0f
            )
        }
    }

    @Test
    fun testCuratedColorSchemeMatchesSelectedPalette() {
        val skyBlue = com.auralis.music.ui.theme.getCuratedColorScheme(
            paletteId = "Sky Blue",
            isDark = true,
            isAmoled = false,
            appTheme = "Dark Mode"
        )
        assertEquals(
            "Curated scheme for Sky Blue must use Sky Blue primary color",
            Color(0xFF4FC3F7),
            skyBlue.primary
        )

        val rubyRed = com.auralis.music.ui.theme.getCuratedColorScheme(
            paletteId = "Ruby Red",
            isDark = true,
            isAmoled = false,
            appTheme = "Dark Mode"
        )
        assertEquals(
            "Curated scheme for Ruby Red must use Ruby Red primary color",
            Color(0xFFEF5350),
            rubyRed.primary
        )
    }
}
