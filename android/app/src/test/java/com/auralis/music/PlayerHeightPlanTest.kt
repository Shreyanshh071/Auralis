package com.auralis.music

import com.auralis.music.ui.player.planPlayerHeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player body gives up gaps first, then text, and the artwork only last and only a little. */
class PlayerHeightPlanTest {

    private val idealArt = 900
    private val minimumArt = 450 // 50%

    @Test
    fun roomyScreenKeepsFullArtAndHandsSpareHeightToGaps() {
        val plan = planPlayerHeights(viewport = 1400, idealArt = idealArt, minimumArt = minimumArt, idealGaps = 200, minimumGaps = 80)
        assertEquals(idealArt, plan.artSide)
        assertEquals(1f, plan.gapProgress)
        assertEquals(300, plan.extra)
        assertFalse(plan.short)
    }

    @Test
    fun tighterScreenShrinksGapsBeforeTheArt() {
        val plan = planPlayerHeights(viewport = 1040, idealArt = idealArt, minimumArt = minimumArt, idealGaps = 200, minimumGaps = 80)
        assertEquals(idealArt, plan.artSide)
        assertEquals(0.5f, plan.gapProgress)
        assertFalse(plan.short)
    }

    @Test
    fun shortScreenAsksForTighterTextAndOnlyThenTrimsArt() {
        val plan = planPlayerHeights(viewport = 940, idealArt = idealArt, minimumArt = minimumArt, idealGaps = 200, minimumGaps = 80)
        assertTrue(plan.short)
        assertEquals(0f, plan.gapProgress)
        assertEquals(860, plan.artSide)
    }

    @Test
    fun artNeverDropsBelowItsFloor() {
        val plan = planPlayerHeights(viewport = 500, idealArt = idealArt, minimumArt = minimumArt, idealGaps = 200, minimumGaps = 80)
        assertEquals(minimumArt, plan.artSide)
        assertTrue(plan.short)
    }
}
