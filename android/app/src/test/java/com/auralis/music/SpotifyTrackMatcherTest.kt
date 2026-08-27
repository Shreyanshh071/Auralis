package com.auralis.music

import com.auralis.music.data.network.SpotifyTrackMatcher
import com.auralis.music.data.network.TrackVersionType
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import org.junit.Assert.*
import org.junit.Test

class SpotifyTrackMatcherTest {

    private fun createTrack(
        id: String = "test_id",
        title: String,
        artist: String = "Test Artist",
        album: String = "Test Album",
        duration: Long = 200L
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        thumbnail = "https://example.com/art.jpg",
        duration = duration,
        source = TrackSource.YOUTUBE
    )

    // ========================================================================
    // 1. Regression Test 1: stalk ur socials (slowed) vs "slowed"
    // ========================================================================

    @Test
    fun `Regression Test 1 - Candidate titled slowed is strictly REJECTED for stalk ur socials (slowed)`() {
        val target = createTrack(
            id = "sp_123",
            title = "stalk ur socials (slowed)",
            artist = "Artist A",
            duration = 222L // 3:42
        )

        val badCandidate1 = createTrack(
            id = "yt_bad1",
            title = "slowed",
            artist = "Other Artist",
            duration = 171L
        )

        val badCandidate2 = createTrack(
            id = "yt_bad2",
            title = "unhappy (slowed)",
            artist = "Artist A",
            duration = 171L
        )

        val goodCandidate = createTrack(
            id = "yt_good",
            title = "stalk ur socials [slowed + reverb]",
            artist = "Artist A",
            duration = 223L
        )

        val evalBad1 = SpotifyTrackMatcher.evaluateCandidate(target, badCandidate1)
        assertFalse("Candidate 'slowed' must be rejected", evalBad1.isAccepted)
        assertTrue("Confidence must be very low for purely generic title", evalBad1.confidence < 30)

        val evalBad2 = SpotifyTrackMatcher.evaluateCandidate(target, badCandidate2)
        assertFalse("Candidate 'unhappy (slowed)' must be rejected due to title mismatch", evalBad2.isAccepted)

        val evalGood = SpotifyTrackMatcher.evaluateCandidate(target, goodCandidate)
        assertTrue("Candidate 'stalk ur socials [slowed + reverb]' must be accepted", evalGood.isAccepted)
        assertTrue("Confidence must be >= 70%", evalGood.confidence >= 70)

        val bestMatch = SpotifyTrackMatcher.findBestMatch(target, listOf(badCandidate1, badCandidate2, goodCandidate))
        assertNotNull(bestMatch)
        assertEquals("yt_good", bestMatch?.candidate?.id)
    }

    // ========================================================================
    // 2. Regression Test 2: MY CLEMATIS vs "Part 1"
    // ========================================================================

    @Test
    fun `Regression Test 2 - Candidate titled Part 1 is strictly REJECTED for MY CLEMATIS`() {
        val target = createTrack(
            id = "sp_456",
            title = "MY CLEMATIS",
            artist = "Artist B",
            duration = 195L
        )

        val badCandidate = createTrack(
            id = "yt_part1",
            title = "Part 1",
            artist = "Unknown Channel",
            duration = 180L
        )

        val goodCandidate = createTrack(
            id = "yt_correct",
            title = "MY CLEMATIS - Official Audio",
            artist = "Artist B",
            duration = 196L
        )

        val evalBad = SpotifyTrackMatcher.evaluateCandidate(target, badCandidate)
        assertFalse("Candidate 'Part 1' must be rejected for 'MY CLEMATIS'", evalBad.isAccepted)
        assertTrue("Confidence must be very low for 'Part 1'", evalBad.confidence < 30)

        val evalGood = SpotifyTrackMatcher.evaluateCandidate(target, goodCandidate)
        assertTrue("Candidate 'MY CLEMATIS - Official Audio' must be accepted", evalGood.isAccepted)
        assertTrue("Confidence should be >= 80%", evalGood.confidence >= 80)

        val bestMatch = SpotifyTrackMatcher.findBestMatch(target, listOf(badCandidate, goodCandidate))
        assertNotNull(bestMatch)
        assertEquals("yt_correct", bestMatch?.candidate?.id)
    }

    // ========================================================================
    // 3. Regression Tests 3, 4, 5: Indian / Bhojpuri Movie Tracks with "From Movie"
    // ========================================================================

