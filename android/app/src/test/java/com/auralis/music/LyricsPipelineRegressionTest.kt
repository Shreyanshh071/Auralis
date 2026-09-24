package com.auralis.music

import com.auralis.music.data.local.dao.LyricsDao
import com.auralis.music.data.local.dao.NegativeLyricsDao
import com.auralis.music.data.local.entity.LyricsEntity
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.LyricsCandidate
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricsPipelineRegressionTest {

    private fun createWordTimedLine(time: Long, text: String, wordCount: Int = 3): LyricLine {
        val words = (0 until wordCount).map { i ->
            LyricWord(
                word = "word$i ",
                time = time + (i * 300L),
                duration = 250L
            )
        }
        return LyricLine(
            time = time,
            text = text,
            isInstrumental = false,
            words = words
        )
    }

    private fun createLineTimedLine(time: Long, text: String): LyricLine {
        return LyricLine(
            time = time,
            text = text,
            isInstrumental = false,
            words = null
        )
    }

    @Test
    fun testRichSyncAlwaysOutranksLineSyncRegardlessOfScoreOrSpeed() {
        val wordCandidate = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                createWordTimedLine(1000L, "Hello world"),
                createWordTimedLine(4000L, "How are you")
            ),
            provider = LyricsProvider.PAXSENIX,
            durationMs = 200_000L
        )
        val lineCandidate = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                createLineTimedLine(1000L, "Hello world"),
                createLineTimedLine(4000L, "How are you")
            ),
            provider = LyricsProvider.LRCLIB,
            durationMs = 200_000L
        )

        val wordTier = LyricsClient.tierOf(wordCandidate)
        val lineTier = LyricsClient.tierOf(lineCandidate)

        assertEquals(LyricsClient.TIER_WORD, wordTier)
        assertEquals(LyricsClient.TIER_LINE, lineTier)

        // Even if line candidate had higher score (e.g. 190 vs 150), word candidate MUST outrank it
        val wordOutranksLine = LyricsClient.outranks(
            tier = wordTier,
            score = 150.0,
            bestTier = lineTier,
            bestScore = 190.0,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.PAXSENIX,
            bestProvider = LyricsProvider.LRCLIB
        )
        assertTrue("Aligned RichSync MUST outrank faster/higher-score LineSync", wordOutranksLine)

        // Faster line-sync arriving later must NEVER replace existing RichSync
        val lineOutranksWord = LyricsClient.outranks(
            tier = lineTier,
            score = 190.0,
            bestTier = wordTier,
            bestScore = 150.0,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.LRCLIB,
            bestProvider = LyricsProvider.PAXSENIX
        )
        assertFalse("LineSync must NEVER replace an existing aligned RichSync candidate", lineOutranksWord)
    }

    @Test
    fun testNullDurationDoesNotRejectInstrumentalOutro() {
        // Song is 220s (220,000ms), but vocals stop at 180s (180,000ms) with a 40s instrumental outro
        val lyricsWithoutStatedDuration = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                createWordTimedLine(15_000L, "First line"),
                createWordTimedLine(180_000L, "Last line ending at 181s")
            ),
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = null // Missing header duration metadata
        )

        val match = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = lyricsWithoutStatedDuration,
            playbackDurationMs = 220_000L
        )
        // UNKNOWN CANDIDATE DURATION != EXACT MASTER MATCH
        // Candidate with durationMs == null must NOT be rejected as MASTER_MISMATCH due to normal vocal outro,
        // but must NEVER be treated as EXACT_MATCH.
        assertEquals(
            "Word sync with durationMs == null must NOT be rejected as MASTER_MISMATCH due to normal vocal outro",
            MasterMatchStatus.COMPATIBLE_OFFSET,
            match
        )
        assertNotEquals(MasterMatchStatus.EXACT_MATCH, match)
        assertTrue(
            "Unknown candidate duration with non-overrunning vocals must be acceptable as compatible match",
            LyricsAlignmentEngine.isAcceptableMasterMatch(lyricsWithoutStatedDuration, 220_000L)
        )
    }

    @Test
    fun testNullDurationRejectsIfVocalsOverrunTrackLength() {
        // Song is 180s, but lyrics continue until 230s (50s after track finishes)
        val lyricsOverrunning = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                createWordTimedLine(15_000L, "First line"),
                createWordTimedLine(230_000L, "Last line way past audio length")
            ),
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = null
        )

        val match = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = lyricsOverrunning,
            playbackDurationMs = 180_000L
        )
        assertEquals(
            "Lyrics extending past playback duration + tolerance must be classified as MASTER_MISMATCH",
            MasterMatchStatus.MASTER_MISMATCH,
            match
        )
    }

    @Test
    fun testExactVideoMatchBypassRestrictedToUnisonWithGenuineVideoId() {
        // A 357s BetterLyrics candidate playing on 276s track must NOT bypass duration check
        val studioLyricsWithSpId = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(1000L, "Hello")),
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 357_267L,
            isExactVideoMatch = true, // Mislabelled flag from old bug
            matchedVideoId = "sp_1wyedcs7wgjv0rg7rmmx3o"
        )

        val match = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = studioLyricsWithSpId,
            playbackDurationMs = 276_000L,
            playbackVideoId = "sp_1wyedcs7wgjv0rg7rmmx3o"
        )
        assertEquals(
            "Studio candidate with 357s vs 276s MUST be rejected as MASTER_MISMATCH despite exact ID",
            MasterMatchStatus.MASTER_MISMATCH,
            match
        )
    }

    @Test
    fun testCachedRichSyncPreservedWhenNetworkReturnsLineSync() = runBlocking {
        val mockDao = mockk<LyricsDao>(relaxed = true)
        val mockNegativeDao = mockk<NegativeLyricsDao>(relaxed = true)
        val mockClient = mockk<LyricsClient>()

        val trackKey = "test_track_123"
        val playbackMs = 210_000L

        // 1. Prepare existing Room DB row: RichSync with 4 words/lines
        val existingLines = listOf(
            createWordTimedLine(5000L, "Verse one"),
            createWordTimedLine(10000L, "Verse two"),
            createWordTimedLine(15000L, "Chorus here"),
            createWordTimedLine(20000L, "Outro there")
        )
        val existingEntity = LyricsEntity(
            trackId = trackKey,
            trackName = "Test Song",
            artistName = "Test Artist",
            plainLyrics = "Verse one\nVerse two\nChorus here\nOutro there",
            syncType = SyncType.RICHSYNC.name,
            durationMs = 210_000L,
            hasWordTiming = true,
            pipelineVersion = 7,
            linesJson = JSONArray().apply {
                existingLines.forEach { line ->
                    put(JSONObject().apply {
                        put("time", line.time)
                        put("text", line.text)
                        put("isInstrumental", false)
                        put("words", JSONArray().apply {
                            line.words?.forEach { w ->
                                put(JSONObject().apply {
                                    put("word", w.word)
                                    put("time", w.time)
                                    put("duration", w.duration)
                                })
                            }
                        })
                    })
                }
            }.toString(),
            provider = LyricsProvider.BETTER_LYRICS.name,
            leadingSilenceMs = 500L
        )

        coEvery { mockDao.getLyrics(trackKey) } returns existingEntity

        // 2. Network cascade returns LINE_SYNC (e.g. from LRCLIB)
        val networkLineSync = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                createLineTimedLine(5000L, "Verse one"),
                createLineTimedLine(10000L, "Verse two"),
                createLineTimedLine(15000L, "Chorus here"),
                createLineTimedLine(20000L, "Outro there")
            ),
            provider = LyricsProvider.LRCLIB,
            durationMs = 210_000L,
            trackName = "Test Song",
            artistName = "Test Artist"
        )
        coEvery {
            mockClient.getLyrics(
                title = any(),
                artist = any(),
                durationSec = any(),
                videoId = any(),
                album = any(),
                channelTitle = any(),
                durationMs = any(),
                audioLeadingSilenceMs = any()
            )
        } returns networkLineSync

        val repository = LyricsRepositoryImpl(
            lyricsClient = mockClient,
            lyricsDao = mockDao,
            negativeLyricsDao = mockNegativeDao
        )

        // 3. Force refresh triggers getLyrics
        val result = repository.getLyrics(
            title = "Test Song",
            artist = "Test Artist",
            durationSec = 210L,
            videoId = trackKey,
            forceRefresh = true,
            durationMs = playbackMs
        )

        assertNotNull(result)
        assertEquals("Final result MUST remain RICHSYNC", SyncType.RICHSYNC, result?.syncType)
        assertTrue("Final result MUST retain genuine per-word timestamps", result?.lines?.any { it.words != null } == true)
        assertEquals("Provider MUST remain BETTER_LYRICS", LyricsProvider.BETTER_LYRICS, result?.provider)

        // Verify Room DB was NOT overwritten with line-sync
        coVerify(exactly = 0) { mockDao.insertLyrics(match { !it.hasWordTiming }) }

        // Verify subsequent getCachedLyrics serves the preserved RICHSYNC from memory
        val cached = repository.getCachedLyrics(
            title = "Test Song",
            artist = "Test Artist",
            durationSec = 210L,
            videoId = trackKey,
            durationMs = playbackMs
        )
        assertNotNull(cached)
        assertEquals("Memory cache MUST serve RICHSYNC", SyncType.RICHSYNC, cached?.syncType)
    }

    @Test
    fun testCachedLineSyncUpgradesWhenNetworkReturnsRichSync() = runBlocking {
        val mockDao = mockk<LyricsDao>(relaxed = true)
        val mockNegativeDao = mockk<NegativeLyricsDao>(relaxed = true)
        val mockClient = mockk<LyricsClient>()

        val trackKey = "upgrade_track_456"
        val playbackMs = 180_000L

        // 1. Existing Room DB row: LINE_SYNC (hasWordTiming = false)
        val existingEntity = LyricsEntity(
            trackId = trackKey,
            trackName = "Upgrade Song",
            artistName = "Artist",
            plainLyrics = "Line 1\nLine 2\nLine 3\nLine 4",
            syncType = SyncType.LINE_SYNC.name,
            durationMs = 180_000L,
            hasWordTiming = false,
            pipelineVersion = 7,
            linesJson = JSONArray().apply {
                put(JSONObject().apply { put("time", 2000L); put("text", "Line 1"); put("isInstrumental", false) })
                put(JSONObject().apply { put("time", 6000L); put("text", "Line 2"); put("isInstrumental", false) })
                put(JSONObject().apply { put("time", 10000L); put("text", "Line 3"); put("isInstrumental", false) })
                put(JSONObject().apply { put("time", 14000L); put("text", "Line 4"); put("isInstrumental", false) })
            }.toString(),
            provider = LyricsProvider.LRCLIB.name,
            leadingSilenceMs = null
        )

        coEvery { mockDao.getLyrics(trackKey) } returns existingEntity

        // 2. Network cascade returns fresh RICHSYNC
        val networkRichSync = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                createWordTimedLine(2000L, "Line 1"),
                createWordTimedLine(6000L, "Line 2"),
                createWordTimedLine(10000L, "Line 3"),
                createWordTimedLine(14000L, "Line 4")
            ),
            provider = LyricsProvider.PAXSENIX,
            durationMs = 180_000L,
            trackName = "Upgrade Song",
            artistName = "Artist"
        )
        coEvery {
            mockClient.getLyrics(any(), any(), any(), any(), any(), any(), any(), any())
        } returns networkRichSync

        val repository = LyricsRepositoryImpl(
            lyricsClient = mockClient,
            lyricsDao = mockDao,
            negativeLyricsDao = mockNegativeDao
        )

        val result = repository.getLyrics(
            title = "Upgrade Song",
            artist = "Artist",
            durationSec = 180L,
            videoId = trackKey,
            forceRefresh = false,
            durationMs = playbackMs
        )

        assertNotNull(result)
        assertEquals("Result MUST upgrade to RICHSYNC", SyncType.RICHSYNC, result?.syncType)
        assertEquals(LyricsProvider.PAXSENIX, result?.provider)

        // Verify Room DB was upgraded with hasWordTiming = true
        coVerify(atLeast = 1) {
            mockDao.insertLyrics(match { it.hasWordTiming && it.provider == LyricsProvider.PAXSENIX.name })
        }
    }

    @Test
    fun testMismatchedCachedWordSyncIsReplacedByAlignedCandidate() = runBlocking {
        val mockDao = mockk<LyricsDao>(relaxed = true)
        val mockNegativeDao = mockk<NegativeLyricsDao>(relaxed = true)
        val mockClient = mockk<LyricsClient>()

        val trackKey = "sp_1wyedcs7wgjv0rg7rmmx3o" // Bitter Sweet Symphony radio edit (276s)
        val playbackMs = 276_000L

        // Old mismatched cached row: 357s album cut from BetterLyrics
        val mismatchedEntity = LyricsEntity(
            trackId = trackKey,
            trackName = "Bitter Sweet Symphony",
            artistName = "The Verve",
            plainLyrics = "Lyrics...",
            syncType = SyncType.RICHSYNC.name,
            durationMs = 357_267L,
            hasWordTiming = true,
            pipelineVersion = 2,
            linesJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("time", 66300L) // 66.3s intro vocal
                    put("text", "Cause it's a bitter sweet symphony")
                    put("isInstrumental", false)
                    put("words", JSONArray().apply {
                        put(JSONObject().apply { put("word", "Cause"); put("time", 66300L); put("duration", 300L) })
                    })
                })
                put(JSONObject().apply { put("time", 70000L); put("text", "that's life"); put("isInstrumental", false) })
                put(JSONObject().apply { put("time", 74000L); put("text", "trying to make ends meet"); put("isInstrumental", false) })
                put(JSONObject().apply { put("time", 78000L); put("text", "you're a slave to money"); put("isInstrumental", false) })
            }.toString(),
            provider = LyricsProvider.BETTER_LYRICS.name,
            leadingSilenceMs = 63000L
        )

        coEvery { mockDao.getLyrics(trackKey) } returns mismatchedEntity

        // Network returns correctly aligned radio-edit line sync (276s, intro at 35.9s)
        val alignedRadioEdit = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                createLineTimedLine(35900L, "Cause it's a bitter sweet symphony"),
                createLineTimedLine(40000L, "that's life"),
                createLineTimedLine(44000L, "trying to make ends meet"),
                createLineTimedLine(48000L, "you're a slave to money")
            ),
            provider = LyricsProvider.LRCLIB,
            durationMs = 276_000L,
            trackName = "Bitter Sweet Symphony",
            artistName = "The Verve"
        )
        coEvery {
            mockClient.getLyrics(any(), any(), any(), any(), any(), any(), any(), any())
        } returns alignedRadioEdit

        val repository = LyricsRepositoryImpl(
            lyricsClient = mockClient,
            lyricsDao = mockDao,
            negativeLyricsDao = mockNegativeDao
        )

        val result = repository.getLyrics(
            title = "Bitter Sweet Symphony",
            artist = "The Verve",
            durationSec = 276L,
            videoId = trackKey,
            forceRefresh = false,
            durationMs = playbackMs
        )

        assertNotNull(result)
        assertEquals("Aligned radio edit must win over mismatched 357s album cut", LyricsProvider.LRCLIB, result?.provider)
        assertEquals("First line must start at radio edit intro ~35.9s, not ~66.3s", 35900L, result?.lines?.first()?.time)

        // Verify Room DB row was replaced with the aligned candidate
        coVerify(atLeast = 1) {
            mockDao.insertLyrics(match { it.durationMs == 276_000L && it.provider == LyricsProvider.LRCLIB.name })
        }
    }

    @Test
    fun testLineSyncCandidateWithDeltaOver3Point5SecondsRejectedAsMasterMismatch() {
        // Goal 1 & Test A: Line-sync candidate with delta > 3.5s must be rejected as MASTER_MISMATCH
        val playbackMs = 200_000L
        val lineSyncCandidate = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(createLineTimedLine(5000L, "Hello world")),
            provider = LyricsProvider.LRCLIB,
            durationMs = 204_000L // Delta = 4.0s (> 3.5s threshold)
        )

        val masterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = lineSyncCandidate,
            playbackDurationMs = playbackMs
        )
        assertEquals("Delta > 3.5s must produce MASTER_MISMATCH for line-sync", MasterMatchStatus.MASTER_MISMATCH, masterMatch)

        val isAcceptable = LyricsAlignmentEngine.isAcceptableMasterMatch(
            lyrics = lineSyncCandidate,
            playbackDurationMs = playbackMs
        )
        assertFalse("Line-sync candidate with delta > 3.5s must NOT be acceptable", isAcceptable)
    }

    @Test
    fun testLineSyncCandidateWithDeltaAround7SecondsNeverWinsOverNoLyrics() = runBlocking {
        // Goal 1 & Test B: Line-sync candidate with delta ~7s (e.g. film version vs album cut)
        // Must be rejected and never win over no-lyrics result
        val mockDao = mockk<LyricsDao>(relaxed = true)
        val mockNegativeDao = mockk<NegativeLyricsDao>(relaxed = true)
        val mockClient = mockk<LyricsClient>()

        val playbackMs = 261_000L // 4:21 film version
        val trackKey = "film_cut_123"

        coEvery { mockDao.getLyrics(trackKey) } returns null
        // Client returns 268s studio line-sync candidate (delta = 7s)
        val mismatchedLineSync = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(createLineTimedLine(9400L, "Song line")),
            provider = LyricsProvider.LRCLIB,
            durationMs = 268_000L
        )
        coEvery {
            mockClient.getLyrics(any(), any(), any(), any(), any(), any(), any(), any())
        } returns null // LyricsClient internal gate rejects candidates where !isAcceptable

        val repository = LyricsRepositoryImpl(
            lyricsClient = mockClient,
            lyricsDao = mockDao,
            negativeLyricsDao = mockNegativeDao
        )

        val result = repository.getLyrics(
            title = "Test Song",
            artist = "Test Artist",
            durationSec = 261L,
            videoId = trackKey,
            forceRefresh = false,
            durationMs = playbackMs
        )

        assertNull("Mismatched 7s line-sync candidate must be rejected; no-lyrics is preferred over bad sync", result)
    }

    @Test
    fun testCompatibleOffsetCandidateWithoutAudioLeadingSilenceIsAcceptableAndUnshifted() {
        // Candidate with 1.5s < delta <= 3.5s and audioLeadingSilenceMs == null
        // remains acceptable for playback and runs unshifted (offsetMs = 0)
        val playbackMs = 200_000L
        val candidateWith2Point5sDelta = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(10_000L, "Hello")),
            provider = LyricsProvider.PAXSENIX,
            durationMs = 202_500L, // Delta = 2500ms (within 1500ms - 3500ms)
            leadingSilenceMs = 500L,
            isExactVideoMatch = false
        )

        val masterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = candidateWith2Point5sDelta,
            playbackDurationMs = playbackMs
        )
        assertEquals(MasterMatchStatus.COMPATIBLE_OFFSET, masterMatch)

        val isAcceptable = LyricsAlignmentEngine.isAcceptableMasterMatch(
            lyrics = candidateWith2Point5sDelta,
            playbackDurationMs = playbackMs,
            audioLeadingSilenceMs = null // Unverified leading silence
        )
        assertTrue("Compatible offset without audio leading silence must remain acceptable for playback", isAcceptable)

        // alignToPlayback must run unshifted (offsetMs = 0)
        val aligned = LyricsAlignmentEngine.alignToPlayback(
            lyrics = candidateWith2Point5sDelta,
            playbackDurationMs = playbackMs,
            audioLeadingSilenceMs = null
        )
        assertEquals(10_000L, aligned.lines[0].time) // Untouched; not altered
    }

    @Test
    fun testCompatibleOffsetCandidateWithValidLeadingSilencePreservesAlignment() {
        // Goal 2 & Test D: Same compatible-offset candidate WITH measured audio leading silence
        // preserves existing alignment behavior
        val playbackMs = 200_000L
        val candidateWith2Point5sDelta = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(10_000L, "Hello")),
            provider = LyricsProvider.PAXSENIX,
            durationMs = 202_500L,
            leadingSilenceMs = 500L,
            isExactVideoMatch = false
        )

        val isAcceptable = LyricsAlignmentEngine.isAcceptableMasterMatch(
            lyrics = candidateWith2Point5sDelta,
            playbackDurationMs = playbackMs,
            audioLeadingSilenceMs = 700L // Audio has 700ms leading silence -> delta = +200ms
        )
        assertTrue("Compatible offset WITH measured audio leading silence must be accepted", isAcceptable)

        val aligned = LyricsAlignmentEngine.alignToPlayback(
            lyrics = candidateWith2Point5sDelta,
            playbackDurationMs = playbackMs,
            audioLeadingSilenceMs = 700L
        )
        assertEquals("Line must receive verified +200ms shift", 10_200L, aligned.lines[0].time)
        assertEquals("Word timing must receive verified +200ms shift", 10_200L, aligned.lines[0].words!![0].time)
    }

    @Test
    fun testGenuineExactVideoMatchBypassesUnverifiedOffsetRejection() {
        // Goal 2 & Test E: Genuine exact-video match retains exact-video treatment
        val playbackMs = 200_000L
        val exactVideoCandidate = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(createLineTimedLine(5000L, "Hello")),
            provider = LyricsProvider.UNISON,
            durationMs = 202_800L, // Delta = 2800ms
            isExactVideoMatch = true,
            matchedVideoId = "dQw4w9WgXcQ"
        )

        val isAcceptable = LyricsAlignmentEngine.isAcceptableMasterMatch(
            lyrics = exactVideoCandidate,
            playbackDurationMs = playbackMs,
            playbackVideoId = "dQw4w9WgXcQ",
            audioLeadingSilenceMs = null // Even without leading silence, exact video is trusted
        )
        assertTrue("Genuine exact-video match must remain acceptable", isAcceptable)
    }

    @Test
    fun testMultiVersionMasterMatchingGenericFixtures() {
        // Test G: Generic test fixtures representing multi-version uploads
        // Master lyrics duration = 268,000ms (studio master)
        val studioMasterLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(9600L, "First line of song")),
            provider = LyricsProvider.PAXSENIX,
            durationMs = 268_000L,
            leadingSilenceMs = 9600L
        )

        // 1. Lyric-video playback cut (~268s) -> EXACT_MATCH, accepted
        val lyricVideoDurationMs = 268_000L
        val lyricVideoMatch = LyricsAlignmentEngine.evaluateMasterMatch(studioMasterLyrics, lyricVideoDurationMs)
        assertEquals(MasterMatchStatus.EXACT_MATCH, lyricVideoMatch)
        assertTrue(LyricsAlignmentEngine.isAcceptableMasterMatch(studioMasterLyrics, lyricVideoDurationMs))

        // 2. Film-version playback cut (~261s) -> delta = 7000ms > 3.5s -> MASTER_MISMATCH, rejected
        val filmVersionDurationMs = 261_000L
        val filmMatch = LyricsAlignmentEngine.evaluateMasterMatch(studioMasterLyrics, filmVersionDurationMs)
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, filmMatch)
        assertFalse(LyricsAlignmentEngine.isAcceptableMasterMatch(studioMasterLyrics, filmVersionDurationMs))

        // 3. Music-video playback cut (~250s) -> delta = 18000ms > 3.5s -> MASTER_MISMATCH, rejected
        val musicVideoDurationMs = 250_000L
        val musicVideoMatch = LyricsAlignmentEngine.evaluateMasterMatch(studioMasterLyrics, musicVideoDurationMs)
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, musicVideoMatch)
        assertFalse(LyricsAlignmentEngine.isAcceptableMasterMatch(studioMasterLyrics, musicVideoDurationMs))

        // 4. Full Audio playback cut with extra intro silence (~270.8s) -> delta = 2800ms
        // Delta = 2800ms <= 3.5s -> COMPATIBLE_OFFSET, acceptable for playback
        val fullAudioDurationMs = 270_800L
        val fullAudioMatch = LyricsAlignmentEngine.evaluateMasterMatch(studioMasterLyrics, fullAudioDurationMs)
        assertEquals(MasterMatchStatus.COMPATIBLE_OFFSET, fullAudioMatch)
        assertTrue("Compatible 2.8s offset without audio leading silence remains acceptable for playback",
            LyricsAlignmentEngine.isAcceptableMasterMatch(studioMasterLyrics, fullAudioDurationMs, audioLeadingSilenceMs = null))

        // But if leading silence is measured (e.g. 12,400ms vs provider 9,600ms = +2800ms offset):
        assertTrue("Verified 2.8s offset with audio leading silence must be accepted",
            LyricsAlignmentEngine.isAcceptableMasterMatch(studioMasterLyrics, fullAudioDurationMs, audioLeadingSilenceMs = 12_400L))
    }

    // ══════════════════════════════════════════════════════════════════════
    // AUDIT REGRESSION TESTS: Master Matching & Duration Propagation
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun testLrcLibDurationPropagatedIntoLyricsData() {
        val lrcSource = com.auralis.music.data.network.provider.LrcLibLyricsSource()
        val json = JSONObject().apply {
            put("id", 12345)
            put("trackName", "Kesariya")
            put("artistName", "Arijit Singh")
            put("duration", 268.0)
            put("instrumental", false)
            put("syncedLyrics", "[00:15.20] Line 1\n[00:20.10] Line 2\n[00:25.00] Line 3\n[00:30.00] Line 4")
        }

        val parsed = lrcSource.parseLrcItem(json)
        assertNotNull(parsed)
        assertEquals("LRCLIB duration in seconds must propagate to durationMs", 268_000L, parsed?.durationMs)

        // Also test instrumental LRCLIB response
        val instrumentalJson = JSONObject().apply {
            put("id", 67890)
            put("trackName", "Kesariya (Instrumental)")
            put("artistName", "Pritam")
            put("duration", 268.0)
            put("instrumental", true)
        }
        val parsedInst = lrcSource.parseLrcItem(instrumentalJson)
        assertNotNull(parsedInst)
        assertTrue(parsedInst!!.lines.first().isInstrumental)
        assertEquals("Instrumental LRCLIB response must also propagate durationMs", 268_000L, parsedInst.durationMs)

        // Also test LrcLibApi
        val lrcApi = com.auralis.music.data.remote.LrcLibApi()
        val apiParsed = lrcApi.parseLrclibItem(json)
        assertNotNull(apiParsed)
        assertEquals("LrcLibApi parser must propagate durationMs", 268_000L, apiParsed?.durationMs)
    }

    @Test
    fun testDurationSurvivesCandidateAndCacheRoundTrip() {
        val originalLyrics = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                createLineTimedLine(5000L, "Line 1"),
                createLineTimedLine(10000L, "Line 2"),
                createLineTimedLine(15000L, "Line 3"),
                createLineTimedLine(20000L, "Line 4")
            ),
            provider = LyricsProvider.LRCLIB,
            trackName = "Test Track",
            artistName = "Test Artist",
            durationMs = 268_000L,
            leadingSilenceMs = 1200L
        )

        // 1. Candidate wrapping preserves durationMs
        val candidate = LyricsCandidate(
            lyricsData = originalLyrics,
            confidence = 90,
            syncType = SyncType.LINE_SYNC,
            provider = LyricsProvider.LRCLIB
        )
        assertEquals(268_000L, candidate.lyricsData.durationMs)

        // 2. Room DB Entity conversion preserves durationMs
        val entity = LyricsRepositoryImpl.domainToEntity("test_key", candidate.lyricsData, "Test Track", "Test Artist")
        assertEquals(268_000L, entity.durationMs)

        // 3. Room DB Entity to Domain restoration preserves durationMs
        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Test Track", "Test Artist")
        assertNotNull(restored)
        assertEquals(268_000L, restored?.durationMs)
    }

    @Test
    fun testKesariyaStudioVsFilmCutMismatchRejected() {
        // Kesariya Studio: ~268s
        val studioPlaybackMs = 268_000L
        // Kesariya Film cut: ~261s
        val filmPlaybackMs = 261_000L

        val studioLyrics = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                createLineTimedLine(15_200L, "Mujhko itna bataye koyi"),
                createLineTimedLine(20_000L, "Kaise tujhse dil na lagaye koyi"),
                createLineTimedLine(25_000L, "Rabba ne tujhko banane me"),
                createLineTimedLine(250_000L, "Kesariya tera ishq hai piya")
            ),
            provider = LyricsProvider.LRCLIB,
            trackName = "Kesariya",
            artistName = "Arijit Singh",
            durationMs = 268_000L
        )

        // A) 268s studio playback -> 268s studio lyrics accepted (EXACT_MATCH)
        val studioMatch = LyricsAlignmentEngine.evaluateMasterMatch(studioLyrics, studioPlaybackMs)
        assertEquals(MasterMatchStatus.EXACT_MATCH, studioMatch)
        assertTrue(LyricsAlignmentEngine.isAcceptableMasterMatch(studioLyrics, studioPlaybackMs))

        // B) 261s film playback -> 268s studio lyrics rejected (MASTER_MISMATCH: delta = 7s > 3.5s)
        val filmMatch = LyricsAlignmentEngine.evaluateMasterMatch(studioLyrics, filmPlaybackMs)
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, filmMatch)
        assertFalse(LyricsAlignmentEngine.isAcceptableMasterMatch(studioLyrics, filmPlaybackMs))
    }

    @Test
    fun testKesariyaFilmCutNullDurationCannotBecomeExactMatch() {
        // Film playback is 261s
        val filmPlaybackMs = 261_000L

        // Candidate has unknown duration (durationMs == null), with vocals ending at 250s
        val nullDurationCandidate = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                createLineTimedLine(15_200L, "Mujhko itna bataye koyi"),
                createLineTimedLine(20_000L, "Kaise tujhse dil na lagaye koyi"),
                createLineTimedLine(25_000L, "Rabba ne tujhko banane me"),
                createLineTimedLine(250_000L, "Kesariya tera ishq hai piya")
            ),
            provider = LyricsProvider.LRCLIB,
            trackName = "Kesariya",
            artistName = "Arijit Singh",
            durationMs = null // Unknown duration
        )

        val match = LyricsAlignmentEngine.evaluateMasterMatch(nullDurationCandidate, filmPlaybackMs)
        // UNKNOWN CANDIDATE DURATION != EXACT MASTER MATCH
        assertNotEquals("Unknown candidate duration must NEVER be EXACT_MATCH", MasterMatchStatus.EXACT_MATCH, match)
        assertEquals(MasterMatchStatus.COMPATIBLE_OFFSET, match)

        val isAcceptable = LyricsAlignmentEngine.isAcceptableMasterMatch(nullDurationCandidate, filmPlaybackMs)
        assertTrue("Null-duration candidate with non-overrunning vocals is acceptable", isAcceptable)
    }

    @Test
    fun testDurationStrippedLrcLibFallbackCannotBypassMismatch() {
        val filmPlaybackMs = 261_000L

        // When LRCLIB falls back without &duration=, it returns 268s studio lyrics with duration in JSON
        val fallbackJsonWithDuration = JSONObject().apply {
            put("id", 1001)
            put("trackName", "Kesariya")
            put("artistName", "Arijit Singh")
            put("duration", 268.0)
            put("syncedLyrics", "[00:15.20] Line 1\n[00:20.10] Line 2\n[00:25.00] Line 3\n[04:10.00] Kesariya tera ishq")
        }
        val lrcSource = com.auralis.music.data.network.provider.LrcLibLyricsSource()
        val parsedWithDuration = lrcSource.parseLrcItem(fallbackJsonWithDuration)!!

        assertEquals(268_000L, parsedWithDuration.durationMs)
        assertFalse(
            "Fallback candidate with 268s duration must NOT bypass master check on 261s film cut",
            LyricsAlignmentEngine.isAcceptableMasterMatch(parsedWithDuration, filmPlaybackMs)
        )

        // If LRCLIB fallback response returned NO duration at all, but vocals extend past the track length:
        val fallbackJsonNoDuration = JSONObject().apply {
            put("id", 1002)
            put("trackName", "Kesariya")
            put("artistName", "Arijit Singh")
            put("syncedLyrics", "[00:15.20] Line 1\n[00:20.10] Line 2\n[00:25.00] Line 3\n[04:28.00] Kesariya tera ishq")
        }
        val parsedNoDuration = lrcSource.parseLrcItem(fallbackJsonNoDuration)!!
        assertNull(parsedNoDuration.durationMs)
        assertFalse(
            "Fallback candidate with null duration and overrunning vocals must NOT bypass master check on 261s film cut",
            LyricsAlignmentEngine.isAcceptableMasterMatch(parsedNoDuration, filmPlaybackMs)
        )
    }

    @Test
    fun testBitterSweetSymphony357sVs276sRejected() {
        val radioEditPlaybackMs = 276_000L

        // Album cut: 357s
        val albumCutLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(66_000L, "Cause it's a bitter sweet symphony")),
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 357_000L,
            trackName = "Bitter Sweet Symphony",
            artistName = "The Verve"
        )
        val albumMatch = LyricsAlignmentEngine.evaluateMasterMatch(albumCutLyrics, radioEditPlaybackMs)
        assertEquals("357s album cut on 276s radio edit must be MASTER_MISMATCH", MasterMatchStatus.MASTER_MISMATCH, albumMatch)
        assertFalse("357s album cut must be rejected for 276s radio edit", LyricsAlignmentEngine.isAcceptableMasterMatch(albumCutLyrics, radioEditPlaybackMs))

        // Genuinely matching candidate (e.g. 276s radio edit line sync):
        val radioEditLyrics = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(createLineTimedLine(35_900L, "Cause it's a bitter sweet symphony")),
            provider = LyricsProvider.LRCLIB,
            durationMs = 276_000L,
            trackName = "Bitter Sweet Symphony (Radio Edit)",
            artistName = "The Verve"
        )
        val radioMatch = LyricsAlignmentEngine.evaluateMasterMatch(radioEditLyrics, radioEditPlaybackMs)
        assertEquals("Matching 276s candidate must be EXACT_MATCH", MasterMatchStatus.EXACT_MATCH, radioMatch)
        assertTrue("Matching 276s candidate must be accepted", LyricsAlignmentEngine.isAcceptableMasterMatch(radioEditLyrics, radioEditPlaybackMs))
    }

    @Test
    fun testGenuineExactVideoMatchStillWorks() {
        val videoId = "BddP6PYo2gs"
        val playbackMs = 261_000L

        val unisonExactCandidate = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(15_000L, "Kesariya")),
            provider = LyricsProvider.UNISON,
            durationMs = 268_000L, // Duration metadata differs from audio cut
            isExactVideoMatch = true,
            matchedVideoId = videoId
        )

        val isAcceptable = LyricsAlignmentEngine.isAcceptableMasterMatch(
            lyrics = unisonExactCandidate,
            playbackDurationMs = playbackMs,
            playbackVideoId = videoId
        )
        assertTrue("Genuine Unison exact video candidate must remain acceptable via exact video identity", isAcceptable)
    }

    @Test
    fun testGenericAndNonVideoIdsCannotTriggerExactVideoBypass() {
        val playbackMs = 261_000L
        val mismatchedDurationMs = 268_000L // 7s delta > 3.5s

        // 1. Spotify ID prefixed with "sp_"
        val spotifyIdCandidate = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(15_000L, "Kesariya")),
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = mismatchedDurationMs,
            isExactVideoMatch = true,
            matchedVideoId = "sp_1wyedcs7wgjv0rg7rmmx3o"
        )
        assertFalse(
            "Spotify track ID must NOT trigger exact video bypass",
            LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = spotifyIdCandidate,
                playbackDurationMs = playbackMs,
                playbackVideoId = "sp_1wyedcs7wgjv0rg7rmmx3o"
            )
        )

        // 2. Composite cache key containing "::"
        val compositeKeyCandidate = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(15_000L, "Kesariya")),
            provider = LyricsProvider.UNISON,
            durationMs = mismatchedDurationMs,
            isExactVideoMatch = true,
            matchedVideoId = "kesariya::arijit singh::261"
        )
        assertFalse(
            "Composite cache key must NOT trigger exact video bypass",
            LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = compositeKeyCandidate,
                playbackDurationMs = playbackMs,
                playbackVideoId = "kesariya::arijit singh::261"
            )
        )

        // 3. Provider is not UNISON
        val paxsenixCandidate = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(15_000L, "Kesariya")),
            provider = LyricsProvider.PAXSENIX,
            durationMs = mismatchedDurationMs,
            isExactVideoMatch = true,
            matchedVideoId = "BddP6PYo2gs"
        )
        assertFalse(
            "Non-UNISON provider must NOT trigger exact video bypass",
            LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = paxsenixCandidate,
                playbackDurationMs = playbackMs,
                playbackVideoId = "BddP6PYo2gs"
            )
        )
    }

    @Test
    fun testValidCachedRichSyncRemainsAccepted() = runBlocking {
        val mockDao = mockk<LyricsDao>(relaxed = true)
        val mockClient = mockk<LyricsClient>()

        val rawVideoId = "NJAv_7lHUIU"
        val trackKey = rawVideoId.lowercase()
        val playbackMs = 268_000L

        val validLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                createWordTimedLine(15200L, "Mujhko itna bataye koyi"),
                createWordTimedLine(20000L, "Kaise tujhse dil na lagaye koyi"),
                createWordTimedLine(25000L, "Rabba ne tujhko banane me"),
                createWordTimedLine(30000L, "Kardi hai husn ki khaali tijoriyan")
            ),
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = "Kesariya",
            artistName = "Arijit Singh",
            durationMs = 268_000L,
            leadingSilenceMs = 1200L
        )
        val cachedRichSync = LyricsRepositoryImpl.domainToEntity(trackKey, validLyrics, "Kesariya", "Arijit Singh")

        coEvery { mockDao.getLyrics(trackKey) } returns cachedRichSync

        val repository = LyricsRepositoryImpl(
            lyricsClient = mockClient,
            lyricsDao = mockDao
        )

        val cached = repository.getCachedLyrics(
            title = "Kesariya",
            artist = "Arijit Singh",
            durationSec = 268L,
            videoId = rawVideoId,
            durationMs = playbackMs
        )

        assertNotNull("Valid cached RichSync matching playback master must be returned", cached)
        assertEquals(SyncType.RICHSYNC, cached?.syncType)
        assertEquals(LyricsProvider.BETTER_LYRICS, cached?.provider)
    }

    @Test
    fun testMismatchedCachedLyricsAreNotAcceptedAndEvicted() = runBlocking {
        val mockDao = mockk<LyricsDao>(relaxed = true)
        val mockClient = mockk<LyricsClient>()

        val rawVideoId = "BddP6PYo2gs" // Film cut: 261s
        val trackKey = rawVideoId.lowercase()
        val playbackMs = 261_000L

        // Old cached entry with 268s studio duration
        val mismatchedLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                createWordTimedLine(15200L, "Mujhko itna bataye koyi"),
                createWordTimedLine(20000L, "Kaise tujhse dil na lagaye koyi"),
                createWordTimedLine(25000L, "Rabba ne tujhko banane me"),
                createWordTimedLine(30000L, "Kardi hai husn ki khaali tijoriyan")
            ),
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = "Kesariya",
            artistName = "Arijit Singh",
            durationMs = 268_000L, // Mismatched 7s delta
            leadingSilenceMs = 1200L
        )
        val mismatchedEntity = LyricsRepositoryImpl.domainToEntity(trackKey, mismatchedLyrics, "Kesariya", "Arijit Singh")

        coEvery { mockDao.getLyrics(trackKey) } returns mismatchedEntity

        val repository = LyricsRepositoryImpl(
            lyricsClient = mockClient,
            lyricsDao = mockDao
        )

        val cached = repository.getCachedLyrics(
            title = "Kesariya",
            artist = "Arijit Singh",
            durationSec = 261L,
            videoId = rawVideoId,
            durationMs = playbackMs
        )

        assertNull("Mismatched cached lyrics must NOT be accepted", cached)
        // Verify eviction from Room DB
        coVerify(atLeast = 1) { mockDao.deleteLyrics(trackKey) }
    }

    @Test
    fun testCandidateWithMassiveGapCannotInstantWin() {
        // BetterLyrics candidate has a 260s gap (missing vocoder section)
        val line1 = LyricLine(time = 159_740L, endTime = 163_000L, text = "Tell my mother I'm sorry")
        val line2 = LyricLine(time = 420_500L, endTime = 425_000L, text = "Baby, now I'm ready, moving on")
        val lines = listOf(line1, line2)
        val gap = LyricsClient.maxInternalGapMs(lines)
        assertTrue("Gap should be > 250s", gap > 250_000L)

        val isInstant = LyricsClient.isInstantWinner(
            tier = LyricsClient.TIER_WORD,
            score = 160.0,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.BETTER_LYRICS,
            isExactVideoMatch = false,
            maxGapMs = gap
        )
        assertFalse("Candidate with 260s gap MUST NOT instant-win and cancel other providers", isInstant)

        // Complete candidate without gap CAN instant-win
        val isInstantComplete = LyricsClient.isInstantWinner(
            tier = LyricsClient.TIER_WORD,
            score = 160.0,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.BETTER_LYRICS,
            isExactVideoMatch = false,
            maxGapMs = 5_000L
        )
        assertTrue("Complete candidate with normal gaps can instant-win", isInstantComplete)
    }

    @Test
    fun testCompleteCandidateOutranksIncompleteCandidateWithMassiveVoid() {
        // Even though BetterLyrics normally has higher provider priority than NetEase,
        // a NetEase candidate with full coverage outranks a BetterLyrics candidate missing > 45s of vocals
        val netEaseOutranksIncompleteBetterLyrics = LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD,
            score = 150.0,
            provider = LyricsProvider.NETEASE,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            bestTier = LyricsClient.TIER_WORD,
            bestScore = 155.0,
            bestProvider = LyricsProvider.BETTER_LYRICS,
            bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
            maxGapMs = 12_000L,
            bestMaxGapMs = 260_000L
        )
        assertTrue("Complete candidate MUST outrank incomplete candidate with massive void", netEaseOutranksIncompleteBetterLyrics)
    }

    @Test
    fun testFillLyricsGapsGraftsMissingLinesFromSecondaryCandidate() {
        val incompletePrimary = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                LyricLine(time = 150_000L, endTime = 160_000L, text = "Tell my mother I'm sorry"),
                LyricLine(time = 420_000L, endTime = 425_000L, text = "Baby, now I'm ready, moving on")
            ),
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 467_000L
        )

        val secondaryComplete = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                LyricLine(time = 150_000L, endTime = 160_000L, text = "Tell my mother I'm sorry"),
                LyricLine(time = 330_000L, endTime = 334_000L, text = "I cannot vanish, you will not scare me"),
                LyricLine(time = 335_000L, endTime = 339_000L, text = "Try to get through it, try to push through it"),
                LyricLine(time = 420_000L, endTime = 425_000L, text = "Baby, now I'm ready, moving on")
            ),
            provider = LyricsProvider.NETEASE,
            durationMs = 467_000L
        )

        val filled = LyricsClient.fillLyricsGaps(incompletePrimary, listOf(secondaryComplete))
        assertEquals("Should have grafted 2 missing lines to total 4 lines", 4, filled.lines.size)
        assertEquals("I cannot vanish, you will not scare me", filled.lines[1].text)
        assertEquals("Try to get through it, try to push through it", filled.lines[2].text)
        assertTrue("Lines must be sorted by time", filled.lines.zipWithNext().all { it.first.time <= it.second.time })
    }

    @Test
    fun testLetItHappenVocoderSectionAndIndicatorResolution() {
        // Reproduce exact scenario for Tame Impala - Let It Happen (467s duration)
        // 1. BetterLyrics (Apple Music TTML) transcription drops the vocoder section between 2:39.7 and 7:00.5
        val betterLyricsLines = listOf(
            LyricLine(time = 155_000L, endTime = 159_740L, text = "Tell my mother I'm sorry"),
            LyricLine(time = 420_500L, endTime = 425_000L, text = "Baby, now I'm ready, moving on")
        )
        val betterLyricsData = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = betterLyricsLines,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 467_000L
        )

        // 2. NetEase / LRCLIB has the full 63 lines with the vocoder lyrics (5:29 - 7:00)
        val netEaseLines = listOf(
            LyricLine(time = 155_000L, endTime = 159_740L, text = "Tell my mother I'm sorry"),
            LyricLine(time = 329_000L, endTime = 333_000L, text = "I cannot vanish, you will not scare me"),
            LyricLine(time = 334_000L, endTime = 338_000L, text = "Try to get through it, try to push through it"),
            LyricLine(time = 420_500L, endTime = 425_000L, text = "Baby, now I'm ready, moving on")
        )
        val netEaseData = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = netEaseLines,
            provider = LyricsProvider.NETEASE,
            durationMs = 467_000L
        )

        val blGap = LyricsClient.maxInternalGapMs(betterLyricsLines)
        val neGap = LyricsClient.maxInternalGapMs(netEaseLines)

        assertTrue("BetterLyrics must have a gap > 250s", blGap > 250_000L)
        assertTrue("NetEase must not have a gap > 200s", neGap < 200_000L)

        // BetterLyrics must NOT trigger instant win
        assertFalse(
            "BetterLyrics with 260s missing section must NOT trigger instant win",
            LyricsClient.isInstantWinner(
                tier = LyricsClient.TIER_WORD,
                score = 160.0,
                masterMatch = MasterMatchStatus.EXACT_MATCH,
                provider = LyricsProvider.BETTER_LYRICS,
                maxGapMs = blGap
            )
        )

        // Complete NetEase candidate outranks incomplete BetterLyrics
        assertTrue(
            "Complete NetEase candidate must outrank incomplete BetterLyrics",
            LyricsClient.outranks(
                tier = LyricsClient.TIER_WORD,
                score = 150.0,
                provider = LyricsProvider.NETEASE,
                masterMatch = MasterMatchStatus.EXACT_MATCH,
                bestTier = LyricsClient.TIER_WORD,
                bestScore = 160.0,
                bestProvider = LyricsProvider.BETTER_LYRICS,
                bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
                maxGapMs = neGap,
                bestMaxGapMs = blGap
            )
        )

        // Gap filling fallback: If BetterLyrics was used, it grafts NetEase vocoder lines
        val healed = LyricsClient.fillLyricsGaps(betterLyricsData, listOf(netEaseData))
        assertTrue("Healed lyrics must contain vocoder line", healed.lines.any { it.text.contains("vanish") })
        assertEquals(4, healed.lines.size)
    }

    @Test
    fun testFillLyricsGapsHealsIntroAndOutroVoidsAcrossCatalog() {
        // Primary candidate dropped the first verse (starts at 30s) and outro (ends at 120s of a 180s song)
        val primaryWithMissingIntroAndOutro = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                createWordTimedLine(30_000L, "Main chorus line 1"),
                createWordTimedLine(60_000L, "Main chorus line 2"),
                createWordTimedLine(120_000L, "Bridge line")
            ),
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 180_000L
        )

        // Secondary candidate has the full intro verse and outro
        val secondaryFull = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                createLineTimedLine(5_000L, "Intro line 1"),
                createLineTimedLine(12_000L, "Intro line 2"),
                createLineTimedLine(30_000L, "Main chorus line 1"),
                createLineTimedLine(60_000L, "Main chorus line 2"),
                createLineTimedLine(120_000L, "Bridge line"),
                createLineTimedLine(150_000L, "Outro line 1"),
                createLineTimedLine(165_000L, "Outro line 2")
            ),
            provider = LyricsProvider.LRCLIB,
            durationMs = 180_000L
        )

        val healed = LyricsClient.fillLyricsGaps(primaryWithMissingIntroAndOutro, listOf(secondaryFull))
        // Should have grafted 2 intro lines and 2 outro lines -> total 7 lines
        assertEquals("Total lines must be 7 after healing intro and outro", 7, healed.lines.size)
        assertEquals("First line must be the grafted intro line", "Intro line 1", healed.lines.first().text)
        assertEquals(5_000L, healed.lines.first().time)
        assertEquals("Last line must be the grafted outro line", "Outro line 2", healed.lines.last().text)
        assertEquals(165_000L, healed.lines.last().time)
        assertTrue("Lines must be ordered strictly by timestamp", healed.lines.zipWithNext().all { it.first.time <= it.second.time })
    }

    @Test
    fun testInstantWinnerRejectsCandidateWithVoidGreaterThan18Seconds() {
        // Candidate with 20s gap (>18s) must not instant win and cancel other providers
        val cannotInstantWin = LyricsClient.isInstantWinner(
            tier = LyricsClient.TIER_WORD,
            score = 150.0,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.BETTER_LYRICS,
            maxGapMs = 20_000L
        )
        assertFalse("Candidate with 20s void must not instant win", cannotInstantWin)

        // Candidate with normal 10s gap (<=18s) can instant win
        val canInstantWin = LyricsClient.isInstantWinner(
            tier = LyricsClient.TIER_WORD,
            score = 150.0,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.BETTER_LYRICS,
            maxGapMs = 10_000L
        )
        assertTrue("Complete candidate with normal gap <=18s can instant win", canInstantWin)
    }

    @Test
    fun testNetEaseAndMultiProviderCandidatesAreAcceptable() {
        val playbackMs = 210_000L

        // NetEase candidate with propagated duration
        val netEaseWithDuration = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(createWordTimedLine(10_000L, "Hello")),
            provider = LyricsProvider.NETEASE,
            durationMs = 211_500L // 1.5s delta
        )
        assertTrue(LyricsAlignmentEngine.isAcceptableMasterMatch(netEaseWithDuration, playbackMs))

        // JioSaavn / KuGou candidate without duration metadata (null duration)
        val candidateNullDuration = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                createLineTimedLine(10_000L, "Hello"),
                createLineTimedLine(200_000L, "Outro before track ends")
            ),
            provider = LyricsProvider.JIOSAAVN,
            durationMs = null
        )
        assertTrue("Candidate without duration must be acceptable if vocals fit in audio bounds",
            LyricsAlignmentEngine.isAcceptableMasterMatch(candidateNullDuration, playbackMs))

        // Candidate with 2.5s delta (common on YouTube Music streams)
        val candidate2Point5sDelta = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(createLineTimedLine(10_000L, "Hello")),
            provider = LyricsProvider.KUGOU,
            durationMs = 212_500L // 2.5s delta
        )
        assertTrue("Candidate with 2.5s delta must be acceptable without requiring silence measurement",
            LyricsAlignmentEngine.isAcceptableMasterMatch(candidate2Point5sDelta, playbackMs, audioLeadingSilenceMs = null))
    }

    @Test
    fun testDatabaseCacheNotEvictedOnTemporaryCompatibleOffset() = runBlocking {
        val mockDao = mockk<LyricsDao>(relaxed = true)
        val mockClient = mockk<LyricsClient>()

        val trackKey = "test_song_key"
        val playbackMs = 200_000L

        // Cached lyrics with 202,000ms duration (2s delta -> COMPATIBLE_OFFSET)
        val cachedLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                createWordTimedLine(10_000L, "Hello line 1"),
                createWordTimedLine(15_000L, "Hello line 2"),
                createWordTimedLine(20_000L, "Hello line 3"),
                createWordTimedLine(25_000L, "Hello line 4")
            ),
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = "Test Song",
            artistName = "Test Artist",
            durationMs = 202_000L
        )
        val entity = LyricsRepositoryImpl.domainToEntity(trackKey, cachedLyrics, "Test Song", "Test Artist")
        coEvery { mockDao.getLyrics(trackKey) } returns entity

        val repository = LyricsRepositoryImpl(
            lyricsClient = mockClient,
            lyricsDao = mockDao
        )

        val result = repository.getCachedLyrics(
            title = "Test Song",
            artist = "Test Artist",
            durationSec = 200L,
            videoId = trackKey,
            durationMs = playbackMs
        )

        assertNotNull("Cached lyrics with compatible offset must be returned", result)
        // Must NOT call deleteLyrics on DB for compatible offset
        coVerify(exactly = 0) { mockDao.deleteLyrics(trackKey) }
    }

    @Test
    fun `synced cache is reused across duplicate Spotify and YouTube track IDs`() = runBlocking {
        val mockDao = mockk<LyricsDao>(relaxed = true)
        val mockClient = mockk<LyricsClient>(relaxed = true)
        val youtubeKey = "7j6c9metnm0"

        val plainForYoutube = LyricsData(
            syncType = SyncType.PLAIN,
            lines = listOf(LyricLine(time = 0L, text = "Cigarettes out the window")),
            provider = LyricsProvider.YOUTUBE,
            trackName = "Cigarettes out the Window",
            artistName = "TV Girl"
        )
        val syncedForSpotify = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                createWordTimedLine(8_000L, "Cigarettes out the window"),
                createWordTimedLine(12_000L, "My girl Liddy used to always smoke"),
                createWordTimedLine(16_000L, "Cigarettes when she couldn't sleep"),
                createWordTimedLine(20_000L, "She'd disappear for an hour and a half")
            ),
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = "Cigarettes out the Window",
            artistName = "TV Girl",
            durationMs = 199_000L
        )

        val plainEntity = LyricsRepositoryImpl.domainToEntity(
            youtubeKey,
            plainForYoutube,
            "Cigarettes out the Window",
            "TV Girl"
        )
        val syncedEntity = LyricsRepositoryImpl.domainToEntity(
            "sp_6qeysvyqyusfbzsapbjdho",
            syncedForSpotify,
            "Cigarettes out the Window",
            "TV Girl"
        )
        coEvery { mockDao.getLyrics(youtubeKey) } returns plainEntity
        coEvery {
            mockDao.getBestLyricsByMetadata(
                title = "Cigarettes out the Window",
                artist = "TV Girl",
                durationMs = 198_000L,
                pipelineVersion = LyricsRepositoryImpl.LYRICS_PIPELINE_VERSION,
                durationToleranceMs = any()
            )
        } returns syncedEntity

        val repository = LyricsRepositoryImpl(mockClient, mockDao)
        val result = repository.getCachedLyrics(
            title = "Cigarettes out the Window",
            artist = "TV Girl",
            durationSec = 198L,
            videoId = youtubeKey,
            durationMs = 198_000L
        )

        assertNotNull(result)
        assertEquals(SyncType.RICHSYNC, result?.syncType)
        assertEquals(LyricsProvider.BETTER_LYRICS, result?.provider)
        coVerify {
            mockDao.insertLyrics(match { it.trackId == youtubeKey && it.syncType == SyncType.RICHSYNC.name })
        }
    }
}
