package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.*
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LiveLyricsUpgradeProbeTest {

    @Test
    fun probeSunflower() = runBlocking {
        val client = LyricsClient()
        val result = client.getLyrics(
            title = "Sunflower (Spider-Man: Into the Spider-Verse)",
            artist = "Swae Lee",
            durationSec = 159L,
            videoId = "r7Rn4ryE_w8",
            durationMs = 159000L
        )
        println("PROBE Sunflower: provider=${result?.provider}, syncType=${result?.syncType}, lines=${result?.lines?.size}, hasWordTiming=${result?.lines?.let { WordTiming.hasGenuineWordStarts(it) }}")
        if (result != null && result.lines.isNotEmpty()) {
            println("PROBE Sunflower first 3 lines:")
            result.lines.take(3).forEach { println("  [${it.time}ms] ${it.text} (words=${it.words?.size})") }
        }
    }

    @Test
    fun probeOctober() = runBlocking {
        val client = LyricsClient()
        val result = client.getLyrics(
            title = "we fell in love in october",
            artist = "girl in red",
            durationSec = 184L,
            videoId = "FJX0JPXD2nM",
            durationMs = 184000L
        )
        println("PROBE October: provider=${result?.provider}, syncType=${result?.syncType}, lines=${result?.lines?.size}, hasWordTiming=${result?.lines?.let { WordTiming.hasGenuineWordStarts(it) }}")
        if (result != null && result.lines.isNotEmpty()) {
            println("PROBE October first 3 lines:")
            result.lines.take(3).forEach { println("  [${it.time}ms] ${it.text} (words=${it.words?.size})") }
        }
    }

    @Test
    fun probeLoveMeNot() = runBlocking {
        val client = LyricsClient()
        val result = client.getLyrics(
            title = "Love Me Not",
            artist = "Ravyn Lenae",
            durationSec = 213L,
            videoId = "HfpR4tAmI7E",
            durationMs = 213000L
        )
        println("PROBE LoveMeNot: provider=${result?.provider}, syncType=${result?.syncType}, lines=${result?.lines?.size}, hasWordTiming=${result?.lines?.let { WordTiming.hasGenuineWordStarts(it) }}")
        if (result != null && result.lines.isNotEmpty()) {
            println("PROBE LoveMeNot first 3 lines:")
            result.lines.take(3).forEach { println("  [${it.time}ms] ${it.text} (words=${it.words?.size})") }
        }
    }
}
