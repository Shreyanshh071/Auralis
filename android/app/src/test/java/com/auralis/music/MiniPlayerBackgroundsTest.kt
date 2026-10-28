package com.auralis.music

import com.auralis.music.ui.player.MiniPlayerBackgrounds
import com.auralis.music.ui.player.PlayerBackgroundStyle
import org.junit.Assert.*
import org.junit.Test

class MiniPlayerBackgroundsTest {
    @Test fun `every chosen background is independent of the global glass theme`() {
        for (key in MiniPlayerBackgrounds.options + "Apple Music") {
            val settings = com.auralis.music.domain.model.AppearanceSettings(miniPlayerBackgroundStyle = key)
            val expected = when {
                MiniPlayerBackgrounds.isFrosted(key) -> MiniPlayerBackgrounds.Surface.FROSTED
                PlayerBackgroundStyle.fromKey(key) == PlayerBackgroundStyle.APPLE_MUSIC -> MiniPlayerBackgrounds.Surface.LIQUID_GLASS
                else -> MiniPlayerBackgrounds.Surface.ARTWORK
            }
            for (glass in listOf(false, true)) {
                assertEquals("$key with glass=$glass", expected, MiniPlayerBackgrounds.surface(settings.copy(liquidGlass = glass)))
                assertEquals(MiniPlayerBackgrounds.Surface.PURE_BLACK,
                    MiniPlayerBackgrounds.surface(settings.copy(liquidGlass = glass, pureBlackMiniPlayer = true)))
            }
        }
    }
    @Test fun `frosted blur is an additional choice distinct from artwork blur`() {
        assertTrue(MiniPlayerBackgrounds.options.contains("Frosted blur"))
        assertTrue(MiniPlayerBackgrounds.options.contains("Blur"))
        assertFalse(MiniPlayerBackgrounds.isFrosted("Blur"))
        assertEquals(MiniPlayerBackgrounds.options.size, MiniPlayerBackgrounds.options.distinct().size)
    }

    @Test fun `persisted frosted choice has the correct settings label`() {
        assertEquals("Frosted blur", MiniPlayerBackgrounds.label("Frosted blur"))
        assertTrue(MiniPlayerBackgrounds.isFrosted(" frosted blur "))
    }

    @Test fun `existing backgrounds and legacy liquid glass labels still resolve`() {
        PlayerBackgroundStyle.entries.forEach { style ->
            assertEquals(style.displayName, MiniPlayerBackgrounds.label(style.displayName))
        }
        assertEquals("Liquid glass", MiniPlayerBackgrounds.label("Apple Music"))
    }
}
