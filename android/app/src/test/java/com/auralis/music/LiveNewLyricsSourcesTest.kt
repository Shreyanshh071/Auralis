package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.data.network.provider.YouLyPlusLyricsSource
import com.auralis.music.data.network.provider.YouTubeCaptionsLyricsSource
import com.auralis.music.data.network.provider.YouTubeInnerTubeLyricsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test

/** Live probe of YouLy+ and caption timing. Skipped unless AURALIS_LIVE_LYRICS=1. */
class LiveNewLyricsSourcesTest {

    @Test
    fun probeYouLyPlusAndCaptions() = runBlocking {
        Assume.assumeTrue("set AURALIS_LIVE_LYRICS=1 to run", System.getenv("AURALIS_LIVE_LYRICS") == "1")

        val ylp = YouLyPlusLyricsSource()
        for ((title, artist, dur) in listOf(
            Triple("Hawayein (From \"Jab Harry Met Sejal\")", "Pritam & Arijit Singh", 290L),
            Triple("Tum Hi Ho", "Arijit Singh", 262L),
            Triple("Blinding Lights", "The Weeknd", 202L),
            Triple("Satranga", "Arijit Singh, Shreyas Puranik & Siddharth-Garima", 272L)
        )) {
            val t0 = System.currentTimeMillis()
            val c = ylp.search(LyricsSearchQuery(title = title, artist = artist, durationSec = dur))
            println("[YOULY+] \"$title\" in ${System.currentTimeMillis() - t0}ms -> " +
                if (c == null) "NULL" else "sync=${c.syncType} tier=${LyricsClient.tierOf(c.lyricsData)} lines=${c.lyricsData.lines.size} conf=${c.confidence} first=\"${c.lyricsData.lines.first().text}\"@${c.lyricsData.lines.first().time}")
        }

        // Official music video (English captions) and its plain lyrics from YouTube Music.
        val yt = YouTubeInnerTubeLyricsSource()
        val captions = YouTubeCaptionsLyricsSource()
        for ((videoId, audioId) in listOf("fHI8X4OXluQ" to "4NRXx6U8ABQ")) {
            val plain = yt.search(LyricsSearchQuery(title = "Blinding Lights", artist = "The Weeknd", videoId = audioId))?.lyricsData
                ?: com.auralis.music.data.network.provider.LrcLibLyricsSource()
                    .search(LyricsSearchQuery(title = "Blinding Lights", artist = "The Weeknd", durationSec = 200L))?.lyricsData
            println("[CAPTIONS] reference plain lyrics: ${plain?.lines?.size ?: 0} lines")
            if (plain == null) continue
            val t0 = System.currentTimeMillis()
            val timed = captions.timeFromCaptions(videoId, plain)
            println("[CAPTIONS] $videoId in ${System.currentTimeMillis() - t0}ms -> " +
                if (timed == null) "NULL" else "${timed.syncType} ${timed.lines.size} lines, first=\"${timed.lines.first().text}\"@${timed.lines.first().time}")
        }
    }
}
