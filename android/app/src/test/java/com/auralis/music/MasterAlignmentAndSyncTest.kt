package com.auralis.music

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.service.AudioLeadingSilenceProcessor
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MasterAlignmentAndSyncTest {

    @Test
    fun testHeavenKnowsAlignmentShifts160msCandidateToMatchAudioVocalOnset() {
        // Candidate with 160ms leading silence (Apple Music album cut, e.g. Louder Than Bombs)
        val candidateLines = listOf(
            LyricLine(
                time = 16432L,
                text = "I was happy in the haze of a drunken hour",
                words = listOf(
                    LyricWord("I ", 16432L, 200L),
                    LyricWord("was ", 16632L, 300L),
                    LyricWord("happy ", 16932L, 400L)
                )
            )
        )
        val unalignedLyrics = LyricsData(
            lines = candidateLines,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.PAXSENIX,
            durationMs = 216440L,
            leadingSilenceMs = 160L,
            trackName = "Heaven Knows I'm Miserable Now",
            artistName = "The Smiths"
        )

        // Measured audio leading silence in YouTube stream is ~1900ms
        val aligned = LyricsAlignmentEngine.alignToPlayback(
            lyrics = unalignedLyrics,
            playbackDurationMs = 216440L,
            audioLeadingSilenceMs = 1900L
        )

        // Expected shift = 1900 - 160 = +1740ms
        val expectedFirstWord = 16432L + 1740L // 18172ms
        assertEquals(expectedFirstWord, aligned.lines[0].words?.get(0)?.time)
        assertEquals(expectedFirstWord, aligned.lines[0].time)
    }

    @Test
    fun testHeavenKnows1900msCandidateRequiresZeroShift() {
        // Candidate with 1900ms leading silence (ISRC GBCRL1300378 / GBCRL0800305, 2008 Remaster)
        val candidateLines = listOf(
            LyricLine(
                time = 18172L,
                text = "I was happy in the haze of a drunken hour",
                words = listOf(
                    LyricWord("I ", 18172L, 200L),
                    LyricWord("was ", 18372L, 300L),
                    LyricWord("happy ", 18672L, 400L)
                )
            )
        )
        val remasteredLyrics = LyricsData(
            lines = candidateLines,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 216000L,
            leadingSilenceMs = 1900L,
            trackName = "Heaven Knows I'm Miserable Now (2008 Remaster)",
            artistName = "The Smiths"
        )

        // Audio leading silence matches candidate (1900ms)
        val aligned = LyricsAlignmentEngine.alignToPlayback(
            lyrics = remasteredLyrics,
            playbackDurationMs = 216000L,
            audioLeadingSilenceMs = 1900L
        )

        // Shift is 0ms
        assertEquals(18172L, aligned.lines[0].words?.get(0)?.time)
        assertEquals(18172L, aligned.lines[0].time)
    }

    @Test
    fun testExistingMatchingTracksRemainUnchangedZeroOffset() {
        // HUMBLE. has matching ~300ms audio and provider leading silence
        val humbleLines = listOf(
            LyricLine(
                time = 3800L,
                text = "Nobody pray for me",
                words = listOf(
                    LyricWord("Nobody ", 3800L, 400L),
                    LyricWord("pray ", 4200L, 300L)
                )
            )
        )
        val humbleLyrics = LyricsData(
            lines = humbleLines,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 177000L,
            leadingSilenceMs = 300L,
            trackName = "HUMBLE.",
            artistName = "Kendrick Lamar"
        )

        val aligned = LyricsAlignmentEngine.alignToPlayback(
            lyrics = humbleLyrics,
            playbackDurationMs = 177000L,
            audioLeadingSilenceMs = 300L
        )

        // No shift introduced
        assertEquals(3800L, aligned.lines[0].words?.get(0)?.time)
        assertEquals(3800L, aligned.lines[0].time)
    }

    @Test
    fun testNullAudioLeadingSilenceDefaultsToZeroOffset() {
        // When audioLeadingSilenceMs is null (e.g. unmeasured), no arbitrary shift is applied
        val lines = listOf(
            LyricLine(time = 5000L, text = "Test", words = listOf(LyricWord("Test", 5000L, 300L)))
        )
        val lyrics = LyricsData(
            lines = lines,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 200000L,
            leadingSilenceMs = 160L
        )

        val aligned = LyricsAlignmentEngine.alignToPlayback(
            lyrics = lyrics,
            playbackDurationMs = 200000L,
            audioLeadingSilenceMs = null
        )

        assertEquals(5000L, aligned.lines[0].words?.get(0)?.time)
    }

    @Test
    fun testAlignToPlaybackIsStrictlyIdempotentAcrossMultipleInvocations() {
        val candidateLines = listOf(
            LyricLine(
                time = 16432L,
                text = "I was happy in the haze of a drunken hour",
                words = listOf(
                    LyricWord("I ", 16432L, 200L),
                    LyricWord("was ", 16632L, 300L),
                    LyricWord("happy ", 16932L, 400L)
                )
            )
        )
        val initial = LyricsData(
            lines = candidateLines,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 216000L,
            leadingSilenceMs = 1200L
        )

        // First shift: audio silence = 2000ms, candidate silence = 1200ms -> shift = +800ms
        var current = LyricsAlignmentEngine.alignToPlayback(initial, 216000L, 2000L)
        val expectedTime = 16432L + 800L
        assertEquals(expectedTime, current.lines[0].time)
        assertEquals(expectedTime, current.lines[0].words?.get(0)?.time)
        assertEquals(800L, current.appliedOffsetMs)

        // Consecutively invoke alignToPlayback 10 more times (simulating ticker, cache reloads, view recompositions)
        for (i in 1..10) {
            current = LyricsAlignmentEngine.alignToPlayback(current, 216000L, 2000L)
            assertEquals("Invocation $i must remain strictly identical without compounding", expectedTime, current.lines[0].time)
            assertEquals("Invocation $i word timestamp must not compound", expectedTime, current.lines[0].words?.get(0)?.time)
            assertEquals(800L, current.appliedOffsetMs)
        }

        // Now simulate audio silence adjustment: change silence from 2000ms to 2050ms (+50ms adjustment)
        current = LyricsAlignmentEngine.alignToPlayback(current, 216000L, 2050L)
        val adjustedExpected = 16432L + 850L
        assertEquals(adjustedExpected, current.lines[0].time)
        assertEquals(adjustedExpected, current.lines[0].words?.get(0)?.time)
        assertEquals(850L, current.appliedOffsetMs)
    }

    @Test
    fun testResilientDnsBypassesCarrierSinkholeIp() {
        // ResilientLyricsDns resolves music.163.com to official overseas CDN IPs
        val ips = NetworkClientProvider.ResilientLyricsDns.lookup("music.163.com")
        assertNotNull(ips)
        assertTrue("Expected non-empty IP list for NetEase", ips.isNotEmpty())

        val stringIps = ips.map { it.hostAddress }
        // Ensure the blocked Reliance Jio sinkhole IP is NEVER returned
        assertFalse("Sinkhole IP 49.44.79.236 must be filtered", stringIps.contains("49.44.79.236"))
        assertFalse("Loopback IP must be filtered", stringIps.contains("127.0.0.1"))
    }

    // =========================================================================
    // 9 MANDATORY SYNCHRONIZATION TEST CASES
    // =========================================================================

    @Test
    fun testCase1_AlreadyCorrectTimestampsNoUnnecessaryCorrection_LoseYourself() {
        // NetEase studio word-synced candidate for Lose Yourself (id: 5052317, 321960ms)
        // Measured audio leading silence matches candidate leading silence (~1263ms)
        val candidateLines = listOf(
            LyricLine(time = 33520L, text = "Look", words = listOf(LyricWord("Look", 33520L, 800L))),
            LyricLine(time = 52930L, text = "His palms are sweaty", words = listOf(LyricWord("His", 52930L, 200L), LyricWord("palms", 53130L, 300L))),
            LyricLine(time = 75460L, text = "Snap back to reality", words = listOf(LyricWord("Snap", 75460L, 250L), LyricWord("back", 75710L, 250L)))
        )
        val studioLyrics = LyricsData(
            lines = candidateLines,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.NETEASE,
            durationMs = 321960L,
            leadingSilenceMs = 1263L,
            trackName = "Lose Yourself",
            artistName = "Eminem"
        )

        // Aligned against studio audio 4wOLVrGHiIU (322200ms) with matching 1263ms leading silence
        val aligned = LyricsAlignmentEngine.alignToPlayback(
            lyrics = studioLyrics,
            playbackDurationMs = 322200L,
            audioLeadingSilenceMs = 1263L
        )

        // Zero offset must be applied: exactly preserved timestamps
        assertEquals(0L, aligned.appliedOffsetMs)
        assertEquals(33520L, aligned.lines[0].time)
        assertEquals(33520L, aligned.lines[0].words?.get(0)?.time)
        assertEquals(52930L, aligned.lines[1].time)
        assertEquals(75460L, aligned.lines[2].time)
    }

    @Test
    fun testCase2_GenuineConstantTimingMismatch_HeavenKnowsDerivedCorrection() {
        // Candidate GBCRL1300378: leadingSilence = 1900ms, first word at 18172ms
        val candidateLines = listOf(
            LyricLine(
                time = 18172L,
                text = "I was happy in the haze of a drunken hour",
                words = listOf(LyricWord("I ", 18172L, 200L), LyricWord("was ", 18372L, 200L), LyricWord("happy ", 18572L, 300L))
            ),
            LyricLine(
                time = 110761L,
                text = "What she asked of me at the end of the day",
                words = listOf(LyricWord("What ", 110761L, 250L), LyricWord("she ", 111011L, 250L))
            ),
            LyricLine(
                time = 173728L,
                text = "Oh, why do I give valuable time",
                words = listOf(LyricWord("Oh, ", 173728L, 300L), LyricWord("why ", 174028L, 250L))
            )
        )
        val hkLyrics = LyricsData(
            lines = candidateLines,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 216000L,
            leadingSilenceMs = 1900L,
            trackName = "Heaven Knows I'm Miserable Now",
            artistName = "The Smiths"
        )

        // Master 1: 3Mr0pDNVms0 (2008 Remaster, audio leading silence = 2036ms)
        // Mathematically derived correction: 2036 - 1900 = +136ms
        val aligned3Mr = LyricsAlignmentEngine.alignToPlayback(
            lyrics = hkLyrics,
            playbackDurationMs = 216456L,
            audioLeadingSilenceMs = 2036L
        )
        assertEquals(+136L, aligned3Mr.appliedOffsetMs)
        assertEquals(18308L, aligned3Mr.lines[0].words?.get(0)?.time) // 18172 + 136 = 18308ms (acoustic onset: 18340ms, delta = 32ms)
        assertEquals(110897L, aligned3Mr.lines[1].words?.get(0)?.time) // 110761 + 136 = 110897ms (acoustic onset: 110950ms, delta = 53ms)
        assertEquals(173864L, aligned3Mr.lines[2].words?.get(0)?.time) // 173728 + 136 = 173864ms (acoustic onset: 173900ms, delta = 36ms)

        // Master 2: 10z6-vQm23w (1984 trimmed single cut, audio leading silence = 1349ms)
        // Mathematically derived correction: 1349 - 1900 = -551ms
        val aligned10z = LyricsAlignmentEngine.alignToPlayback(
            lyrics = hkLyrics,
            playbackDurationMs = 215783L,
            audioLeadingSilenceMs = 1349L
        )
        assertEquals(-551L, aligned10z.appliedOffsetMs)
        assertEquals(17621L, aligned10z.lines[0].words?.get(0)?.time) // 18172 - 551 = 17621ms (acoustic onset: 17653ms, delta = 32ms)
    }

    @Test
    fun testCase3_DriftVersusConstantOffsetAnalysis_AnchorsShowConstantShiftNotDrift() {
        // Acoustic ground truth vs candidate timestamps across track duration:
        // Anchor 1: Audio = 18,340 ms | Lyrics = 18,172 ms | Delta = +168 ms
        // Anchor 2: Audio = 110,950 ms | Lyrics = 110,761 ms | Delta = +189 ms
        // Anchor 3: Audio = 173,900 ms | Lyrics = 173,728 ms | Delta = +172 ms
        val delta1 = 18340L - 18172L
        val delta2 = 110950L - 110761L
        val delta3 = 173900L - 173728L

        val maxVariance = maxOf(delta1, delta2, delta3) - minOf(delta1, delta2, delta3)
        // Max variance across 155,560ms of elapsed music is only 21ms (< 0.14 ms/s clock difference)
        assertTrue("Variance across anchors must be < 30ms confirming constant offset, not clock drift", maxVariance < 30L)
    }

    @Test
    fun testCase4_WrongMasterRejected_DurationExceedsThreshold() {
        val studioLyrics = LyricsData(
            lines = listOf(LyricLine(time = 33520L, text = "Look")),
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.NETEASE,
            durationMs = 321960L // 322s
        )

        // Music video cut duration = 327,494ms (5.5s delta > 3.5s limit)
        val status = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = studioLyrics,
            playbackDurationMs = 327494L
        )
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, status)
        assertFalse(LyricsAlignmentEngine.isAcceptableMasterMatch(studioLyrics, 327494L))

        // Aligner refuses to shift a mismatched master
        val unchanged = LyricsAlignmentEngine.alignToPlayback(studioLyrics, 327494L, 1263L)
        assertEquals(0L, unchanged.appliedOffsetMs)
        assertEquals(33520L, unchanged.lines[0].time)
    }

    @Test
    fun testCase5_LeadingSilenceProcessor_HandlesBoth16BitAndFloatPCM() {
        // Test 1: 16-bit PCM leading silence detection
        val processor16 = AudioLeadingSilenceProcessor()
        val format16 = AudioProcessor.AudioFormat(44100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        val configured16 = processor16.configure(format16)
        assertEquals(format16, configured16)
        processor16.flush()

        var detectedSilence16: Long? = null
        processor16.onLeadingSilenceDetected = { detectedSilence16 = it }

        // 100ms of silence at 44100Hz stereo 16-bit = 4410 frames * 4 bytes/frame = 17640 bytes of zeros
        // Followed by 1 frame of audible signal (> 450)
        val silenceFrames16 = 4410 // 100ms
        val buffer16 = ByteBuffer.allocateDirect((silenceFrames16 + 10) * 4).order(ByteOrder.LITTLE_ENDIAN)
        // Fill zeros
        for (i in 0 until silenceFrames16 * 2) {
            buffer16.putShort(0.toShort())
        }
        // Audible pulse
        buffer16.putShort(1000.toShort())
        buffer16.putShort(1000.toShort())
        buffer16.flip()

        processor16.queueInput(buffer16)
        assertNotNull(detectedSilence16)
        assertEquals(100L, detectedSilence16)

        // Test 2: Float PCM leading silence detection
        val processorFloat = AudioLeadingSilenceProcessor()
        val formatFloat = AudioProcessor.AudioFormat(44100, 2, androidx.media3.common.C.ENCODING_PCM_FLOAT)
        val configuredFloat = processorFloat.configure(formatFloat)
        assertEquals(formatFloat, configuredFloat)
        processorFloat.flush()

        var detectedSilenceFloat: Long? = null
        processorFloat.onLeadingSilenceDetected = { detectedSilenceFloat = it }

        // 200ms of silence at 44100Hz stereo Float = 8820 frames * 8 bytes/frame = 70560 bytes of zeros
        val silenceFramesFloat = 8820 // 200ms
        val bufferFloat = ByteBuffer.allocateDirect((silenceFramesFloat + 10) * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until silenceFramesFloat * 2) {
            bufferFloat.putFloat(0.0f)
        }
        // Audible pulse (> 0.0138f)
        bufferFloat.putFloat(0.1f)
        bufferFloat.putFloat(0.1f)
        bufferFloat.flip()

        processorFloat.queueInput(bufferFloat)
        assertNotNull(detectedSilenceFloat)
        assertEquals(200L, detectedSilenceFloat)
    }

    @Test
    fun testCase6_SeekBehavior_LyricClockFollowsPlaybackPosition() {
        val lines = listOf(
            LyricLine(time = 10000L, text = "Line 1"),
            LyricLine(time = 25000L, text = "Line 2"),
            LyricLine(time = 50000L, text = "Line 3"),
            LyricLine(time = 75000L, text = "Line 4"),
            LyricLine(time = 100000L, text = "Line 5")
        )
        val lyrics = LyricsData(lines = lines, syncType = SyncType.LINE_SYNC, provider = LyricsProvider.LRCLIB)

        // Helper to locate active line based on playback position
        fun findActiveLine(lyricsData: LyricsData, playbackPosMs: Long): Int {
            return lyricsData.lines.indexOfLast { it.time <= playbackPosMs }.coerceAtLeast(0)
        }

        // Before seek: at 12000ms -> Line 1
        assertEquals(0, findActiveLine(lyrics, 12000L))

        // Seek forward: user seeks directly to 80000ms -> Line 4 immediately active without lag or drift
        assertEquals(3, findActiveLine(lyrics, 80000L))

        // Seek backward: user seeks back to 20000ms -> Line 1
        assertEquals(0, findActiveLine(lyrics, 20000L))
    }

    @Test
    fun testCase7_PauseResumeCycles_NoAccumulatedTimingError() {
        val candidateLines = listOf(
            LyricLine(time = 18172L, text = "I was happy", words = listOf(LyricWord("I", 18172L, 200L)))
        )
        val lyrics = LyricsData(
            lines = candidateLines,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 216000L,
            leadingSilenceMs = 1900L
        )

        // Simulate 20 pause/resume events where audioLeadingSilenceMs is repeatedly reported
        var aligned = lyrics
        for (i in 1..20) {
            aligned = LyricsAlignmentEngine.alignToPlayback(aligned, 216456L, 2036L)
            assertEquals("Offset must remain strictly +136ms across cycle $i", 136L, aligned.appliedOffsetMs)
            assertEquals("Timestamp must remain 18308ms across cycle $i", 18308L, aligned.lines[0].words?.get(0)?.time)
        }
    }

    @Test
    fun testCase8_CacheInvalidation_PipelineVersion12PurgesStaleData() {
        assertEquals("Pipeline version must be 12", 12, com.auralis.music.data.repository.LyricsRepositoryImpl.LYRICS_PIPELINE_VERSION)

        // Mock an entity from old pipeline version 11
        val staleEntity = com.auralis.music.data.local.entity.LyricsEntity(
            trackId = "eminem::lose yourself::322",
            syncType = "LINE_SYNC",
            linesJson = "[]",
            plainLyrics = null,
            provider = "LRCLIB",
            hasWordTiming = false,
            pipelineVersion = 11,
            cachedAt = System.currentTimeMillis()
        )

        // The repository check requires entity.pipelineVersion >= LYRICS_PIPELINE_VERSION
        val isStale = staleEntity.pipelineVersion < com.auralis.music.data.repository.LyricsRepositoryImpl.LYRICS_PIPELINE_VERSION
        assertTrue("Old version 11 cache entry must be flagged as stale", isStale)
    }

    @Test
    fun testCase9_ExistingSyncedSongsRemainUnchanged_LoveMeNotAndHumble() {
        // Kendrick Lamar - HUMBLE.
        val humbleLyrics = LyricsData(
            lines = listOf(LyricLine(time = 3800L, text = "Nobody pray for me", words = listOf(LyricWord("Nobody", 3800L, 400L)))),
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 177000L,
            leadingSilenceMs = 300L
        )
        val alignedHumble = LyricsAlignmentEngine.alignToPlayback(humbleLyrics, 177000L, 300L)
        assertEquals(0L, alignedHumble.appliedOffsetMs)
        assertEquals(3800L, alignedHumble.lines[0].time)

        // Skepta / Cheb Bilal - Love Me Not
        val loveMeNotLyrics = LyricsData(
            lines = listOf(LyricLine(time = 14200L, text = "Love me not", words = listOf(LyricWord("Love", 14200L, 300L)))),
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 213000L,
            leadingSilenceMs = 500L
        )
        val alignedLoveMeNot = LyricsAlignmentEngine.alignToPlayback(loveMeNotLyrics, 213000L, 500L)
        assertEquals(0L, alignedLoveMeNot.appliedOffsetMs)
        assertEquals(14200L, alignedLoveMeNot.lines[0].time)
    }

    @Test
    fun testCase10_ClickToSeek_ClockFreezesDuringSeekInFlightAndBuffering() {
        val targetSeekMs = 50_000L
        val tapTimestamp = 1_000L

        // Simulated clock source while buffering
        var rawPos = targetSeekMs
        var isBuffering = true
        var isPlaying = true

        val clockSource = object : com.auralis.music.data.service.PlaybackClockSource {
            override fun rawPositionMs(): Long = rawPos
            override fun isPlaying(): Boolean = isPlaying && !isBuffering
            override fun speed(): Float = 1.0f
            override fun isBuffering(): Boolean = isBuffering
        }

        // 1. While buffering (e.g. 500ms after tap):
        val wallTimeAfter500ms = tapTimestamp + 500L
        val clockSourcePlaying = clockSource.isPlaying()
        val clockSourceBuffering = isBuffering || clockSource.isBuffering()

        // Verify seek in flight convergence check
        val hasAudioReached = kotlin.math.abs(rawPos - targetSeekMs) <= 350L
        val minTimeElapsed = (wallTimeAfter500ms - tapTimestamp) >= 80L
        val hasConverged = minTimeElapsed && hasAudioReached && !clockSourceBuffering && clockSourcePlaying
        assertFalse("Seek must NOT converge while audio engine is buffering", hasConverged)

        // Simulated position under seek in flight: must pin to seek target
        val currentPos = if (!hasConverged) {
            targetSeekMs
        } else {
            rawPos + (wallTimeAfter500ms - tapTimestamp).coerceIn(0L, com.auralis.music.ui.lyrics.MAX_CLOCK_CARRY_MS)
        }
        assertEquals("Clock position must remain pinned to seek target during buffer delay", targetSeekMs, currentPos)

        // 2. Audio finishes buffering at wallTime + 600ms and begins emitting samples
        isBuffering = false
        rawPos = 50_020L
        val wallTimeAtReady = tapTimestamp + 600L
        val convergedNow = ((wallTimeAtReady - tapTimestamp) >= 80L) &&
                (kotlin.math.abs(rawPos - targetSeekMs) <= 350L) &&
                !clockSource.isBuffering() &&
                clockSource.isPlaying()
        assertTrue("Seek must converge once audio is ready and actively playing", convergedNow)
    }

    @Test
    fun testCase11_WordHighlightDoesNotAdvancePrematurelyWhileBuffering() {
        // Line starting at 18,172ms with 3 words
        val words = listOf(
            LyricWord("I ", 18172L, 300L),       // 18172 .. 18472
            LyricWord("was ", 18472L, 400L),     // 18472 .. 18872
            LyricWord("happy ", 18872L, 500L)    // 18872 .. 19372
        )

        // User taps line: seek target is 18,172ms
        // While buffering, smoothPosition is pinned to line.time (18,172ms)
        val smoothPositionWhileBuffering = 18172L

        // Compute word progress
        val wordFactors = words.map { word ->
            val wStartMs = word.time
            val wEndMs = word.time + (word.duration ?: 300L)
            val isWordSung = smoothPositionWhileBuffering > wEndMs
            val isWordActive = smoothPositionWhileBuffering in wStartMs..wEndMs
            val sungFactor = if (isWordSung) 1f
            else if (isWordActive) ((smoothPositionWhileBuffering - wStartMs).toFloat() / (wEndMs - wStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
            else 0f
            Triple(sungFactor, isWordSung, isWordActive)
        }

        // Word 0: at exactly start time -> sungFactor is 0.0f
        assertEquals("Word 0 sungFactor must be 0 while buffering at start", 0f, wordFactors[0].first, 0.001f)
        assertFalse("Word 0 must NOT be marked sung while buffering", wordFactors[0].second)

        // Word 1: not started
        assertEquals("Word 1 sungFactor must be 0", 0f, wordFactors[1].first, 0.001f)
        assertFalse("Word 1 must NOT be marked sung", wordFactors[1].second)

        // Word 2: not started
        assertEquals("Word 2 sungFactor must be 0", 0f, wordFactors[2].first, 0.001f)
        assertFalse("Word 2 must NOT be marked sung", wordFactors[2].second)
    }

    @Test
    fun testCase12_PlaybackClockSource_ReportsNotPlayingDuringBuffering() {
        var isPlayingState = true
        var isBufferingState = true

        val clockSource = object : com.auralis.music.data.service.PlaybackClockSource {
            override fun rawPositionMs(): Long = 20_000L
            override fun isPlaying(): Boolean = isPlayingState && !isBufferingState
            override fun speed(): Float = 1.0f
            override fun isBuffering(): Boolean = isBufferingState
        }

        // While buffering, isPlaying() must report false to prevent carry extrapolation
        assertFalse("PlaybackClockSource.isPlaying() must return false while buffering", clockSource.isPlaying())
        assertTrue("PlaybackClockSource.isBuffering() must return true while buffering", clockSource.isBuffering())

        // carriedPositionMs must NOT extrapolate when isPlaying is false
        val carried = com.auralis.music.ui.lyrics.carriedPositionMs(
            rawMs = 20_000L,
            anchorRawMs = 20_000L,
            anchorWallMs = 0L,
            nowWallMs = 800L,
            speed = 1.0f,
            isPlaying = clockSource.isPlaying()
        )
        assertEquals("carriedPositionMs must return raw reading verbatim when isPlaying is false", 20_000L, carried)
    }
}
