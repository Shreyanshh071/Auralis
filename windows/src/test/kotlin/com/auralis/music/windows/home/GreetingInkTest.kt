package com.auralis.music.windows.home

import androidx.compose.ui.graphics.*
import com.auralis.music.ui.theme.ArtworkPalette
import kotlin.test.*

class GreetingInkTest {
    @Test fun nameUsesALighterVersionOfEachSongsThemeWithReadableContrast() {
        for (seed in listOf(Color(0xFF74258B), Color(0xFF145D72), Color(0xFF9B273C), Color(0xFF848484))) {
            val tint = Color(0xFF101010).copy(alpha = .16f)
            val colour = greetingThemeColour(ArtworkPalette(seed, seed, seed), tint)
            val background = tint.compositeOver(lerp(Color.Black, seed, .68f))
            assertTrue(colour.luminance() > seed.luminance())
            assertTrue((colour.luminance() + .05f) / (background.luminance() + .05f) >= 4.5f)
            val text = greetingNameText("Good evening", "Shreyanshh", colour)
            assertEquals(colour, text.spanStyles.single().item.color)
            assertEquals("Good evening, ".length, text.spanStyles.single().start)
        }
    }
    @Test fun changingSongsChangesTheNameColour() {
        val purple = Color(0xFF74258B)
        val blue = Color(0xFF145D72)
        assertNotEquals(greetingThemeColour(ArtworkPalette(purple,purple,purple), Color.Black),
            greetingThemeColour(ArtworkPalette(blue,blue,blue), Color.Black))
    }
}
