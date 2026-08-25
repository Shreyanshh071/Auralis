package com.auralis.music

import com.auralis.music.data.parser.LyricsMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatcherTest {

    @Test
    fun `diceCoefficient calculates string token overlap correctly`() {
        // Exact match
        assertEquals(1.0, LyricsMatcher.diceCoefficient("Blinding Lights", "Blinding Lights"), 0.001)

        // Case insensitive and punctuation normalized
        assertEquals(1.0, LyricsMatcher.diceCoefficient("blinding lights", "Blinding Lights!"), 0.001)

        // Partial overlap: 2 words overlap out of (3 + 2) total words -> (2 * 2) / 5 = 0.8
        val partial = LyricsMatcher.diceCoefficient("The Blinding Lights", "Blinding Lights")
        assertEquals(0.8, partial, 0.001)

        // Completely disjoint
        assertEquals(0.0, LyricsMatcher.diceCoefficient("Starboy", "Save Your Tears"), 0.001)
    }

    @Test
    fun `isDurationMatching strictly enforces 4-second tolerance window`() {
        // Exactly same duration
        assertTrue(LyricsMatcher.isDurationMatching(200, 200))

        // Within 4 seconds
        assertTrue(LyricsMatcher.isDurationMatching(200, 203))
        assertTrue(LyricsMatcher.isDurationMatching(204, 200))

        // Exceeds 4 seconds -> rejected
        assertFalse(LyricsMatcher.isDurationMatching(200, 205))
        assertFalse(LyricsMatcher.isDurationMatching(190, 200))

        // Unknown duration (0) -> allowed
        assertTrue(LyricsMatcher.isDurationMatching(0, 200))
    }

    @Test
    fun `isCandidateAcceptable accepts valid track variations and rejects mismatches`() {
        val acceptable = LyricsMatcher.isCandidateAcceptable(
            queryTitle = "Blinding Lights",
            queryArtist = "The Weeknd",
            candidateTitle = "Blinding Lights",
            candidateArtist = "The Weeknd"
        )
        assertTrue(acceptable)

        val rejected = LyricsMatcher.isCandidateAcceptable(
            queryTitle = "Blinding Lights",
            queryArtist = "The Weeknd",
            candidateTitle = "Bad Guy",
            candidateArtist = "Billie Eilish"
        )
        assertFalse(rejected)
    }
}
