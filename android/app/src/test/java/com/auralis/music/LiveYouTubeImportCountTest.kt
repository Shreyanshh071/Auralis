package com.auralis.music

import com.auralis.music.data.network.CompleteYouTubePlaylistImporter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in check of the public playlist reported as importing 93/100; no account/session used. */
class LiveYouTubeImportCountTest {
    @Test fun `reported playlist imports all 100 source positions`() = runBlocking {
        assumeTrue(System.getenv("AURALIS_LIVE_IMPORT_PROBE") == "1")
        val playlist = withTimeout(45_000) { CompleteYouTubePlaylistImporter().importPlaylistById("PLaXJX-8WO2Us") }
        println("Live YouTube import: ${playlist.title}, ${playlist.tracks.size} positions, ${playlist.tracks.map { it.id }.distinct().size} distinct recordings")
        assertEquals(100, playlist.tracks.size)
        assertEquals(2, playlist.tracks.count { it.id == "MJoSyNdffGo" })
    }
}
