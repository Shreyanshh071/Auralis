package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.*
import com.auralis.music.domain.model.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The lyrics race must hand back its winner as soon as it has decided, not after the slowest
 * source returns. Sources make blocking OkHttp calls that cancellation can't interrupt; on a real
 * phone a word-synced winner found in 0.8s was held for 11s behind AMLL.
 */
class LyricsRaceLatencyTest {

    private fun wordLyrics(provider: LyricsProvider): LyricsData {
        val lines = (0 until 12).map { i ->
            val t = 10_000L + i * 4_000L
            LyricLine(
                time = t,
                text = "alpha beta gamma $i",
                words = listOf(
                    LyricWord("alpha ", t, 300L),
                    LyricWord("beta ", t + 400L, 300L),
                    LyricWord("gamma $i", t + 800L, 400L)
                ),
                endTime = t + 1_200L
            )
        }
        return LyricsData(syncType = SyncType.RICHSYNC, lines = lines, provider = provider, trackName = "Song", artistName = "Artist")
    }

    private fun lineLyrics(provider: LyricsProvider, count: Int): LyricsData =
        LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = (0 until count).map { LyricLine(time = 10_000L + it * 4_000L, text = "line $it") },
            provider = provider, trackName = "Song", artistName = "Artist"
        )

    private fun <T : LyricsSource> source(mock: T, provider: LyricsProvider, data: LyricsData?, blockMs: Long = 0L): T {
        every { mock.provider } returns provider
        coEvery { mock.search(any()) } coAnswers {
            // Thread.sleep, not delay: models a blocking network call cancellation can't stop.
            if (blockMs > 0) Thread.sleep(blockMs)
            data?.let { LyricsCandidate(it, confidence = 95, syncType = it.syncType, provider = provider) }
        }
        return mock
    }

    private fun client(
        betterLyrics: LyricsData? = null,
        amllBlockMs: Long = 0L,
        netEase: LyricsData? = null,
        lrcLib: LyricsData? = null,
        youLyPlus: LyricsData? = null,
        youLyPlusBlockMs: Long = 0L,
        betterLyricsBlockMs: Long = 0L,
        youTubePlain: LyricsData? = null
    ) = LyricsClient(
        amllSource = source(mockk(), LyricsProvider.AMLL, null, blockMs = amllBlockMs),
        betterLyricsSource = source(mockk(), LyricsProvider.BETTER_LYRICS, betterLyrics, blockMs = betterLyricsBlockMs),
        unisonSource = source(mockk(), LyricsProvider.UNISON, null),
        paxsenixSource = source(mockk(), LyricsProvider.PAXSENIX, null),
        lrcLibSource = source(mockk(), LyricsProvider.LRCLIB, lrcLib),
        jioSaavnSource = source(mockk(), LyricsProvider.JIOSAAVN, null),
        netEaseSource = source(mockk(), LyricsProvider.NETEASE, netEase),
        kuGouSource = source(mockk(), LyricsProvider.KUGOU, null),
        musixmatchSource = source(mockk(), LyricsProvider.MUSIXMATCH, null),
        geniusSource = source(mockk(), LyricsProvider.GENIUS, null),
        ytMusicSource = source(mockk(), LyricsProvider.YOUTUBE, youTubePlain),
        youLyPlusSource = source(mockk(), LyricsProvider.YOULYPLUS, youLyPlus, blockMs = youLyPlusBlockMs),
        simpMusicSource = source(mockk(), LyricsProvider.SIMPMUSIC, null),
        captionsSource = mockk<YouTubeCaptionsLyricsSource>().also { coEvery { it.timeFromCaptions(any(), any()) } returns null }
    )

    @Test
    fun `a decided winner is returned without waiting for a blocked slow source`() = runBlocking {
        val c = client(betterLyrics = wordLyrics(LyricsProvider.BETTER_LYRICS), amllBlockMs = 12_000L)
        val t0 = System.currentTimeMillis()
        val result = c.getLyrics("Song", "Artist", durationSec = 60L, durationMs = 60_000L)
        val elapsed = System.currentTimeMillis() - t0
        assertNotNull(result)
        assertEquals(LyricsProvider.BETTER_LYRICS, result!!.provider)
        assertTrue("race took ${elapsed}ms; it waited on the blocked source", elapsed < 8_000L)
    }

    @Test
    fun `a cold YouLy+ fetch past the normal source budget still wins when nobody else has the song`() = runBlocking {
        // Measured: YouLy+ takes ~9s on a song its backend hasn't served lately, vs 0.5s warm.
        // The old 6.5s budget dropped every such song to unsynced even though YouLy+ had it.
        val c = client(youLyPlus = wordLyrics(LyricsProvider.YOULYPLUS), youLyPlusBlockMs = 7_500L)
        val result = c.getLyrics("Song", "Artist", durationSec = 60L, durationMs = 60_000L)
        assertNotNull("cold YouLy+ answer was dropped", result)
        assertEquals(LyricsProvider.YOULYPLUS, result!!.provider)
    }

    @Test
    fun `line sync is shown early while a word-sync source is still answering, then word sync wins`() = runBlocking {
        val interims = java.util.Collections.synchronizedList(mutableListOf<LyricsData>())
        val c = client(
            lrcLib = lineLyrics(LyricsProvider.LRCLIB, count = 12),
            betterLyrics = wordLyrics(LyricsProvider.BETTER_LYRICS), betterLyricsBlockMs = 2_000L
        )
        val result = c.getLyrics("Song", "Artist", durationSec = 60L, durationMs = 60_000L, onInterim = { interims.add(it) })
        assertEquals(LyricsProvider.BETTER_LYRICS, result!!.provider)
        assertTrue("line sync wasn't offered early: ${interims.map { it.provider }}",
            interims.firstOrNull()?.provider == LyricsProvider.LRCLIB)
    }

    @Test
    fun `plain text is shown while a cold YouLy+ fetch is still running`() = runBlocking {
        val interims = java.util.Collections.synchronizedList(mutableListOf<LyricsData>())
        val plain = LyricsData(syncType = SyncType.PLAIN, lines = (0 until 10).map { LyricLine(time = 0L, text = "plain $it") }, provider = LyricsProvider.YOUTUBE)
        val c = client(youLyPlus = wordLyrics(LyricsProvider.YOULYPLUS), youLyPlusBlockMs = 9_000L, youTubePlain = plain)
        val result = c.getLyrics("Song", "Artist", durationSec = 60L, durationMs = 60_000L, videoId = "abc", onInterim = { interims.add(it) })
        assertEquals(LyricsProvider.YOULYPLUS, result!!.provider)
        assertTrue("plain text wasn't shown during the wait: ${interims.map { it.provider }}",
            interims.any { it.provider == LyricsProvider.YOUTUBE })
    }

    @Test
    fun `a fast line-sync candidate does not settle the race before a cold YouLy+ fetch can deliver word sync`() = runBlocking {
        // Regression: line sync (fast, e.g. lrclib) used to win over a still-fetching YouLy+
        // because the race only waited 5.5s for word sync, well under YouLy+'s ~9-13s cold fetch.
        val c = client(
            lrcLib = lineLyrics(LyricsProvider.LRCLIB, count = 12),
            youLyPlus = wordLyrics(LyricsProvider.YOULYPLUS), youLyPlusBlockMs = 8_000L
        )
        val result = c.getLyrics("Song", "Artist", durationSec = 60L, durationMs = 60_000L)
        assertEquals("settled for line sync before YouLy+'s cold fetch could finish",
            LyricsProvider.YOULYPLUS, result!!.provider)
        assertEquals(SyncType.RICHSYNC, result.syncType)
    }

    @Test
    fun `a one or two line result is not accepted as a song's synced lyrics`() = runBlocking {
        val c = client(netEase = lineLyrics(LyricsProvider.NETEASE, count = 1), lrcLib = lineLyrics(LyricsProvider.LRCLIB, count = 12))
        val result = c.getLyrics("Song", "Artist", durationSec = 60L, durationMs = 60_000L)
        assertNotNull(result)
        assertEquals(LyricsProvider.LRCLIB, result!!.provider)
        assertTrue(result.lines.size >= LyricsClient.MIN_SUNG_LINES)
    }
}