    @Test
    fun `Regression Test 3 - Sorry Sorry - From Bhojpuriya Raja correctly matches`() {
        val target = createTrack(
            id = "sp_bhojpuri_1",
            title = "Sorry Sorry - From \"Bhojpuriya Raja\"",
            artist = "Pawan Singh",
            album = "Bhojpuriya Raja",
            duration = 245L
        )

        val candFull = createTrack(
            id = "yt_bhojpuri_1",
            title = "Sorry Sorry - From \"Bhojpuriya Raja\"",
            artist = "Pawan Singh",
            duration = 245L
        )

        val candShort = createTrack(
            id = "yt_bhojpuri_short",
            title = "Sorry Sorry",
            artist = "Pawan Singh",
            album = "Bhojpuriya Raja",
            duration = 246L
        )

        val evalFull = SpotifyTrackMatcher.evaluateCandidate(target, candFull)
        assertTrue("Full title match should be accepted", evalFull.isAccepted)
        assertTrue("Confidence >= 90%", evalFull.confidence >= 90)

        val evalShort = SpotifyTrackMatcher.evaluateCandidate(target, candShort)
        assertTrue("Short title candidate with same artist should be accepted", evalShort.isAccepted)
        assertTrue("Confidence >= 80%", evalShort.confidence >= 80)
    }

    @Test
    fun `Regression Test 4 - Palang Sagwan Ke - From Doli Saja Ke Rakhna matches`() {
        val target = createTrack(
            id = "sp_bhojpuri_2",
            title = "Palang Sagwan Ke - From \"Doli Saja Ke Rakhna\"",
            artist = "Khesari Lal Yadav",
            album = "Doli Saja Ke Rakhna",
            duration = 210L
        )

        val cand = createTrack(
            id = "yt_bhojpuri_2",
            title = "Palang Sagwan Ke (From \"Doli Saja Ke Rakhna\")",
            artist = "Khesari Lal Yadav",
            duration = 211L
        )

        val eval = SpotifyTrackMatcher.evaluateCandidate(target, cand)
        assertTrue("Candidate should be accepted", eval.isAccepted)
        assertTrue("Confidence >= 85%", eval.confidence >= 85)
    }

    @Test
    fun `Regression Test 5 - Chhalakata Hamro Jawaniya - From Bhojpuriya Raja matches`() {
        val target = createTrack(
            id = "sp_bhojpuri_3",
            title = "Chhalakata Hamro Jawaniya - From \"Bhojpuriya Raja\"",
            artist = "Pawan Singh, Priyanka Singh",
            album = "Bhojpuriya Raja",
            duration = 280L
        )

        val cand = createTrack(
            id = "yt_bhojpuri_3",
            title = "Chhalakata Hamro Jawaniya Ye Raja",
            artist = "Pawan Singh, Priyanka Singh",
            duration = 282L
        )

        val eval = SpotifyTrackMatcher.evaluateCandidate(target, cand)
        assertTrue("Candidate should be accepted", eval.isAccepted)
        assertTrue("Confidence >= 75%", eval.confidence >= 75)
    }

    // ========================================================================
    // 4. Regional Indian Music Testing Across Languages
    // ========================================================================

    @Test
    fun `Regional Indian - Hindi Bollywood, Punjabi, Tamil, Telugu, Bengali`() {
        // Hindi Bollywood
        val targetHindi = createTrack(title = "Kesariya - From \"Brahmastra\"", artist = "Arijit Singh", duration = 268L)
        val candHindi = createTrack(id = "yt_hindi", title = "Kesariya", artist = "Arijit Singh", duration = 268L)
        assertTrue(SpotifyTrackMatcher.evaluateCandidate(targetHindi, candHindi).isAccepted)

        // Punjabi
        val targetPunjabi = createTrack(title = "Softly", artist = "Karan Aujla", duration = 155L)
        val candPunjabi = createTrack(id = "yt_punjabi", title = "Softly (Official Music Video)", artist = "Karan Aujla", duration = 156L)
        assertTrue(SpotifyTrackMatcher.evaluateCandidate(targetPunjabi, candPunjabi).isAccepted)

        // Tamil
        val targetTamil = createTrack(title = "Arabic Kuthu - From \"Beast\"", artist = "Anirudh Ravichander", duration = 280L)
        val candTamil = createTrack(id = "yt_tamil", title = "Arabic Kuthu - Halamithi Habibo (From \"Beast\")", artist = "Anirudh Ravichander", duration = 280L)
        assertTrue(SpotifyTrackMatcher.evaluateCandidate(targetTamil, candTamil).isAccepted)

        // Telugu
        val targetTelugu = createTrack(title = "Naatu Naatu - From \"RRR\"", artist = "Rahul Sipligunj, Kaala Bhairava", duration = 215L)
        val candTelugu = createTrack(id = "yt_telugu", title = "Naatu Naatu (Full Song) | RRR", artist = "Rahul Sipligunj, Kaala Bhairava", duration = 216L)
        assertTrue(SpotifyTrackMatcher.evaluateCandidate(targetTelugu, candTelugu).isAccepted)
    }

    // ========================================================================
    // 5. Version Discrimination & Compatibility
    // ========================================================================

