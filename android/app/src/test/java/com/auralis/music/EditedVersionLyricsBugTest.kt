package com.auralis.music

import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LyricsMatcher
import org.junit.Assert.*
import org.junit.Test

class EditedVersionLyricsBugTest {

    @Test
    fun testCoreTitleExtraction() {
        val qTitle = "Let Down - Orchestral Version"
        val cTitle = "Let You (Orchestral Version)"

        val qBare = TitleCleaner.extractBareSongTitle(qTitle)
        val cBare = TitleCleaner.extractBareSongTitle(cTitle)
        assertEquals("Let Down", qBare)
        assertEquals("Let You", cBare)
    }

    @Test
    fun testTitleMatchingRejectsDifferentSongsWithSameVersion() {
        // "Let Down - Orchestral Version" vs "Let You (Orchestral Version)"
        // Must NOT match!
        val matches = LyricsMatcher.isTitleMatching(
            "Let Down - Orchestral Version",
            "Let You (Orchestral Version)"
        )
        assertFalse("Different songs with same version tag must not match", matches)
    }

    @Test
    fun testTitleMatchingRejects2WordSongsWithDifferentWords() {
        assertFalse(LyricsMatcher.isTitleMatching("Let Down", "Let You"))
        assertFalse(LyricsMatcher.isTitleMatching("Bad Guy", "Bad Boy"))
        assertFalse(LyricsMatcher.isTitleMatching("Stay Away", "Stay Here"))
        assertFalse(LyricsMatcher.isTitleMatching("Don't Stop", "Don't Go"))
    }

    @Test
    fun testConfidenceRejectsCompletelyDifferentArtists() {
        val conf = LyricsMatcher.calculateConfidence(
            queryTitle = "Let Down - Orchestral Version",
            queryArtist = "Alessandro Veloz",
            candidateTitle = "Let You (Orchestral Version)",
            candidateArtist = "Cheryl",
            queryDurationSec = 176L,
            candidateDurationSec = 176L
        )
        assertEquals("Confidence must be 0 for completely mismatched song and artist", 0, conf)
    }

    @Test
    fun testConfidenceAcceptsMatchingSongAndArtist() {
        val conf = LyricsMatcher.calculateConfidence(
            queryTitle = "Let Down",
            queryArtist = "Radiohead",
            candidateTitle = "Let Down",
            candidateArtist = "Radiohead",
            queryDurationSec = 299L,
            candidateDurationSec = 299L
        )
        assertTrue("Matching song and artist should have high confidence", conf >= 85)
    }

    @Test
    fun testLiveLyricsClientRejectsWrongLyricsForLetDownOrchestral() = kotlinx.coroutines.runBlocking {
        val client = com.auralis.music.data.network.LyricsClient()
        val result = client.getLyrics(
            title = "Let Down - Orchestral Version",
            artist = "Alessandro Veloz",
            durationSec = 176L,
            videoId = "atvXFUzaOK0"
        )
        println("getLyrics result: $result")
        // It must NOT match "Let You" by Cheryl or any other unrelated song!
        if (result != null) {
            val text = result.lines.joinToString(" ") { it.text }
            assertFalse(
                "Must not show lyrics from Cheryl's 'Let You'!",
                text.contains("loyalty", ignoreCase = true) || text.contains("took on you", ignoreCase = true)
            )
        }
    }
}

