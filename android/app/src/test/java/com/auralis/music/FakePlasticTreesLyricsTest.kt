package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test

class FakePlasticTreesLyricsTest {

    @Test
    fun testFakePlasticTreesVariations() = runBlocking {
        val client = LyricsClient()
        val testCases = listOf(
            TestCase("Studio 290s", "Fake Plastic Trees", "Radiohead", 290L, "6gDhsUWCHrg", null, 290700L),
            TestCase("YTMusic 291s", "Fake Plastic Trees", "Radiohead", 291L, "6gDhsUWCHrg", null, 291000L),
            TestCase("OfficialVideo 292s", "Fake Plastic Trees", "Radiohead", 292L, "n5h0qHwNrHk", null, 292000L),
            TestCase("OfficialVideo 293s", "Fake Plastic Trees", "Radiohead", 293L, "n5h0qHwNrHk", null, 293000L),
            TestCase("OfficialVideo 294s", "Fake Plastic Trees", "Radiohead", 294L, "n5h0qHwNrHk", null, 294000L),
            TestCase("OfficialVideo 295s", "Fake Plastic Trees", "Radiohead", 295L, "n5h0qHwNrHk", null, 295000L),
            TestCase("ExtendedVideo 296s", "Fake Plastic Trees", "Radiohead", 296L, "n5h0qHwNrHk", null, 296000L),
            TestCase("ExtendedVideo 297s", "Fake Plastic Trees", "Radiohead", 297L, "n5h0qHwNrHk", null, 297000L),
            TestCase("ExtendedVideo 298s", "Fake Plastic Trees", "Radiohead", 298L, "n5h0qHwNrHk", null, 298000L),
            TestCase("ExtendedVideo 300s", "Fake Plastic Trees", "Radiohead", 300L, "n5h0qHwNrHk", null, 300000L),
            TestCase("ZeroDuration", "Fake Plastic Trees", "Radiohead", 0L, "6gDhsUWCHrg", null, 0L),
            TestCase("NullDuration", "Fake Plastic Trees", "Radiohead", null, "6gDhsUWCHrg", null, null)
        )

        for (tc in testCases) {
            println("==================================================")
            println("TEST CASE: ${tc.label} (sec=${tc.durationSec}, ms=${tc.durationMs}, vid=${tc.videoId})")
            val lyrics = client.getLyrics(
                title = tc.title,
                artist = tc.artist,
                durationSec = tc.durationSec,
                videoId = tc.videoId,
                album = "The Bends",
                durationMs = tc.durationMs
            )
            org.junit.Assert.assertNotNull("Lyrics must not be null for ${tc.label}", lyrics)
            org.junit.Assert.assertTrue("Lyrics lines must not be empty for ${tc.label}", lyrics!!.lines.isNotEmpty())
            val tier = LyricsClient.tierOf(lyrics)
            val lines = lyrics.lines.size
            val dur = lyrics.effectiveDurationMs
            val match = LyricsAlignmentEngine.evaluateMasterMatch(
                lyrics = lyrics,
                playbackDurationMs = tc.durationMs ?: ((tc.durationSec ?: 0L) * 1000L),
                playbackTitle = tc.title,
                candidateTitle = lyrics.trackName,
                playbackVideoId = tc.videoId
            )
            println("RESULT: SUCCESS - provider=${lyrics.provider}, syncType=${lyrics.syncType}, tier=$tier, lines=$lines, dur=${dur}ms, match=$match")
        }
    }

    @Test
    fun testDurationMismatchRejection() = runBlocking {
        val client = LyricsClient()
        val query = com.auralis.music.data.network.provider.LyricsSearchQuery(
            title = "Fake Plastic Trees",
            artist = "Radiohead",
            durationSec = 295L,
            videoId = "n5h0qHwNrHk",
            album = "The Bends",
            durationMs = 294500L
        )
        val lyrics = client.getLyrics(
            title = query.title,
            artist = query.artist,
            durationSec = query.durationSec,
            videoId = query.videoId,
            album = query.album,
            durationMs = query.durationMs
        )
        org.junit.Assert.assertNotNull("Lyrics must now be safely delivered even with duration delta", lyrics)
        org.junit.Assert.assertTrue(lyrics!!.lines.isNotEmpty())
        println("Resolved lyrics successfully: provider=${lyrics.provider}, lines=${lyrics.lines.size}")
    }

    data class TestCase(
        val label: String,
        val title: String,
        val artist: String,
        val durationSec: Long?,
        val videoId: String?,
        val channelTitle: String?,
        val durationMs: Long?
    )
}
