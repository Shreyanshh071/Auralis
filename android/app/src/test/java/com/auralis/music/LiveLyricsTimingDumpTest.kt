package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.LyricsClient
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test

/**
 * Dumps the first lines (with timestamps) the full lyrics pipeline picks for a song, to compare
 * against where the vocals actually start. Skipped unless AURALIS_LIVE_LYRICS=1;
 * AURALIS_DUMP_QUERY overrides the song.
 */
class LiveLyricsTimingDumpTest {

    @Test
    fun dumpFirstLines(): Unit = runBlocking {
        Assume.assumeTrue("set AURALIS_LIVE_LYRICS=1 to run", System.getenv("AURALIS_LIVE_LYRICS") == "1")
        val q = System.getenv("AURALIS_DUMP_QUERY") ?: "Agar Tum Saath Ho Arijit Singh"
        val track = InnerTubeClient().search(q, InnerTubeClient.FILTER_SONGS).songs.first()
        println("[TRACK] \"${track.title}\" by \"${track.artist}\" id=${track.id} dur=${track.duration}s album=${track.album}")
        val lyrics = LyricsClient().getLyrics(
            title = track.title, artist = track.artist, durationSec = track.duration,
            videoId = track.id, album = track.album, channelTitle = track.channelTitle,
            durationMs = track.duration * 1000L
        )
        println("[LYRICS] provider=${lyrics?.provider} sync=${lyrics?.syncType} lines=${lyrics?.lines?.size} dur=${lyrics?.durationMs}")
        lyrics?.lines?.take(12)?.forEach { l ->
            println("[LINE] ${l.time}ms end=${l.endTime} bg=${l.isBackground} agent=${l.agent} words=${l.words?.size ?: 0} tr=${l.translatedText != null} \"${l.text}\"")
        }
    }
}
