package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LivePlaybackSyncDiagnosisTest {

    @Test
    fun diagnoseHeavenKnowsAndLoseYourself() = runBlocking {
        val client = LyricsClient()

        println("\n=======================================================")
        println("LIVE DIAGNOSIS: 1. HEAVEN KNOWS I'M MISERABLE NOW")
        println("=======================================================")

        // Query for Heaven Knows with duration 216s (the YouTube duration)
        val hkLyrics = client.getLyrics(
            title = "Heaven Knows I'm Miserable Now",
            artist = "The Smiths",
            durationSec = 216L,
            videoId = "3Mr0pDNVms0",
            durationMs = 216_456L
        )

        if (hkLyrics != null) {
            println("HK WINNER PROVIDER: ${hkLyrics.provider}")
            println("HK SYNC TYPE: ${hkLyrics.syncType}")
            println("HK DURATION MS: ${hkLyrics.durationMs}")
            println("HK LEADING SILENCE MS: ${hkLyrics.leadingSilenceMs}")
            println("HK LINE COUNT: ${hkLyrics.lines.size}")
            val firstLine = hkLyrics.lines.firstOrNull { !it.isInstrumental }
            println("HK FIRST LINE: \"${firstLine?.text}\" at ${firstLine?.time}ms")
            val words = firstLine?.words ?: emptyList()
            println("HK FIRST LINE WORDS:")
            for (w in words) {
                println("   Word: '${w.word}' | start=${w.time}ms | dur=${w.duration}ms")
            }

            // Test alignment against hk_3Mr0pDNVms0 (audio leading silence = 2036ms)
            val aligned3Mr = LyricsAlignmentEngine.alignToPlayback(hkLyrics, 216_456L, 2036L)
            val aligned3MrWord = aligned3Mr.lines.firstOrNull { !it.isInstrumental }?.words?.firstOrNull()
            println("Aligned for 3Mr0pDNVms0 (silence=2036ms): first word at ${aligned3MrWord?.time}ms")

            // Test alignment against hk_10z6-vQm23w (audio leading silence = 1349ms)
            val aligned10z = LyricsAlignmentEngine.alignToPlayback(hkLyrics, 215_783L, 1349L)
            val aligned10zWord = aligned10z.lines.firstOrNull { !it.isInstrumental }?.words?.firstOrNull()
            println("Aligned for 10z6-vQm23w (silence=1349ms): first word at ${aligned10zWord?.time}ms")
        } else {
            println("HK lyrics returned NULL!")
        }

        println("\n=======================================================")
        println("LIVE DIAGNOSIS: 2. LOSE YOURSELF")
        println("=======================================================")

        val lyLyrics = client.getLyrics(
            title = "Lose Yourself",
            artist = "Eminem",
            durationSec = 322L,
            videoId = "4wOLVrGHiIU",
            durationMs = 322_200L
        )

        if (lyLyrics != null) {
            println("LY WINNER PROVIDER: ${lyLyrics.provider}")
            println("LY SYNC TYPE: ${lyLyrics.syncType}")
            println("LY DURATION MS: ${lyLyrics.durationMs}")
            println("LY LEADING SILENCE MS: ${lyLyrics.leadingSilenceMs}")
            println("LY LINE COUNT: ${lyLyrics.lines.size}")
            val firstLine = lyLyrics.lines.firstOrNull { !it.isInstrumental && it.text.isNotBlank() }
            println("LY FIRST LINE: \"${firstLine?.text}\" at ${firstLine?.time}ms")
            val words = firstLine?.words ?: emptyList()
            println("LY FIRST LINE WORDS:")
            for (w in words.take(8)) {
                println("   Word: '${w.word}' | start=${w.time}ms | dur=${w.duration}ms")
            }

            // Multiple anchor lines throughout the song:
            // Line 1: Look
            // Anchor 2: His palms are sweaty
            // Anchor 3: Snap back to reality
            val palmsLine = lyLyrics.lines.firstOrNull { it.text.contains("palms", ignoreCase = true) }
            val snapLine = lyLyrics.lines.firstOrNull { it.text.contains("reality", ignoreCase = true) }
            println("LY ANCHOR 2 (Palms): \"${palmsLine?.text}\" at ${palmsLine?.time}ms")
            println("LY ANCHOR 3 (Snap): \"${snapLine?.text}\" at ${snapLine?.time}ms")

            // Test alignment against ly_4wOLVrGHiIU (audio leading silence = 1263ms)
            val aligned4w = LyricsAlignmentEngine.alignToPlayback(lyLyrics, 322_200L, 1263L)
            val aligned4wWord = aligned4w.lines.firstOrNull { !it.isInstrumental && it.text.isNotBlank() }?.words?.firstOrNull()
            println("Aligned for 4wOLVrGHiIU (silence=1263ms): first word at ${aligned4wWord?.time}ms")
        } else {
            println("LY lyrics returned NULL!")
        }
        println("=======================================================\n")
    }
}