    @Test
    fun `Version Matching - Slowed vs Studio vs Live vs Acoustic`() {
        val slowedTarget = createTrack(title = "Midnight City (Slowed)", artist = "M83", duration = 280L)

        val studioCand = createTrack(id = "yt_std", title = "Midnight City", artist = "M83", duration = 243L)
        val slowedCand = createTrack(id = "yt_slow", title = "Midnight City (Slowed & Reverb)", artist = "M83", duration = 282L)
        val acousticCand = createTrack(id = "yt_acoust", title = "Midnight City (Acoustic Live)", artist = "M83", duration = 220L)

        val evalStudio = SpotifyTrackMatcher.evaluateCandidate(slowedTarget, studioCand)
        val evalSlowed = SpotifyTrackMatcher.evaluateCandidate(slowedTarget, slowedCand)
        val evalAcoustic = SpotifyTrackMatcher.evaluateCandidate(slowedTarget, acousticCand)

        assertTrue("Slowed candidate should be accepted", evalSlowed.isAccepted)
        assertFalse("Acoustic live candidate should be rejected for slowed target", evalAcoustic.isAccepted)
        assertTrue("Slowed candidate should have higher confidence than studio", evalSlowed.confidence > evalStudio.confidence)
    }

    // ========================================================================
    // 6. Duration Proximity Guard
    // ========================================================================

    @Test
    fun `Duration Guard - Severe duration delta rejects wrong song with same version tag`() {
        val target = createTrack(title = "Echoes (Remix)", artist = "Pink Floyd", duration = 400L)
        val candLargeDelta = createTrack(title = "Echoes (Remix)", artist = "Pink Floyd", duration = 210L) // 190s delta

        val eval = SpotifyTrackMatcher.evaluateCandidate(target, candLargeDelta)
        assertFalse("Large duration mismatch (> 45s) must be rejected", eval.isAccepted)
    }

    // ========================================================================
    // 7. Artist Discrimination & Featured Artists
    // ========================================================================

    @Test
    fun `Artist Agreement - Different artist rejected, featured artist accepted`() {
        val target = createTrack(title = "Levitating", artist = "Dua Lipa", duration = 203L)

        val wrongArtistCand = createTrack(id = "yt_wrong", title = "Levitating", artist = "Cover Singer", duration = 203L)
        val featArtistCand = createTrack(id = "yt_feat", title = "Levitating (feat. DaBaby)", artist = "Dua Lipa", duration = 203L)

        val evalWrong = SpotifyTrackMatcher.evaluateCandidate(target, wrongArtistCand)
        val evalFeat = SpotifyTrackMatcher.evaluateCandidate(target, featArtistCand)

        assertFalse("Completely different artist must be rejected", evalWrong.isAccepted)
        assertTrue("Featured artist version by same primary artist must be accepted", evalFeat.isAccepted)
    }

    // ========================================================================
    // 8. Generic Title Detection
    // ========================================================================

    @Test
    fun `Generic Titles - Correctly flags purely generic titles`() {
        val (tokens1, _) = SpotifyTrackMatcher.extractCoreTokensAndVersion("slowed")
        assertTrue("slowed is generic", SpotifyTrackMatcher.isPurelyGenericTitle("slowed", tokens1))

        val (tokens2, _) = SpotifyTrackMatcher.extractCoreTokensAndVersion("Part 1")
        assertTrue("Part 1 is generic", SpotifyTrackMatcher.isPurelyGenericTitle("Part 1", tokens2))

        val (tokens3, _) = SpotifyTrackMatcher.extractCoreTokensAndVersion("Remix (Audio)")
        assertTrue("Remix (Audio) is generic", SpotifyTrackMatcher.isPurelyGenericTitle("Remix (Audio)", tokens3))

        val (tokens4, _) = SpotifyTrackMatcher.extractCoreTokensAndVersion("stalk ur socials (slowed)")
        assertFalse("stalk ur socials (slowed) is NOT generic", SpotifyTrackMatcher.isPurelyGenericTitle("stalk ur socials (slowed)", tokens4))
    }

    // ========================================================================
    // 9. Indic & Regional Indian Songs
    // ========================================================================

    @Test
    fun `Indic Cross-Script Matching - Matches Romanized to Devanagari Bhojpuri titles`() {
        val target = createTrack(
            title = "Raja Ji Ke Dilwa",
            artist = "Pawan Singh",
            duration = 180L
        )

        val candDevanagari = createTrack(
            id = "yt_bhojpuri",
            title = "राजा जी के दिलवा | Pawan Singh | Bhojpuri Song",
            artist = "Wave Music",
            duration = 182L
        )

        val eval = SpotifyTrackMatcher.evaluateCandidate(target, candDevanagari)
        assertTrue("Cross-script matching should accept Devanagari equivalent", eval.isAccepted)
        assertTrue("Confidence should be >= 70%", eval.confidence >= 70)
    }
}
