package com.auralis.music

import com.auralis.music.ui.lyrics.LyricsViewportBlurProfile as Profile
import org.junit.Assert.*
import org.junit.Test

class StationaryLyricsBlurTest {
    @Test fun `multi line sung phrase expands the sharp band`() {
        for (y in listOf(180f,350f,520f)) assertEquals(0f,Profile.amount(y,1000f,.35f,190f),0f)
        assertTrue(Profile.amount(800f,1000f,.35f,190f)>0f)
        assertEquals(0f,Profile.amount(800f,1000f,.35f,600f),0f)
    }
    @Test fun `focus band stays sharp and blur increases at fixed screen positions`() {
        fun at(y: Float) = Profile.amount(y, 1000f, .35f, 48f)
        assertEquals(0f, at(350f), 0f)
        assertEquals(0f, at(310f), 0f)
        assertEquals(0f, at(390f), 0f)
        assertTrue(at(500f) > at(420f))
        assertTrue(at(650f) > at(500f))
        assertEquals(at(200f), at(500f), .0001f)
        assertEquals(1f, at(0f), 0f)
        assertEquals(1f, at(1000f), 0f)
    }

    @Test fun `scrolling text changes its blur without moving the viewport profile`() {
        val fixedScreenPositions = listOf(100f, 250f, 350f, 500f, 800f)
        val before = fixedScreenPositions.map { Profile.amount(it, 1000f, .35f, 48f) }
        // A lyric moves upward through the stationary field and enters the clear focus band.
        val enteringLine = listOf(650f, 550f, 450f, 350f).map { Profile.amount(it, 1000f, .35f, 48f) }
        assertTrue(enteringLine.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(0f, enteringLine.last(), 0f)
        val after = fixedScreenPositions.map { Profile.amount(it, 1000f, .35f, 48f) }
        assertEquals(before, after)
    }

    @Test fun `blur masks preserve opacity and only blend neighboring levels`() {
        for (step in 0..1000) {
            val amount = step / 1000f
            val weights = (0 until Profile.LEVELS).map { Profile.weight(amount, it) }
            assertEquals(1f, weights.sum(), .00001f)
            assertTrue(weights.count { it > 0f } <= 2)
        }
        assertEquals(1f, Profile.weight(0f, 0), 0f)
        assertEquals(0f, Profile.weight(1f, 0), 0f)
        assertEquals(1f, Profile.weight(1f, Profile.LEVELS - 1), 0f)
    }

    @Test fun `invalid viewport is sharp and focus follows the chosen reading position`() {
        assertEquals(0f, Profile.amount(20f, 0f, .35f, 48f), 0f)
        assertEquals(0f, Profile.amount(Float.NaN, 1000f, .35f, 48f), 0f)
        assertEquals(0f, Profile.amount(600f, 1000f, .6f, 48f), 0f)
        assertTrue(Profile.amount(600f, 1000f, .35f, 48f) > 0f)
    }
}
