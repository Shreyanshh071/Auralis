package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LiveLyricsClientAuditTest {

    @Test
    fun auditLiveClientForLoveMeNot() = runBlocking {
        println("\n=======================================================")
        println("AUDITING LIVE LYRICSCLIENT FOR LOVE ME NOT")
        println("=======================================================")
        val client = LyricsClient()
        val result = client.getLyrics("Love Me Not", "Ravyn Lenae", durationSec = 214L)
        if (result != null) {
            println("WINNER PROVIDER: ${result.provider}")
            println("WINNER SYNCTYPE: ${result.syncType}")
            println("TRACK NAME: ${result.trackName}")
            println("ARTIST NAME: ${result.artistName}")
            println("TOTAL LINES: ${result.lines.size}")
            for (i in 0 until minOf(5, result.lines.size)) {
                val l = result.lines[i]
                println("LINE $i [${l.time}ms] (hasWords=${l.hasWordTiming}): \"${l.text}\"")
                l.words?.forEach { w ->
                    println("   WORD: \"${w.word}\" [${w.time} - ${w.duration?.let { w.time + it }}] (dur=${w.duration}ms)")
                }
            }
        } else {
            println("LyricsClient returned NULL!")
        }
        println("=======================================================\n")
    }
}
