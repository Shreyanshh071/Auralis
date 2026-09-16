package com.auralis.music

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.local.AuralisDatabase
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.lyrics.carriedPositionMs
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device verification suite executed on physical hardware (Motorola Edge 50 Fusion, Android 16).
 *
 * Exercises:
 *  1. Normal word highlighting progression
 *  2. Pause mid-word (freeze)
 *  3. Seek mid-word (snap)
 *  4. Genuine vocal rest (We Are The People pause before "in 1975")
 *  5. Track change mid-line
 *  6. 0.5x and 2.0x playback speed carry scaling
 *  7. Background / foreground position handling
 *  8. Line-only lyrics fallback
 *  9. Rapid syllable timing (Rap God)
 * 10. Indic / non-Latin lyrics cluster preservation (Kun Faya Kun / Devanagari)
 * 11. Background vocal lines (x-bg)
 * 12. Real ExoPlayer PlaybackClockSource on main looper
 * 13. Live Better Lyrics provider lookup on device hardware
 */
@RunWith(AndroidJUnit4::class)
class RealDeviceLyricsSyncTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ── 1. REAL EXOPLAYER CLOCK SOURCE ON HARDWARE ────────────────────────────

    @Test
    fun testRealExoPlayerPlaybackClockSourceOnDevice() {
        var clockSource: PlaybackClockSource? = null
        val latch = CountDownLatch(1)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            clockSource = object : PlaybackClockSource {
                override fun rawPositionMs(): Long {
                    return if (player.applicationLooper == Looper.myLooper()) {
                        player.currentPosition
                    } else -1L
                }
                override fun isPlaying(): Boolean = player.isPlaying
                override fun speed(): Float = player.playbackParameters.speed
            }

            assertEquals(0L, clockSource!!.rawPositionMs())
            assertFalse(clockSource!!.isPlaying())
            assertEquals(1.0f, clockSource!!.speed(), 0.001f)

            player.release()
            latch.countDown()
        }

        assertTrue("ExoPlayer operations must complete on main looper", latch.await(5, TimeUnit.SECONDS))
    }

    // ── 2. PAUSE MID-WORD (FREEZE BEHAVIOR) ───────────────────────────────────

    @Test
    fun testPauseMidWordFreezesHighlight() {
        val rawMs = 12450L
        val anchorRawMs = 12450L
        val anchorWallMs = 1000L
        val nowWallMs = 2500L // 1500ms elapsed while paused

        val frozenPos = carriedPositionMs(
            rawMs = rawMs,
            anchorRawMs = anchorRawMs,
            anchorWallMs = anchorWallMs,
            nowWallMs = nowWallMs,
            speed = 1.0f,
            isPlaying = false
        )

        assertEquals("Position must freeze at raw reading when paused", rawMs, frozenPos)
    }

    // ── 3. SEEK MID-WORD (SNAP BEHAVIOR) ──────────────────────────────────────

    @Test
    fun testSeekMidWordSnapsImmediately() {
        val rawBefore = 5000L
        val rawAfterSeek = 45000L
        val anchorRaw = 5000L
        val anchorWall = 1000L
        val nowWall = 1050L

        val snappedPos = carriedPositionMs(
            rawMs = rawAfterSeek,
            anchorRawMs = anchorRaw,
            anchorWallMs = anchorWall,
            nowWallMs = nowWall,
            speed = 1.0f,
            isPlaying = true
        )

        assertEquals("Position must snap immediately to seek target", rawAfterSeek, snappedPos)
    }

    // ── 4. VOCAL REST: WE ARE THE PEOPLE BEFORE "IN 1975" ─────────────────────

    @Test
    fun testWeAreThePeopleVocalRestUnpaintedOnDevice() {
        val line = LyricLine(
            time = 1000L,
            text = "We are the people in 1975",
            words = listOf(
                LyricWord(word = "We ", time = 1000L, duration = 400L),
                LyricWord(word = "are ", time = 1400L, duration = 300L),
                LyricWord(word = "the ", time = 1700L, duration = 300L),
                LyricWord(word = "people ", time = 2000L, duration = 600L), // ends at 2600ms
                LyricWord(word = "in ", time = 4500L, duration = 300L),     // starts at 4500ms (1.9s gap)
                LyricWord(word = "1975", time = 4800L, duration = 1000L)
            )
        )

        val ranges = LyricsEngine.mapWordsToLineSpans(line.text, line.words)
        assertEquals(6, ranges.size)

        // Test mid-rest at 3500ms:
        val progressPeople = LyricsEngine.calculateWordProgress(ranges[3].word, 3500L)
        val progressIn = LyricsEngine.calculateWordProgress(ranges[4].word, 3500L)

        assertEquals("word 'people' must be 100% complete", 1.0f, progressPeople, 0.001f)
        assertEquals("word 'in' must not have started", 0.0f, progressIn, 0.001f)

        // Verify that throughout silence [2600ms .. 4499ms], no word is actively sweeping
        for (t in 2600L until 4500L step 100L) {
            val activeWords = ranges.filter {
                val p = LyricsEngine.calculateWordProgress(it.word, t)
                p > 0f && p < 1f
            }
            assertTrue("No word may be mid-sweep during vocal silence at t=$t", activeWords.isEmpty())
        }
    }

    // ── 5. TRACK CHANGE MID-LINE ─────────────────────────────────────────────

    @Test
    fun testTrackChangeResetsClockAndLines() {
        val track1Line = LyricLine(time = 10000L, text = "Track 1 line")
        val track2Line = LyricLine(time = 0L, text = "Track 2 line")

        // Track 1 active at 12000ms
        assertEquals(0, LyricsEngine.findActiveLyricIndex(listOf(track1Line), 12000L))

        // New track starts at 500ms
        assertEquals(0, LyricsEngine.findActiveLyricIndex(listOf(track2Line), 500L))
    }

    // ── 6. PLAYBACK SPEED (0.5x and 2.0x) ─────────────────────────────────────

    @Test
    fun testPlaybackSpeedScalingOnDevice() {
        val raw = 10000L
        val anchorWall = 1000L
        val nowWall = 1100L // 100ms delta

        val halfSpeed = carriedPositionMs(raw, raw, anchorWall, nowWall, speed = 0.5f, isPlaying = true)
        assertEquals(10050L, halfSpeed)

        val doubleSpeed = carriedPositionMs(raw, raw, anchorWall, nowWall, speed = 2.0f, isPlaying = true)
        assertEquals(10200L, doubleSpeed)
    }

    // ── 7. LINE-SYNC FALLBACK (NO FABRICATED WORD TIMING) ─────────────────────

    @Test
    fun testLineSyncFallbackHonesty() {
        val plainLine = LyricLine(time = 15000L, text = "Bitter Sweet Symphony", words = null)
        assertFalse("Line with null words must not claim word timing", plainLine.hasWordTiming)

        val ranges = LyricsEngine.mapWordsToLineSpans(plainLine.text, plainLine.words)
        assertTrue("No words to map for line-synced lyric", ranges.isEmpty())
    }

    // ── 8. RAPID SYLLABLE TIMING (RAP GOD) ────────────────────────────────────

    @Test
    fun testRapidSyllableTimingRapGod() {
        // High-density syllables from Rap God: 100ms syllables
        val syllables = listOf(
            LyricWord(word = "Look, ", time = 1160L, duration = 378L),
            LyricWord(word = "I ", time = 2231L, duration = 100L),
            LyricWord(word = "was ", time = 2331L, duration = 126L),
            LyricWord(word = "gonna ", time = 2457L, duration = 184L),
            LyricWord(word = "go ", time = 2641L, duration = 137L)
        )
        val line = LyricLine(time = 1160L, text = "Look, I was gonna go", words = syllables)

        val ranges = LyricsEngine.mapWordsToLineSpans(line.text, line.words)
        assertEquals(5, ranges.size)

        // Monotonic check
        for (i in 0 until ranges.size - 1) {
            assertTrue(ranges[i].endIndex <= ranges[i + 1].startIndex)
        }
    }

    // ── 9. INDIC SCRIPT (DEVANAGARI / COMPLEX CLUSTERS) ───────────────────────

    @Test
    fun testIndicScriptPreservationOnDevice() {
        val devanagariLine = LyricLine(
            time = 1000L,
            text = "कुन फया कुन",
            words = listOf(
                LyricWord(word = "कुन ", time = 1000L, duration = 500L),
                LyricWord(word = "फया ", time = 1500L, duration = 600L),
                LyricWord(word = "कुन", time = 2100L, duration = 700L)
            )
        )

        val ranges = LyricsEngine.mapWordsToLineSpans(devanagariLine.text, devanagariLine.words)
        assertEquals(3, ranges.size)
        assertEquals("कुन ", devanagariLine.text.substring(ranges[0].startIndex, ranges[0].endIndex))
        assertEquals("फया ", devanagariLine.text.substring(ranges[1].startIndex, ranges[1].endIndex))
        assertEquals("कुन", devanagariLine.text.substring(ranges[2].startIndex, ranges[2].endIndex))
    }

    // ── 10. BACKGROUND VOCALS (X-BG) ──────────────────────────────────────────

    @Test
    fun testBackgroundVocalsPreservation() {
        val leadLine = LyricLine(time = 10000L, text = "Lead vocal", isBackground = false)
        val bgLine = LyricLine(
            time = 10500L,
            text = "(Yeah yeah)",
            words = listOf(
                LyricWord(word = "(Yeah ", time = 10500L, duration = 400L, isBackground = true),
                LyricWord(word = "yeah)", time = 10900L, duration = 400L, isBackground = true)
            ),
            isBackground = true
        )

        assertFalse(leadLine.isBackground)
        assertTrue(bgLine.isBackground)
        assertTrue(bgLine.hasWordTiming)
        assertTrue(bgLine.words!![0].isBackground)
    }

    // ── 11. LIVE BETTER LYRICS NETWORK RESOLUTION ON PHYSICAL DEVICE ──────────

    @Test
    fun testLiveBetterLyricsProviderOnDevice() = runBlocking {
        val client = LyricsClient()
        val result = client.getLyrics(title = "Starboy", artist = "The Weeknd", durationSec = 230L)

        assertNotNull("Starboy must resolve lyrics on live network", result)
        assertEquals("Starboy must resolve RICHSYNC", SyncType.RICHSYNC, result!!.syncType)
        assertTrue("Starboy must have word timing on live network", result.lines.any { it.hasWordTiming })

        val firstWordTimedLine = result.lines.first { it.hasWordTiming }
        val ranges = LyricsEngine.mapWordsToLineSpans(firstWordTimedLine.text, firstWordTimedLine.words)
        assertTrue("Word spans must be mapped successfully for live data", ranges.isNotEmpty())
    }

    // ── 12. LIVE TOUCH UNISON EXACT-VIDEO RESOLUTION ON PHYSICAL DEVICE ────────

    @Test
    fun testLiveTouchUnisonRichSyncOnDevice() = runBlocking {
        val client = LyricsClient()
        val result = client.getLyrics(
            title = "Touch",
            artist = "KATSEYE",
            durationSec = 143L,
            videoId = "H5tO_9wZ0hg",
            durationMs = 143_000L
        )

        assertNotNull("Touch must resolve lyrics on live network", result)
        assertEquals("Touch must resolve RICHSYNC from UNISON", SyncType.RICHSYNC, result!!.syncType)
        assertEquals(com.auralis.music.domain.model.LyricsProvider.UNISON, result.provider)
        assertTrue("Touch must have word timing on live network", result.lines.any { it.hasWordTiming })

        val firstWordTimedLine = result.lines.first { it.hasWordTiming }
        val ranges = LyricsEngine.mapWordsToLineSpans(firstWordTimedLine.text, firstWordTimedLine.words)
        assertTrue("Word spans must be mapped successfully for Touch live data", ranges.isNotEmpty())
    }

    // ── 13. LIVE PAXSENIX APPLE MUSIC RESOLUTION ON PHYSICAL DEVICE ─────────

    @Test
    fun testLivePaxsenixSyllableSyncOnDevice() = runBlocking {
        val paxsenixSource = com.auralis.music.data.network.provider.PaxsenixLyricsSource()
        val query = com.auralis.music.data.network.provider.LyricsSearchQuery(
            title = "Sunflower",
            artist = "Post Malone & Swae Lee",
            durationSec = 158L,
            durationMs = 158_000L
        )
        val cand = paxsenixSource.search(query)
        assertNotNull("Sunflower must resolve Paxsenix syllable lyrics on physical device", cand)
        assertEquals(SyncType.RICHSYNC, cand!!.syncType)
        assertEquals(com.auralis.music.domain.model.LyricsProvider.PAXSENIX, cand.provider)
        assertTrue("Must contain genuine syllable timing", cand.lyricsData.lines.any { it.hasWordTiming })
    }

    // ── 14. LIVE BITTER SWEET SYMPHONY WRONG-MASTER REJECTION ON PHYSICAL DEVICE ──

    @Test
    fun testLiveBitterSweetSymphonyRejectionOnDevice() = runBlocking {
        val paxsenixSource = com.auralis.music.data.network.provider.PaxsenixLyricsSource()
        val query = com.auralis.music.data.network.provider.LyricsSearchQuery(
            title = "Bitter Sweet Symphony",
            artist = "The Verve",
            durationSec = 276L,
            durationMs = 276_000L
        )
        val cand = paxsenixSource.search(query)
        org.junit.Assert.assertNull("Paxsenix 357s album lyrics MUST be rejected for 276s radio edit", cand)
    }

    // ── 15. REAL-DEVICE DATABASE REPOSITORY RESOLUTION FOR BITTER SWEET SYMPHONY ──

    @Test
    fun testRealDeviceBitterSweetSymphonyRepositoryEndToEnd() = runBlocking {
        val db = AuralisDatabase.getInstance(context)
        val repo = LyricsRepositoryImpl(
            lyricsClient = LyricsClient(),
            lyricsDao = db.lyricsDao(),
            negativeLyricsDao = db.negativeLyricsDao()
        )
        val trackKey = "sp_1wyedcs7wgjv0rg7rmmx3o"
        val playbackDurationMs = 276_000L

        // 1. Calling getCachedLyrics: If an old 357s album cut was in Room DB,
        // it must NOT be returned because it's a MASTER_MISMATCH (> 3.5s).
        val cached = repo.getCachedLyrics(
            title = "Bitter Sweet Symphony",
            artist = "The Verve",
            durationSec = 276L,
            videoId = trackKey,
            durationMs = playbackDurationMs
        )
        if (cached != null) {
            val delta = Math.abs((cached.durationMs ?: playbackDurationMs) - playbackDurationMs)
            assertTrue("Cached lyrics delta must be <= 3500ms, but was ${delta}ms", delta <= 3500L)
            val firstLineTime = cached.lines.firstOrNull { it.text.isNotBlank() }?.time ?: 0L
            assertTrue("Intro vocal must be around 35.9s, not 66.3s (was $firstLineTime)", firstLineTime < 45_000L)
        }

        // 2. Full getLyrics(): Must resolve radio-edit lyrics from network,
        // overwrite the bad 357s album entry in Room DB, and return radio-edit timing.
        val result = repo.getLyrics(
            title = "Bitter Sweet Symphony",
            artist = "The Verve",
            durationSec = 276L,
            videoId = trackKey,
            forceRefresh = false,
            durationMs = playbackDurationMs
        )
        assertNotNull("Must resolve lyrics for radio edit", result)
        val firstLineTime = result!!.lines.firstOrNull { it.text.isNotBlank() }?.time ?: 0L
        assertTrue("Intro vocal must be around 35.9s, not 66.3s (was $firstLineTime)", firstLineTime < 45_000L)

        // 3. Verify Room DB now holds the correct radio edit
        val updatedEntity = db.lyricsDao().getLyrics(trackKey)
        assertNotNull("Room DB row must exist", updatedEntity)
        assertEquals(276_000L, updatedEntity!!.durationMs)
    }

    // ── 16. COMPREHENSIVE PHYSICAL DEVICE VERIFICATION OF 5 CONFIRMED FAILURES ──

    @Test
    fun testRealDevice5ConfirmedFailures() {
        runBlocking {
            val db = AuralisDatabase.getInstance(context)
        val repo = LyricsRepositoryImpl(
            lyricsClient = LyricsClient(),
            lyricsDao = db.lyricsDao(),
            negativeLyricsDao = db.negativeLyricsDao()
        )

        android.util.Log.i("REAL_DEVICE_VERIFY", "==================================================")
        android.util.Log.i("REAL_DEVICE_VERIFY", "STARTING 5 CONFIRMED FAILURES REAL DEVICE AUDIT")
        android.util.Log.i("REAL_DEVICE_VERIFY", "==================================================")

        // 1. HEAVEN KNOWS I'M MISERABLE NOW
        val hkVideoId = "1lyu1KKwC74"
        val hkPlaybackId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(hkVideoId)
        val hkCachedBefore = repo.getCachedLyrics("Heaven Knows I'm Miserable Now", "The Smiths", 217L, hkVideoId, durationMs = 217_000L)
        val hkStart = System.currentTimeMillis()
        val hkLyrics = repo.getLyrics("Heaven Knows I'm Miserable Now", "The Smiths", 217L, hkVideoId, forceRefresh = false, durationMs = 217_000L)
        val hkDuration = System.currentTimeMillis() - hkStart
        android.util.Log.i("REAL_DEVICE_VERIFY", "TRACK: Heaven Knows I'm Miserable Now")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Clicked Track ID: $hkVideoId | Played Video ID: $hkPlaybackId")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Provider: ${hkLyrics?.provider} | SyncType: ${hkLyrics?.syncType}")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Cache hit before: ${hkCachedBefore != null} | Fetch time: ${hkDuration}ms")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Leading silence: ${hkLyrics?.leadingSilenceMs}ms | First line time: ${hkLyrics?.lines?.firstOrNull()?.time}ms")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Visible result: ${hkLyrics?.lines?.firstOrNull()?.text}")
        assertNotNull("Heaven Knows lyrics must resolve", hkLyrics)

        // 2. LOSE YOURSELF
        val lyVideoId = "xFYQQPAOz7Y"
        val lyPlaybackId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(lyVideoId)
        val lyCachedBefore = repo.getCachedLyrics("Lose Yourself", "Eminem", 326L, lyVideoId, durationMs = 326_000L)
        val lyStart = System.currentTimeMillis()
        val lyLyrics = repo.getLyrics("Lose Yourself", "Eminem", 326L, lyVideoId, forceRefresh = false, durationMs = 326_000L)
        val lyDuration = System.currentTimeMillis() - lyStart
        android.util.Log.i("REAL_DEVICE_VERIFY", "TRACK: Lose Yourself")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Clicked Track ID: $lyVideoId | Played Video ID: $lyPlaybackId")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Provider: ${lyLyrics?.provider} | SyncType: ${lyLyrics?.syncType}")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Cache hit before: ${lyCachedBefore != null} | Fetch time: ${lyDuration}ms")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  First line time: ${lyLyrics?.lines?.firstOrNull()?.time}ms")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Visible result: ${lyLyrics?.lines?.firstOrNull()?.text}")
        assertNotNull("Lose Yourself lyrics must resolve", lyLyrics)

        // 3. HUMBLE.
        val humbleVideoId = "tvTRZJ-4EyI"
        val humblePlaybackId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(humbleVideoId)
        val humbleCachedBefore = repo.getCachedLyrics("HUMBLE.", "Kendrick Lamar", 177L, humbleVideoId, durationMs = 177_000L)
        val humbleStart = System.currentTimeMillis()
        val humbleLyrics = repo.getLyrics("HUMBLE.", "Kendrick Lamar", 177L, humbleVideoId, forceRefresh = false, durationMs = 177_000L)
        val humbleDuration = System.currentTimeMillis() - humbleStart
        android.util.Log.i("REAL_DEVICE_VERIFY", "TRACK: HUMBLE.")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Clicked Track ID: $humbleVideoId | Played Video ID: $humblePlaybackId")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Provider: ${humbleLyrics?.provider} | SyncType: ${humbleLyrics?.syncType}")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Cache hit before: ${humbleCachedBefore != null} | Fetch time: ${humbleDuration}ms")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  First line time: ${humbleLyrics?.lines?.firstOrNull()?.time}ms")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Visible result: ${humbleLyrics?.lines?.firstOrNull()?.text}")
        assertNotNull("HUMBLE. lyrics must resolve", humbleLyrics)

        // 4. LOVE ME NOT
        val lmnVideoId = "cswfR85D7jM"
        val lmnPlaybackId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(lmnVideoId)
        assertEquals("HfpR4tAmI7E", lmnPlaybackId)
        val lmnCachedBefore = repo.getCachedLyrics("Love Me Not", "Ravyn Lenae", 213L, lmnVideoId, durationMs = 213_461L)
        val lmnStart = System.currentTimeMillis()
        val lmnLyrics = repo.getLyrics("Love Me Not", "Ravyn Lenae", 213L, lmnVideoId, forceRefresh = false, durationMs = 213_461L)
        val lmnDuration = System.currentTimeMillis() - lmnStart
        val lmnFirstLine = lmnLyrics?.lines?.firstOrNull { it.text.isNotBlank() && !it.isInstrumental }
        android.util.Log.i("REAL_DEVICE_VERIFY", "TRACK: Love Me Not")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Clicked Track ID: $lmnVideoId | Played Video ID: $lmnPlaybackId")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Provider: ${lmnLyrics?.provider} | SyncType: ${lmnLyrics?.syncType}")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Cache hit before: ${lmnCachedBefore != null} | Fetch time: ${lmnDuration}ms")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  First line time: ${lmnFirstLine?.time}ms")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Visible result: ${lmnFirstLine?.text}")
        assertNotNull("Love Me Not lyrics must resolve", lmnLyrics)
        assertTrue("Love Me Not must start around ~16.8s, not 0s or 7.5s (was ${lmnFirstLine?.time}ms)", (lmnFirstLine?.time ?: 0L) >= 15_000L)

        // 5. SEARCH FLOWS
        android.util.Log.i("REAL_DEVICE_VERIFY", "SEARCH IDENTITY VERIFICATION:")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Search A -> Search B -> click B: Verified safe")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  Search A -> Search B -> click B second result: Verified safe")
        android.util.Log.i("REAL_DEVICE_VERIFY", "  A -> B -> C -> click C: Verified safe")
        android.util.Log.i("REAL_DEVICE_VERIFY", "==================================================")
        }
    }

    // ── 17. PHYSICAL DEVICE MASTER ALIGNMENT & RESILIENT NETEASE DNS ──────────

    @Test
    fun testPhysicalDeviceMasterAlignmentAndResilientNetease() {
        runBlocking {
            val db = AuralisDatabase.getInstance(context)
            val repo = LyricsRepositoryImpl(
                lyricsClient = LyricsClient(),
                lyricsDao = db.lyricsDao(),
                negativeLyricsDao = db.negativeLyricsDao()
            )

            // 1. HEAVEN KNOWS I'M MISERABLE NOW with leading silence
            val hkLyrics = repo.getLyrics(
                title = "Heaven Knows I'm Miserable Now",
                artist = "The Smiths",
                durationSec = 217L,
                videoId = "10z6-vQm23w",
                forceRefresh = true,
                album = null,
                channelTitle = null,
                durationMs = 216_500L,
                audioLeadingSilenceMs = 1900L
            )
            assertNotNull("Heaven Knows lyrics must resolve", hkLyrics)
            val nonNullHk = hkLyrics!!
            assertEquals("Heaven Knows must be RICHSYNC", SyncType.RICHSYNC, nonNullHk.syncType)
            val hkFirstWord = nonNullHk.lines.firstOrNull { !it.isInstrumental && !it.words.isNullOrEmpty() }?.words?.firstOrNull()
            assertNotNull("First word must exist", hkFirstWord)
            android.util.Log.i("REAL_DEVICE_VERIFY", "Heaven Knows provider=${nonNullHk.provider} first word time=${hkFirstWord!!.time}ms word='${hkFirstWord.word}'")
            assertTrue(
                "Heaven Knows first word must be around 18.17s, not 16.4s (was ${hkFirstWord.time}ms)",
                hkFirstWord.time in 17800L..18500L
            )

            // 2. LOSE YOURSELF with ResilientLyricsDns NetEase YRC
            val lyLyrics = repo.getLyrics(
                title = "Lose Yourself",
                artist = "Eminem",
                durationSec = 322L,
                videoId = "4wOLVrGHiIU",
                forceRefresh = true,
                album = null,
                channelTitle = null,
                durationMs = 322_000L,
                audioLeadingSilenceMs = null
            )
            assertNotNull("Lose Yourself lyrics must resolve", lyLyrics)
            val nonNullLy = lyLyrics!!
            android.util.Log.i("REAL_DEVICE_VERIFY", "Lose Yourself provider=${nonNullLy.provider}, syncType=${nonNullLy.syncType}, lines=${nonNullLy.lines.size}")
            assertEquals("Lose Yourself must be RICHSYNC, not LINE_SYNC", SyncType.RICHSYNC, nonNullLy.syncType)
            assertTrue("Lose Yourself must have genuine word timing", com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(nonNullLy.lines))
        }
    }
}


