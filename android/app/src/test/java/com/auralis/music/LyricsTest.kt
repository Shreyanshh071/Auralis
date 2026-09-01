package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LyricsMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {

    data class SongTestItem(
        val title: String,
        val artist: String,
        val durationSec: Long,
        val expectedKeywordInLyrics: String
    )

    @Test
    fun testComprehensiveMainstreamSongsLyrics() {
        runBlocking {
        val client = LyricsClient()

        val testSongs = listOf(
            SongTestItem(
                title = "Raanjhanaa (From \"Raanjhanaa\")",
                artist = "A.R. Rahman, Jaswinder Singh, Shiraz Uppal",
                durationSec = 345L,
                expectedKeywordInLyrics = "राँझणा"
            ),
            SongTestItem(
                title = "Starboy",
                artist = "The Weeknd, Daft Punk",
                durationSec = 230L,
                expectedKeywordInLyrics = "Starboy"
            ),
            SongTestItem(
                title = "Let It Happen",
                artist = "Tame Impala",
                durationSec = 467L,
                expectedKeywordInLyrics = "happen"
            ),
            SongTestItem(
                title = "Kesariya (From \"Brahmastra\")",
                artist = "Arijit Singh, Pritam, Amitabh Bhattacharya",
                durationSec = 268L,
                expectedKeywordInLyrics = "केसरिया"
            ),
            SongTestItem(
                title = "Tum Hi Ho",
                artist = "Arijit Singh, Mithoon",
                durationSec = 262L,
                expectedKeywordInLyrics = "तुम ही हो"
            ),
            SongTestItem(
                title = "Channa Mereya",
                artist = "Arijit Singh, Pritam",
                durationSec = 289L,
                expectedKeywordInLyrics = "अच्छा चलता हूँ"
            ),
            SongTestItem(
                title = "Karma Police",
                artist = "Radiohead",
                durationSec = 264L,
                expectedKeywordInLyrics = "Karma police"
            )
        )

        for (song in testSongs) {
            println("\n==========================================")
            println("Testing song: \"${song.title}\" by \"${song.artist}\"")
            val lyrics = client.getLyrics(song.title, song.artist, song.durationSec)
            if (lyrics != null) {
                println("Winner provider: ${lyrics.provider}, syncType: ${lyrics.syncType}, total lines: ${lyrics.lines.size}")
                val preview = lyrics.lines.take(6).joinToString("\n") { "   [${it.time}ms] ${it.text}" }
                println(preview)

                val fullText = lyrics.lines.joinToString(" ") { it.text }
                println("Contains expected keyword ('${song.expectedKeywordInLyrics}'): ${fullText.contains(song.expectedKeywordInLyrics, ignoreCase = true)}")
            } else {
                println("❌ No lyrics found for: ${song.title}")
            }
        }
        }
    }

    @Test
    fun testWrongSongRejection() {
        // Test that a completely different song or another song from the same movie is strictly rejected
        val queryTitle = "Raanjhanaa (From \"Raanjhanaa\")"
        val queryArtist = "A.R. Rahman, Jaswinder Singh, Shiraz Uppal"

        val wrongSong1 = "Piya Milenge (From \"Raanjhanaa\")"
        val wrongSong2 = "Koi Mil Gaya"
        val wrongSong3 = "Tum Tak (From \"Raanjhanaa\")"

        val conf1 = LyricsMatcher.calculateConfidence(queryTitle, queryArtist, wrongSong1, "A.R. Rahman")
        val conf2 = LyricsMatcher.calculateConfidence(queryTitle, queryArtist, wrongSong2, "Udit Narayan")
        val conf3 = LyricsMatcher.calculateConfidence(queryTitle, queryArtist, wrongSong3, "A.R. Rahman, Javed Ali")

        assertEquals("Piya Milenge must be rejected", 0, conf1)
        assertEquals("Koi Mil Gaya must be rejected", 0, conf2)
        assertEquals("Tum Tak must be rejected", 0, conf3)
    }
}
