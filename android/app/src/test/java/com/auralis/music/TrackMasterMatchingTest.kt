package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.LyricsValidator
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMasterMatchingTest {

    @Test
    fun `Love Me Not - corrupt 00_00 LRCLIB submission is rejected by intro sanity and master match`() {
        val corruptLrclibLyrics = LyricsData(
            syncType = SyncType.LINE_SYNC,
            provider = LyricsProvider.LRCLIB,
            durationMs = 213_684L,
            trackName = "Love Me Not",
            artistName = "Ravyn Lenae",
            lines = listOf(
                LyricLine(time = 0L, text = "You're in love with the idea of me"),
                LyricLine(time = 2_100L, text = "But you don't love me"),
                LyricLine(time = 16_800L, text = "See, right now, I need you"),
                LyricLine(time = 200_000L, text = "Love me not")
            )
        )

        // 1. LyricsValidator flags corrupt intro timing (starts at 0ms on a 213s song)
        assertTrue(LyricsValidator.hasCorruptIntroTiming(corruptLrclibLyrics, playbackDurationSec = 213L))

        // 2. LyricsAlignmentEngine rejects it as MASTER_MISMATCH even though duration delta is <= 1.5s
        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(corruptLrclibLyrics, playbackDurationMs = 213_000L)
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
        assertFalse(LyricsAlignmentEngine.isAcceptableMasterMatch(corruptLrclibLyrics, playbackDurationMs = 213_000L))
    }

    @Test
    fun `Love Me Not - genuine studio lyrics with 16_81s intro pass validation and exact master match`() {
        val genuineStudioLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            durationMs = 213_000L,
            trackName = "Love Me Not",
            artistName = "Ravyn Lenae",
            lines = listOf(
                LyricLine(time = 16_810L, text = "See, right now, I need you, I'll meet you somewhere now"),
                LyricLine(time = 21_270L, text = "You up now, I see you, I get you, take care now"),
                LyricLine(time = 201_000L, text = "Love me not")
            )
        )

        // 1. LyricsValidator does NOT flag genuine intro timing
        assertFalse(LyricsValidator.hasCorruptIntroTiming(genuineStudioLyrics, playbackDurationSec = 213L))

        // 2. Master match is EXACT_MATCH against 213s studio audio
        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(genuineStudioLyrics, playbackDurationMs = 213_000L)
        assertEquals(MasterMatchStatus.EXACT_MATCH, matchStatus)
        assertTrue(LyricsAlignmentEngine.isAcceptableMasterMatch(genuineStudioLyrics, playbackDurationMs = 213_000L))
    }

    @Test
    fun `Love Me Not - music video 222s rejects studio album lyrics 213s`() {
        val studioLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 213_461L,
            trackName = "Love Me Not",
            artistName = "Ravyn Lenae",
            lines = listOf(
                LyricLine(time = 16_810L, text = "See, right now, I need you"),
                LyricLine(time = 200_000L, text = "Love me not")
            )
        )

        // Playback audio is music video (222s) -> delta is 8.5s (> 3.5s)
        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = studioLyrics,
            playbackDurationMs = 222_000L,
            playbackTitle = "Love Me Not (Official Music Video)",
            candidateTitle = "Love Me Not"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
        assertFalse(
            LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = studioLyrics,
                playbackDurationMs = 222_000L,
                playbackTitle = "Love Me Not (Official Music Video)",
                candidateTitle = "Love Me Not"
            )
        )
    }

    @Test
    fun `Love Me Not - official video ID is re-mapped to studio audio`() {
        // cswfR85D7jM is the official video (222s)
        // HfpR4tAmI7E is the studio audio (213s)
        val mappedId = AudioStreamResolver.getMatchedVideoId("cswfR85D7jM")
        assertEquals("HfpR4tAmI7E", mappedId)
    }

    @Test
    fun `Featured artist mismatch - Levitating DaBaby vs Solo is rejected as MASTER_MISMATCH`() {
        val soloLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 203_000L,
            trackName = "Levitating",
            artistName = "Dua Lipa",
            lines = listOf(
                LyricLine(time = 9_000L, text = "If you wanna run away with me"),
                LyricLine(time = 195_000L, text = "Levitating")
            )
        )

        // Playback audio is DaBaby remix (same duration ~203s)
        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = soloLyrics,
            playbackDurationMs = 203_000L,
            playbackTitle = "Levitating (feat. DaBaby)",
            candidateTitle = "Levitating",
            playbackArtist = "Dua Lipa",
            candidateArtist = "Dua Lipa"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
        assertFalse(
            LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = soloLyrics,
                playbackDurationMs = 203_000L,
                playbackTitle = "Levitating (feat. DaBaby)",
                candidateTitle = "Levitating",
                playbackArtist = "Dua Lipa",
                candidateArtist = "Dua Lipa"
            )
        )
    }

    @Test
    fun `Featured artist mismatch - bad guy Justin Bieber vs Solo is rejected`() {
        val soloLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 194_000L,
            trackName = "bad guy",
            artistName = "Billie Eilish",
            lines = listOf(
                LyricLine(time = 13_000L, text = "White shirt now red, my bloody nose"),
                LyricLine(time = 180_000L, text = "I'm only good at bein' bad")
            )
        )

        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = soloLyrics,
            playbackDurationMs = 194_000L,
            playbackTitle = "bad guy (with Justin Bieber)",
            candidateTitle = "bad guy",
            playbackArtist = "Billie Eilish",
            candidateArtist = "Billie Eilish"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
    }

    @Test
    fun `Version mismatch - All Too Well Taylor's Version vs 2012 Original is rejected`() {
        val original2012Lyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 328_000L,
            trackName = "All Too Well",
            artistName = "Taylor Swift",
            lines = listOf(
                LyricLine(time = 8_000L, text = "I walked through the door with you"),
                LyricLine(time = 320_000L, text = "I remember it all too well")
            )
        )

        // Playback audio is Taylor's Version (329s - duration delta is only 1s!)
        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = original2012Lyrics,
            playbackDurationMs = 329_000L,
            playbackTitle = "All Too Well (Taylor's Version)",
            candidateTitle = "All Too Well",
            playbackArtist = "Taylor Swift",
            candidateArtist = "Taylor Swift"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
    }

    @Test
    fun `Featured artist mismatch - Save Your Tears Ariana Grande vs Solo is rejected`() {
        val soloLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 215_000L,
            trackName = "Save Your Tears",
            artistName = "The Weeknd",
            lines = listOf(
                LyricLine(time = 15_000L, text = "I saw you dancing in a crowded room"),
                LyricLine(time = 200_000L, text = "Save your tears for another day")
            )
        )

        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = soloLyrics,
            playbackDurationMs = 215_000L,
            playbackTitle = "Save Your Tears (Remix) [with Ariana Grande]",
            candidateTitle = "Save Your Tears",
            playbackArtist = "The Weeknd",
            candidateArtist = "The Weeknd"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
    }

    @Test
    fun `Featured artist mismatch - Old Town Road Billy Ray Cyrus vs Solo is rejected`() {
        val soloLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 113_000L,
            trackName = "Old Town Road",
            artistName = "Lil Nas X",
            lines = listOf(
                LyricLine(time = 5_000L, text = "Yeah, I'm gonna take my horse to the old town road"),
                LyricLine(time = 100_000L, text = "Can't nobody tell me nothin'")
            )
        )

        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = soloLyrics,
            playbackDurationMs = 113_000L,
            playbackTitle = "Old Town Road (feat. Billy Ray Cyrus)",
            candidateTitle = "Old Town Road",
            playbackArtist = "Lil Nas X",
            candidateArtist = "Lil Nas X"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
    }

    @Test
    fun `Version mismatch - Wish You Were Here Live vs Studio is rejected`() {
        val studioLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 334_000L,
            trackName = "Wish You Were Here",
            artistName = "Pink Floyd",
            lines = listOf(
                LyricLine(time = 90_000L, text = "So, so you think you can tell"),
                LyricLine(time = 320_000L, text = "Wish you were here")
            )
        )

        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = studioLyrics,
            playbackDurationMs = 335_000L,
            playbackTitle = "Wish You Were Here (Live at Pulse)",
            candidateTitle = "Wish You Were Here"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
    }

    @Test
    fun `Version mismatch - Circles Acoustic vs Studio is rejected`() {
        val studioLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 215_000L,
            trackName = "Circles",
            artistName = "Post Malone",
            lines = listOf(
                LyricLine(time = 10_000L, text = "We couldn't turn the page"),
                LyricLine(time = 200_000L, text = "Run away, but we're running in circles")
            )
        )

        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = studioLyrics,
            playbackDurationMs = 215_000L,
            playbackTitle = "Circles (Acoustic)",
            candidateTitle = "Circles"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
    }

    @Test
    fun `Vocal overrun safety - lyrics vocal ending 10s past playback audio duration is rejected unconditionally`() {
        val overrunLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 180_000L, // Provider erroneously claimed 180s
            trackName = "Sample Song",
            artistName = "Sample Artist",
            lines = listOf(
                LyricLine(time = 10_000L, text = "Line 1"),
                LyricLine(time = 190_000L, text = "Overrunning vocal past playback") // 190s > 180s + 3.5s
            )
        )

        // Playback audio is 180s
        assertTrue(LyricsValidator.hasVocalOverrun(overrunLyrics, playbackDurationMs = 180_000L))
        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(overrunLyrics, playbackDurationMs = 180_000L)
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
    }

    @Test
    fun `Album metadata propagation - matching album increases match confidence`() {
        val confWithoutAlbum = LyricsMatcher.calculateConfidence(
            queryTitle = "Love Me Not",
            queryArtist = "Ravyn Lenae",
            candidateTitle = "Love Me Not",
            candidateArtist = "Ravyn Lenae",
            queryDurationSec = 213L,
            candidateDurationSec = 213L,
            queryAlbum = null,
            candidateAlbum = null
        )

        val confWithAlbum = LyricsMatcher.calculateConfidence(
            queryTitle = "Love Me Not",
            queryArtist = "Ravyn Lenae",
            candidateTitle = "Love Me Not",
            candidateArtist = "Ravyn Lenae",
            queryDurationSec = 213L,
            candidateDurationSec = 213L,
            queryAlbum = "Bird's Eye",
            candidateAlbum = "Bird's Eye"
        )

        assertTrue("Confidence with matching album ($confWithAlbum) should be >= confidence without ($confWithoutAlbum)", confWithAlbum >= confWithoutAlbum)
    }

    @Test
    fun `Titles with 'with' in song title are not false positives`() {
        val features = TitleCleaner.extractFeaturedArtists("Die With A Smile", "Lady Gaga & Bruno Mars")
        assertTrue("Song title with 'with' should not extract a featured artist", features.isEmpty())
    }
}
