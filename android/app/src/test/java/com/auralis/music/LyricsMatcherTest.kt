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
        assertTrue(LyricsMatcher.isDurationMatching(200, 200, 4))

        // Within 4 seconds
        assertTrue(LyricsMatcher.isDurationMatching(200, 203, 4))
        assertTrue(LyricsMatcher.isDurationMatching(204, 200, 4))

        // Exceeds 4 seconds -> rejected
        assertFalse(LyricsMatcher.isDurationMatching(200, 205, 4))
        assertFalse(LyricsMatcher.isDurationMatching(190, 200, 4))

        // Unknown duration (0) -> allowed
        assertTrue(LyricsMatcher.isDurationMatching(0, 200, 4))
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

    @Test
    fun `orchestral version never takes the vocal original's lyrics`() {
        // Same artist, same core title, near enough in length: only the version differs.
        val vocal = LyricsMatcher.calculateConfidence(
            queryTitle = "Let Down - Orchestral Version",
            queryArtist = "Some Artist",
            candidateTitle = "Let Down",
            candidateArtist = "Some Artist",
            queryDurationSec = 210,
            candidateDurationSec = 205
        )
        assertEquals(0, vocal)

        val sameVersion = LyricsMatcher.calculateConfidence(
            queryTitle = "Let Down - Orchestral Version",
            queryArtist = "Some Artist",
            candidateTitle = "Let Down (Orchestral Version)",
            candidateArtist = "Some Artist",
            queryDurationSec = 210,
            candidateDurationSec = 209
        )
        assertTrue(sameVersion >= 50)
    }

    @Test
    fun `karaoke and instrumental versions reject the sung song`() {
        for (tag in listOf("Karaoke", "Instrumental")) {
            val confidence = LyricsMatcher.calculateConfidence(
                queryTitle = "Blinding Lights ($tag)",
                queryArtist = "The Weeknd",
                candidateTitle = "Blinding Lights",
                candidateArtist = "The Weeknd",
                queryDurationSec = 200,
                candidateDurationSec = 200
            )
            assertEquals(tag, 0, confidence)
        }
    }
}
