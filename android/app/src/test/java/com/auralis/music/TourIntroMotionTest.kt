package com.auralis.music

import com.auralis.music.ui.onboarding.TourIntroMotion
import org.junit.Assert.*
import org.junit.Test

class TourIntroMotionTest {
    @Test fun logoFinishesBeforeContourLinesStart() {
        assertEquals(0f, TourIntroMotion.logoProgress(0f), 0f)
        assertEquals(1f, TourIntroMotion.logoProgress(850f), 0f)
        assertEquals(0f, TourIntroMotion.linesProgress(850f), 0f)
        assertTrue(TourIntroMotion.linesProgress(1800f) > 0f)
        assertEquals(1f, TourIntroMotion.linesProgress(3500f), 0f)
    }

    @Test fun spinContinuesWithoutSnappingAndSettlesToSlowRotation() {
        fun speedAt(ms: Float) = (TourIntroMotion.rotation(ms + 10f) - TourIntroMotion.rotation(ms)) * 100f
        assertEquals(720f, speedAt(400f), .1f)
        assertTrue(speedAt(850f) < speedAt(800f))
        assertTrue(speedAt(1200f) > speedAt(2200f))
        assertEquals(3f, speedAt(8000f), .1f)
        for (ms in 0..10_000 step 10) {
            val delta = TourIntroMotion.rotation(ms + 10f) - TourIntroMotion.rotation(ms.toFloat())
            assertTrue("Rotation reversed at $ms", delta > 0f)
            assertTrue("Rotation jumped at $ms", delta <= 7.21f)
        }
    }

    @Test fun entryFadeRemainsWithinBounds() {
        assertEquals(0f, TourIntroMotion.contentAlpha(0f), 0f)
        assertEquals(.5f, TourIntroMotion.contentAlpha(210f), .001f)
        assertEquals(1f, TourIntroMotion.contentAlpha(850f), 0f)
    }
}
