package com.auralis.music.windows.dev

import com.auralis.music.data.network.LyricsClient
import kotlinx.coroutines.runBlocking

/**
 * Runs the app's lyrics race for one song and prints the answer; the race logs every provider's
 * verdict to stderr. Reads and writes no app data.
 * usage: probeLyrics <title> <artist> <durationSec> [videoId]
 */
fun main(args: Array<String>) = runBlocking {
    // probeLyrics -Pargs="album::Title::Artist::KnownAlbum": the album a song table would show.
    if (args.first() == "album") {
        println("ALBUM: " + com.auralis.music.data.network.AlbumMetadataResolver.resolveAlbum(args[1], args[2], args.getOrNull(3)))
        return@runBlocking
    }
    val (title, artist, duration) = args
    val result = LyricsClient().getLyrics(
        title = title, artist = artist, durationSec = duration.toLong(), videoId = args.getOrNull(3) ?: "",
        album = null, channelTitle = null, durationMs = duration.toLong() * 1000L,
        audioLeadingSilenceMs = null, onInterim = {})
    println("RESULT: ${result?.provider} ${result?.syncType} lines=${result?.lines?.size}")
}
