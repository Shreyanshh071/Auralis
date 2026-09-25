package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.YouTubePlaylistImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test

/** Prints YouTube Music's own album tracklist for AURALIS_ALBUM_QUERY. Skipped unless AURALIS_LIVE_LYRICS=1. */
class LiveAlbumTracklistProbeTest {
    @Test
    fun printOfficialTracklist(): Unit = runBlocking {
        Assume.assumeTrue(System.getenv("AURALIS_LIVE_LYRICS") == "1")
        val q = System.getenv("AURALIS_ALBUM_QUERY") ?: "Housefull 2"
        val albums = InnerTubeClient().search(q, InnerTubeClient.FILTER_ALBUMS).albums
        for (a in albums.take(3)) {
            println("[ALBUM] \"${a.title}\" by ${a.author} id=${a.id}")
            val pl = YouTubePlaylistImporter().importPlaylistById(a.id)
            pl?.tracks?.forEachIndexed { i, t -> println("[TRACK] ${i + 1}. ${t.title} | ${t.artist} | ${t.duration}s | ${t.id}") }
        }
    }
}
