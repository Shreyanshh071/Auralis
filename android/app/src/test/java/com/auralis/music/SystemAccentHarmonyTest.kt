package com.auralis.music

import com.auralis.music.ui.theme.accentClashesWithNeutral
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAccentHarmonyTest {
    @Test fun `Expressive blue buttons on a brown page count as a clash`() {
        assertTrue(accentClashesWithNeutral(accentHue = 280.0, neutralHue = 60.0, neutralChroma = 8.0))
    }

    @Test fun `a normal wallpaper palette is left alone`() {
        assertFalse(accentClashesWithNeutral(accentHue = 70.0, neutralHue = 60.0, neutralChroma = 8.0))
    }

    @Test fun `hues either side of red are close together`() {
        assertFalse(accentClashesWithNeutral(accentHue = 350.0, neutralHue = 10.0, neutralChroma = 8.0))
    }

    @Test fun `grey surfaces have no colour to clash with`() {
        assertFalse(accentClashesWithNeutral(accentHue = 280.0, neutralHue = 60.0, neutralChroma = 1.0))
    }
}
