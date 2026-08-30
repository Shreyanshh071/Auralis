package com.auralis.music

import com.auralis.music.data.parser.IndicScriptNormalizer
import com.auralis.music.data.parser.LyricsMatcher
import org.junit.Assert.*
import org.junit.Test

class LyricsMatcherComprehensiveTest {

    @Test
    fun `IndicScriptNormalizer transliterates Bhojpuri and Hindi Devanagari to Latin accurately`() {
        val bhojpuriDevanagari = "राजा जी के दिलवा"
        val transliterated = IndicScriptNormalizer.transliterateToPhoneticLatin(bhojpuriDevanagari)
        assertEquals("Raja Ji Ke Dilava", transliterated)

        val hindiDevanagari = "केसरिया"
        val kesariyaLatin = IndicScriptNormalizer.transliterateToPhoneticLatin(hindiDevanagari)
        assertEquals("Kesariya", kesariyaLatin)

        val artistDevanagari = "पवन सिंह"
        val artistLatin = IndicScriptNormalizer.transliterateToPhoneticLatin(artistDevanagari)
        assertEquals("Pavn Sinh", artistLatin)
    }

    @Test
    fun `isTitleMatching matches Bhojpuri cross-script metadata`() {
        val matchesBhojpuri = LyricsMatcher.isTitleMatching(
            queryTitle = "Raja Ji Ke Dilwa",
            candTitle = "राजा जी के दिलवा"
        )
        assertTrue("Devanagari and Romanized Bhojpuri titles should match", matchesBhojpuri)

        val matchesHindi = LyricsMatcher.isTitleMatching(
            queryTitle = "Kesariya",
            candTitle = "केसरिया"
        )
        assertTrue("Devanagari and Romanized Hindi titles should match", matchesHindi)
    }

    @Test
    fun `isArtistMatching matches multi-artist collaborations across scripts`() {
        assertTrue(
            LyricsMatcher.isArtistMatching("Pawan Singh & Shilpi Raj", "Pawan Singh")
        )
        assertTrue(
            LyricsMatcher.isArtistMatching("पवन सिंह", "Pawan Singh")
        )
        assertTrue(
            LyricsMatcher.isArtistMatching("Arijit Singh, Pritam", "Arijit Singh")
        )
    }

    @Test
    fun `calculateConfidence computes high score for exact and regional matches`() {
        // Exact English match
        val englishScore = LyricsMatcher.calculateConfidence(
            queryTitle = "Blinding Lights",
            queryArtist = "The Weeknd",
            candidateTitle = "Blinding Lights",
            candidateArtist = "The Weeknd",
            queryDurationSec = 200,
            candidateDurationSec = 200
        )
        assertTrue("Exact match should be >= 90%, was $englishScore%", englishScore >= 90)

        // Bhojpuri cross-script match
        val bhojpuriScore = LyricsMatcher.calculateConfidence(
            queryTitle = "Raja Ji Ke Dilwa",
            queryArtist = "Pawan Singh",
            candidateTitle = "राजा जी के दिलवा",
            candidateArtist = "पवन सिंह",
            queryDurationSec = 180,
            candidateDurationSec = 182
        )
        assertTrue("Bhojpuri cross-script should have high confidence (>= 75%), was $bhojpuriScore%", bhojpuriScore >= 75)
    }

    @Test
    fun `calculateConfidence penalizes mismatched versions`() {
        // Query wants Remix, candidate is Original Studio
        val remixVsStudioScore = LyricsMatcher.calculateConfidence(
            queryTitle = "Levitating (Remix)",
            queryArtist = "Dua Lipa",
            candidateTitle = "Levitating",
            candidateArtist = "Dua Lipa",
            queryDurationSec = 203,
            candidateDurationSec = 203
        )

        // Query wants Remix, candidate is Remix
        val remixVsRemixScore = LyricsMatcher.calculateConfidence(
            queryTitle = "Levitating (Remix)",
            queryArtist = "Dua Lipa",
            candidateTitle = "Levitating (Remix)",
            candidateArtist = "Dua Lipa",
            queryDurationSec = 203,
            candidateDurationSec = 203
        )

        assertTrue(
            "Matching remix version should score higher than studio version",
            remixVsRemixScore > remixVsStudioScore
        )
    }

    @Test
    fun `calculateConfidence severely penalizes duration mismatch greater than 25s`() {
        val durationMismatchScore = LyricsMatcher.calculateConfidence(
            queryTitle = "Starboy",
            queryArtist = "The Weeknd",
            candidateTitle = "Starboy",
            candidateArtist = "The Weeknd",
            queryDurationSec = 230,
            candidateDurationSec = 120 // 110s difference (wrong track or preview)
        )
        assertTrue("Duration difference > 25s should reduce confidence", durationMismatchScore < 85)
    }

    @Test
    fun `isCandidateAcceptable accepts valid regional variations and rejects unrelated songs`() {
        // Acceptable Bhojpuri
        val accepted = LyricsMatcher.isCandidateAcceptable(
            queryTitle = "Lollipop Lagelu",
            queryArtist = "Pawan Singh",
            candidateTitle = "लॉलीपॉप लागेलू",
            candidateArtist = "Pawan Singh",
            queryDurationSec = 250,
            candidateDurationSec = 252
        )
        assertTrue("Should accept matching Bhojpuri song", accepted)

        // Rejected different song
        val rejected = LyricsMatcher.isCandidateAcceptable(
            queryTitle = "Kesariya",
            queryArtist = "Arijit Singh",
            candidateTitle = "Apna Bana Le",
            candidateArtist = "Arijit Singh",
            queryDurationSec = 268,
            candidateDurationSec = 260
        )
        assertFalse("Should reject completely different song by same artist", rejected)
    }
}
