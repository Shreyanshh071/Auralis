package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.network.provider.LyricsCandidate
import com.auralis.music.data.parser.LyricsValidator
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAccuracyMasterMatchingTest {

    // =========================================================================
    // 1. HEAVEN KNOWS I'M MISERABLE NOW
    // =========================================================================
    @Test
    fun `testHeavenKnows - leading silence difference handled correctly with zero hardcoded offset`() {
        // Apple Music TTML returns leadingSilence = 180ms
        val rawTtml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:amll="http://amll.dev/ttml" amll:leadingSilence="180ms">
              <body>
                <div>
                  <p begin="00:16.452" end="00:20.100">
                    <span begin="00:16.452" end="00:16.800">I </span>
                    <span begin="00:16.800" end="00:17.400">was </span>
                    <span begin="00:17.400" end="00:18.200">looking </span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(rawTtml, LyricsProvider.BETTER_LYRICS)
        assertEquals(180L, parsed.leadingSilenceMs)
        assertEquals(16_452L, parsed.lines.first().time)

        // Case A: When playback audio has measured silence of 2025ms (1845ms difference)
        val alignedWithMeasurement = LyricsAlignmentEngine.alignToPlayback(
            lyrics = parsed,
            playbackDurationMs = 217_000L,
            audioLeadingSilenceMs = 2025L
        )
        // delta = 2025 - 180 = 1845ms
        val expectedFirstLineTime = 16_452L + 1845L // 18,297ms
        assertEquals(expectedFirstLineTime, alignedWithMeasurement.lines.first().time)
        assertEquals(16_452L + 1845L, alignedWithMeasurement.lines.first().words?.first()?.time)

        // Case B: When audioLeadingSilence is null (unmeasured stream), zero artificial shift applied
        val unshifted = LyricsAlignmentEngine.alignToPlayback(
            lyrics = parsed,
            playbackDurationMs = 217_000L,
            audioLeadingSilenceMs = null
        )
        assertEquals(16_452L, unshifted.lines.first().time)
        assertEquals(16_452L, unshifted.lines.first().words?.first()?.time)

        // Case C: Clamped strictly to [-2000ms, 2000ms]
        val clampedExtreme = LyricsAlignmentEngine.alignToPlayback(
            lyrics = parsed,
            playbackDurationMs = 217_000L,
            audioLeadingSilenceMs = 5000L // 5000 - 180 = 4820ms -> clamped to 2000ms
        )
        assertEquals(16_452L + 2000L, clampedExtreme.lines.first().time)
    }

    // =========================================================================
    // 2. LOSE YOURSELF
    // =========================================================================
    @Test
    fun `testLoseYourself - wrong NetEase master cut rejected by duration gate`() {
        // NetEase candidate with duration 321s (5052317)
        val netEaseCandidate = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.NETEASE,
            durationMs = 321_000L,
            trackName = "Lose Yourself",
            artistName = "Eminem",
            lines = listOf(
                LyricLine(time = 33_520L, text = "Look, if you had one shot", words = listOf(
                    LyricWord("Look, ", 33_520L, 2670L),
                    LyricWord("if ", 36_190L, 210L)
                )),
                LyricLine(time = 280_000L, text = "You can do anything you set your mind to, man")
            )
        )

        // Actual YouTube playback duration: 326s (326,000ms)
        // Delta = |326 - 321| = 5s > 3.5s gate
        val status = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = netEaseCandidate,
            playbackDurationMs = 326_000L,
            playbackTitle = "Eminem - Lose Yourself",
            candidateTitle = "Lose Yourself",
            playbackArtist = "Eminem",
            candidateArtist = "Eminem"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, status)
        assertFalse(LyricsAlignmentEngine.isAcceptableMasterMatch(netEaseCandidate, playbackDurationMs = 326_000L))
    }

    // =========================================================================
    // 3. WISH YOU WERE HERE
    // =========================================================================
    @Test
    fun `testWishYouWereHere - wrong edit or radio cut rejected as master mismatch`() {
        // Live/radio cut candidate with duration 224s
        val radioCutLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.NETEASE,
            durationMs = 224_000L,
            trackName = "Wish You Were Here",
            artistName = "Pink Floyd",
            lines = listOf(
                LyricLine(time = 94_530L, text = "So, so you think you can tell"),
                LyricLine(time = 222_000L, text = "Wish you were here")
            )
        )

        // Playback audio is 334s studio track -> delta = 110s >> 3.5s
        val status = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = radioCutLyrics,
            playbackDurationMs = 334_000L,
            playbackTitle = "Wish You Were Here",
            candidateTitle = "Wish You Were Here",
            playbackArtist = "Pink Floyd",
            candidateArtist = "Pink Floyd"
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, status)
        assertFalse(LyricsAlignmentEngine.isAcceptableMasterMatch(radioCutLyrics, playbackDurationMs = 334_000L))
    }

    // =========================================================================
    // 4. HUMBLE.
    // =========================================================================
    @Test
    fun `testHumble - video versus studio distinction and stream re-mapping`() {
        // 1. Official Video tvTRZJ-4EyI is re-mapped to studio audio 18_J_7v0i4k
        val mapped = AudioStreamResolver.getMatchedVideoId("tvTRZJ-4EyI")
        assertEquals("18_J_7v0i4k", mapped)

        // 2. Direct music video playback (184s) against 177s studio lyrics (delta 7s > 1.5s video threshold)
        val studioLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 177_000L,
            trackName = "HUMBLE.",
            artistName = "Kendrick Lamar",
            lines = listOf(
                LyricLine(time = 1_975L, text = "Nobody pray for me"),
                LyricLine(time = 160_000L, text = "My left stroke just went viral")
            )
        )

        val videoStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = studioLyrics,
            playbackDurationMs = 184_000L,
            playbackTitle = "Kendrick Lamar - HUMBLE. (Official Music Video)",
            candidateTitle = "HUMBLE."
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, videoStatus)

        // 3. Studio playback (177s) matches cleanly
        val studioStatus = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = studioLyrics,
            playbackDurationMs = 177_000L,
            playbackTitle = "HUMBLE.",
            candidateTitle = "HUMBLE."
        )
        assertEquals(MasterMatchStatus.EXACT_MATCH, studioStatus)
    }

    // =========================================================================
    // 5. BOHEMIAN RHAPSODY
    // =========================================================================
    @Test
    fun `testBohemianRhapsody - invalid 0ms first word and non-monotonic timestamps rejected`() {
        // Corrupt Bohemian Rhapsody candidate starting at 0ms on a 354s song with non-monotonic jumps
        val corruptBohemian = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 354_000L,
            trackName = "Bohemian Rhapsody",
            artistName = "Queen",
            lines = listOf(
                LyricLine(
                    time = 0L,
                    text = "Is this the real life?",
                    words = listOf(LyricWord("Is ", 0L, 200L), LyricWord("this ", 300L, 200L))
                ),
                LyricLine(
                    time = 4_000L,
                    text = "Caught in a landslide",
                    words = listOf(LyricWord("Caught ", 4_000L, 200L), LyricWord("in ", 2_500L, 200L)) // 2500ms jumps backwards!
                )
            )
        )

        // 1. Caught by corrupt intro timing check (line/word starts at 0ms on 354s song)
        assertTrue(LyricsValidator.hasCorruptIntroTiming(corruptBohemian, playbackDurationSec = 354L))

        // 2. Caught by non-monotonic word timestamp validator
        assertTrue(LyricsValidator.hasNonMonotonicWordTimestamps(corruptBohemian))
        assertTrue(LyricsValidator.isCorruptOrInvalid(corruptBohemian))

        // 3. Rejected by evaluateMasterMatch as MASTER_MISMATCH
        val status = LyricsAlignmentEngine.evaluateMasterMatch(corruptBohemian, playbackDurationMs = 354_000L)
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, status)
    }

    // =========================================================================
    // 6. DREAMS
    // =========================================================================
    @Test
    fun `testDreams - parser output preserved without timestamp rewriting or fabrication`() {
        val dreamsLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 257_800L,
            trackName = "Dreams",
            artistName = "Fleetwood Mac",
            lines = listOf(
                LyricLine(time = 17_171L, text = "Now here you go again, you say you want your freedom"),
                LyricLine(time = 248_599L, text = "You'll know")
            )
        )

        // Verify timestamps are not shifted or stretched when leading silence is not provided
        val aligned = LyricsAlignmentEngine.alignToPlayback(dreamsLyrics, playbackDurationMs = 257_000L)
        assertEquals(17_171L, aligned.lines.first().time)
        assertEquals(248_599L, aligned.lines.last().time)
        assertEquals(dreamsLyrics.lines.size, aligned.lines.size)
    }

    // =========================================================================
    // 7. LOVE ME NOT
    // =========================================================================
    @Test
    fun `testLoveMeNot - corrupt 00_00 LRCLIB candidate rejected and studio match preserved`() {
        val corruptLrc = LyricsData(
            syncType = SyncType.LINE_SYNC,
            provider = LyricsProvider.LRCLIB,
            durationMs = 213_000L,
            trackName = "Love Me Not",
            artistName = "Ravyn Lenae",
            lines = listOf(
                LyricLine(time = 0L, text = "You're in love with the idea of me"),
                LyricLine(time = 16_800L, text = "See, right now, I need you")
            )
        )

        assertTrue(LyricsValidator.hasCorruptIntroTiming(corruptLrc, playbackDurationSec = 213L))
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, LyricsAlignmentEngine.evaluateMasterMatch(corruptLrc, playbackDurationMs = 213_000L))

        // Studio lyrics starting at genuine 16.8s intro pass
        val studioLrc = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 213_461L,
            trackName = "Love Me Not",
            artistName = "Ravyn Lenae",
            lines = listOf(
                LyricLine(time = 16_834L, text = "See, right now, I need you"),
                LyricLine(time = 201_122L, text = "Love me not")
            )
        )
        assertFalse(LyricsValidator.hasCorruptIntroTiming(studioLrc, playbackDurationSec = 213L))
        assertEquals(MasterMatchStatus.EXACT_MATCH, LyricsAlignmentEngine.evaluateMasterMatch(studioLrc, playbackDurationMs = 213_000L))
    }

    // =========================================================================
    // 8. SAME-DURATION ALTERNATE MASTERS
    // =========================================================================
    @Test
    fun `testSameDurationAlternateMasters - all 5 cases strictly rejected as MASTER_MISMATCH`() {
        // 1. Levitating: solo vs DaBaby
        val levitatingSolo = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 203_000L,
            trackName = "Levitating",
            artistName = "Dua Lipa",
            lines = listOf(LyricLine(time = 9_000L, text = "If you wanna run away with me"))
        )
        assertEquals(
            MasterMatchStatus.MASTER_MISMATCH,
            LyricsAlignmentEngine.evaluateMasterMatch(
                lyrics = levitatingSolo,
                playbackDurationMs = 203_000L,
                playbackTitle = "Levitating (feat. DaBaby)",
                candidateTitle = "Levitating",
                playbackArtist = "Dua Lipa",
                candidateArtist = "Dua Lipa"
            )
        )

        // 2. bad guy: solo vs Justin Bieber
        val badGuySolo = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 194_000L,
            trackName = "bad guy",
            artistName = "Billie Eilish",
            lines = listOf(LyricLine(time = 13_000L, text = "White shirt now red"))
        )
        assertEquals(
            MasterMatchStatus.MASTER_MISMATCH,
            LyricsAlignmentEngine.evaluateMasterMatch(
                lyrics = badGuySolo,
                playbackDurationMs = 194_000L,
                playbackTitle = "bad guy (with Justin Bieber)",
                candidateTitle = "bad guy",
                playbackArtist = "Billie Eilish",
                candidateArtist = "Billie Eilish"
            )
        )

        // 3. All Too Well: original vs Taylor's Version
        val allTooWellOriginal = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 328_000L,
            trackName = "All Too Well",
            artistName = "Taylor Swift",
            lines = listOf(LyricLine(time = 8_000L, text = "I walked through the door with you"))
        )
        assertEquals(
            MasterMatchStatus.MASTER_MISMATCH,
            LyricsAlignmentEngine.evaluateMasterMatch(
                lyrics = allTooWellOriginal,
                playbackDurationMs = 329_000L,
                playbackTitle = "All Too Well (Taylor's Version)",
                candidateTitle = "All Too Well",
                playbackArtist = "Taylor Swift",
                candidateArtist = "Taylor Swift"
            )
        )

        // 4. Save Your Tears: solo vs Ariana Grande
        val saveYourTearsSolo = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 215_000L,
            trackName = "Save Your Tears",
            artistName = "The Weeknd",
            lines = listOf(LyricLine(time = 15_000L, text = "I saw you dancing in a crowded room"))
        )
        assertEquals(
            MasterMatchStatus.MASTER_MISMATCH,
            LyricsAlignmentEngine.evaluateMasterMatch(
                lyrics = saveYourTearsSolo,
                playbackDurationMs = 215_000L,
                playbackTitle = "Save Your Tears (Remix) [with Ariana Grande]",
                candidateTitle = "Save Your Tears",
                playbackArtist = "The Weeknd",
                candidateArtist = "The Weeknd"
            )
        )

        // 5. Old Town Road: solo vs Billy Ray Cyrus
        val oldTownRoadSolo = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 113_000L,
            trackName = "Old Town Road",
            artistName = "Lil Nas X",
            lines = listOf(LyricLine(time = 5_000L, text = "Yeah, I'm gonna take my horse"))
        )
        assertEquals(
            MasterMatchStatus.MASTER_MISMATCH,
            LyricsAlignmentEngine.evaluateMasterMatch(
                lyrics = oldTownRoadSolo,
                playbackDurationMs = 113_000L,
                playbackTitle = "Old Town Road (feat. Billy Ray Cyrus)",
                candidateTitle = "Old Town Road",
                playbackArtist = "Lil Nas X",
                candidateArtist = "Lil Nas X"
            )
        )
    }

    // =========================================================================
    // 9. EXISTING KNOWN-GOOD TRACKS
    // =========================================================================
    @Test
    fun `testExistingKnownGoodRichSyncTracks - pass cleanly without regression`() {
        val knownGoodTracks = listOf(
            Triple("Lover", "Diljit Dosanjh", 190_000L),
            Triple("Why This Kolaveri Di", "Anirudh Ravichander", 250_000L),
            Triple("Brown Munde", "AP Dhillon", 268_000L),
            Triple("Die With A Smile", "Lady Gaga & Bruno Mars", 251_000L)
        )

        for ((title, artist, durationMs) in knownGoodTracks) {
            val goodLyrics = LyricsData(
                syncType = SyncType.RICHSYNC,
                provider = LyricsProvider.BETTER_LYRICS,
                durationMs = durationMs,
                trackName = title,
                artistName = artist,
                lines = listOf(
                    LyricLine(time = 12_000L, text = "First vocal line here", words = listOf(
                        LyricWord("First ", 12_000L, 400L),
                        LyricWord("vocal ", 12_400L, 400L),
                        LyricWord("line ", 12_800L, 400L),
                        LyricWord("here", 13_200L, 400L)
                    )),
                    LyricLine(time = 16_000L, text = "Second vocal line singing", words = listOf(
                        LyricWord("Second ", 16_000L, 400L),
                        LyricWord("vocal ", 16_400L, 400L),
                        LyricWord("line ", 16_800L, 400L),
                        LyricWord("singing", 17_200L, 400L)
                    )),
                    LyricLine(time = 24_000L, text = "Third line in chorus"),
                    LyricLine(time = 32_000L, text = "Fourth line continues"),
                    LyricLine(time = durationMs - 10_000L, text = "Final vocal line")
                )
            )

            assertFalse(LyricsValidator.isCorruptOrInvalid(goodLyrics))
            assertFalse(LyricsValidator.hasCorruptIntroTiming(goodLyrics, durationMs / 1000L))
            val status = LyricsAlignmentEngine.evaluateMasterMatch(
                lyrics = goodLyrics,
                playbackDurationMs = durationMs,
                playbackTitle = title,
                candidateTitle = title,
                playbackArtist = artist,
                candidateArtist = artist
            )
            assertEquals("Track '$title' must be EXACT_MATCH", MasterMatchStatus.EXACT_MATCH, status)
            assertTrue("Track '$title' must be acceptable", LyricsAlignmentEngine.isAcceptableMasterMatch(goodLyrics, playbackDurationMs = durationMs))

            val score = LyricsClient.calculateQualityScore(
                cand = LyricsCandidate(goodLyrics, 90, SyncType.RICHSYNC, LyricsProvider.BETTER_LYRICS),
                queryDurationSec = durationMs / 1000L,
                queryDurationMs = durationMs,
                queryTitle = title,
                queryArtist = artist
            )
            assertTrue("Quality score for '$title' should be high ($score)", score >= 100.0)
        }
    }

    // =========================================================================
    // 10. SPECIFIC REGRESSION VERIFICATIONS
    // =========================================================================
    @Test
    fun `testHeavenKnows - remaster candidate preferred over radio cut when album matches`() {
        val queryTitle = "Heaven Knows I'm Miserable Now"
        val queryAlbum = "Hatful of Hollow (2008 Remaster)"
        val queryArtist = "The Smiths"
        val durationMs = 217_000L

        // Candidate 1: Original / Radio master with leading silence 180ms
        val candRadio = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 217_000L,
            trackName = "Heaven Knows I'm Miserable Now",
            artistName = "The Smiths",
            leadingSilenceMs = 180L,
            lines = listOf(
                LyricLine(time = 16_452L, text = "I was looking for a job and then I found a job")
            )
        )

        // Candidate 2: 2008 Remaster with leading silence 1900ms matching YouTube pre-roll
        val candRemaster = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 217_000L,
            trackName = "Heaven Knows I'm Miserable Now",
            artistName = "The Smiths",
            leadingSilenceMs = 1900L,
            lines = listOf(
                LyricLine(time = 18_200L, text = "I was looking for a job and then I found a job")
            )
        )

        val confRadio = com.auralis.music.data.parser.LyricsMatcher.calculateConfidence(
            queryTitle = queryTitle,
            queryArtist = queryArtist,
            candidateTitle = candRadio.trackName ?: "",
            candidateArtist = candRadio.artistName ?: "",
            queryDurationSec = 217L,
            candidateDurationSec = 217L,
            queryAlbum = queryAlbum,
            candidateAlbum = "Louder Than Bombs"
        )

        val confRemaster = com.auralis.music.data.parser.LyricsMatcher.calculateConfidence(
            queryTitle = queryTitle,
            queryArtist = queryArtist,
            candidateTitle = candRemaster.trackName ?: "",
            candidateArtist = candRemaster.artistName ?: "",
            queryDurationSec = 217L,
            candidateDurationSec = 217L,
            queryAlbum = queryAlbum,
            candidateAlbum = "Hatful of Hollow (2008 Remaster)"
        )

        assertTrue(
            "2008 Remaster candidate ($confRemaster) must outscore radio master ($confRadio) for 2008 Remaster query",
            confRemaster > confRadio
        )
    }

    @Test
    fun `testHumble - TTML duration fallback and pipeline version 12 verification`() {
        assertEquals("Lyrics pipeline version must be 12 to purge stale cache", 12, com.auralis.music.data.repository.LyricsRepositoryImpl.LYRICS_PIPELINE_VERSION)

        val rawTtml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:amll="http://amll.dev/ttml">
              <body>
                <div>
                  <p begin="00:01.975" end="00:05.100">
                    <span begin="00:01.975" end="00:03.000">Nobody </span>
                    <span begin="00:03.000" end="00:05.100">pray for me</span>
                  </p>
                  <p begin="02:50.000" end="02:57.000">
                    <span begin="02:50.000" end="02:57.000">My left stroke just went viral</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(rawTtml, LyricsProvider.BETTER_LYRICS)
        // Even when <body dur> is missing, effectiveDurationMs falls back to the last line end timestamp (177,000ms)
        assertEquals(177_000L, parsed.effectiveDurationMs)
        val candDuration = parsed.durationMs?.let { it / 1000L } ?: (parsed.effectiveDurationMs / 1000L)
        assertEquals(177L, candDuration)
        assertEquals(SyncType.RICHSYNC, parsed.syncType)

        // When <body dur="2:57.000"> is explicitly present (Apple Music TTML standard)
        val ttmlWithDur = rawTtml.replace("<body>", "<body dur=\"2:57.000\">")
        val parsedWithDur = TtmlParser.parse(ttmlWithDur, LyricsProvider.BETTER_LYRICS)
        assertEquals(177_000L, parsedWithDur.durationMs)
        assertEquals(177_000L, parsedWithDur.effectiveDurationMs)
    }

    @Test
    fun `testLoveMeNot - fillLyricsGaps does not inject duplicate lines into instrumental intro`() {
        val primaryStudio = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 213_000L,
            trackName = "Love Me Not",
            artistName = "Ravyn Lenae",
            lines = listOf(
                LyricLine(time = 16_834L, text = "See, right now, I need you", words = listOf(
                    LyricWord("See, ", 16_834L, 300L),
                    LyricWord("right ", 17_134L, 300L),
                    LyricWord("now, ", 17_434L, 300L),
                    LyricWord("I ", 17_734L, 200L),
                    LyricWord("need ", 17_934L, 300L),
                    LyricWord("you", 18_234L, 400L)
                )),
                LyricLine(time = 20_000L, text = "Second line of the song")
            )
        )

        // Secondary candidate with duplicate intro line at 7.5s
        val secondaryCandidate = LyricsData(
            syncType = SyncType.LINE_SYNC,
            provider = LyricsProvider.LRCLIB,
            durationMs = 213_000L,
            trackName = "Love Me Not",
            artistName = "Ravyn Lenae",
            lines = listOf(
                LyricLine(time = 7_500L, text = "See, right now, I need you"),
                LyricLine(time = 16_800L, text = "See, right now, I need you"),
                LyricLine(time = 20_000L, text = "Second line of the song")
            )
        )

        val healed = LyricsClient.fillLyricsGaps(primaryStudio, listOf(secondaryCandidate))
        // Must NOT have injected duplicate intro line at 7.5s
        assertEquals("Must remain exactly 2 lines (no duplicate intro line injected)", 2, healed.lines.size)
        assertEquals("First line time must remain ~16.8s", 16_834L, healed.lines.first().time)
        assertEquals("See, right now, I need you", healed.lines.first().text)
    }
}
