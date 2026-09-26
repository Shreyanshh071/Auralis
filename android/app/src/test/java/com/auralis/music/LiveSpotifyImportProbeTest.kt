package com.auralis.music

import com.auralis.music.data.network.SpotifyPlaylistImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Imports AURALIS_SPOTIFY_URL through the app's importer (no YouTube matching) and writes each track's metadata to AURALIS_SPOTIFY_OUT. */
class LiveSpotifyImportProbeTest {
    @Test
    fun printSpotifyTracks(): Unit = runBlocking {
        Assume.assumeTrue(System.getenv("AURALIS_LIVE_LYRICS") == "1")
        val url = System.getenv("AURALIS_SPOTIFY_URL") ?: return@runBlocking
        val out = System.getenv("AURALIS_SPOTIFY_OUT") ?: return@runBlocking
        val pl = SpotifyPlaylistImporter().importPlaylist(url)
        val rows = pl?.tracks?.map { "[SP] ${it.id} \"${it.title}\" | ${it.artist} | album=${it.album} | ${it.duration}s" }.orEmpty()
        File(out).writeText(rows.joinToString("\n"))
    }
}
