package com.auralis.music.desktop.library

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class MusicLibraryRepositoryTest {
    @Test fun liveExistingPlaylistHidesReportedShortWithoutChangingStoredData() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_LIVE_SHORTS") == "1")
        val file = java.io.File(System.getenv("APPDATA"), "Auralis/library.json")
        val before = file.readText()
        val raw = DesktopLibraryRepository(file)
        val playlist = assertNotNull(raw.getPlaylists().first().firstOrNull { p -> p.tracks.any { it.id == "H467W9K843E" } })
        val playable = assertNotNull(MusicLibraryRepository(raw).getPlaylist(playlist.id).first())
        assertFalse(playable.tracks.any { it.id == "H467W9K843E" })
        assertTrue(raw.getPlaylist(playlist.id).first()!!.tracks.any { it.id == "H467W9K843E" })
        assertEquals(before, file.readText())
        println("Saved playlist filtered: ${playlist.tracks.size} original, ${playable.tracks.size} playable; original data preserved")
    }

    @Test fun savedPlaylistAndQueuesExcludeShortsWhileSyncRetainsOriginalEntries() = runBlocking {
        val folder = Files.createTempDirectory("auralis-music-library").toFile()
        try {
            val raw = DesktopLibraryRepository(java.io.File(folder, "library.json"))
            val playlist = raw.createPlaylist("Mixed playlist", null, null)
            val song = Track("song", "Short song", duration = 49)
            val clip = Track("clip", "Gaming Short", duration = 49)
            val interlude = Track("interlude", "Interlude", duration = 30)
            raw.replacePlaylistTracks(playlist.id, listOf(song, clip, interlude))
            val visible = MusicLibraryRepository(raw) { tracks -> tracks.filterNot { it.id == clip.id } }
            assertEquals(listOf(song, interlude), visible.getPlaylist(playlist.id).first()!!.tracks)
            assertEquals(listOf(song, interlude), visible.getPlaylists().first().single().tracks)
            assertEquals(listOf(song, clip, interlude), raw.getPlaylist(playlist.id).first()!!.tracks)
            visible.reorderPlaylist(playlist.id, listOf(interlude, song))
            assertEquals(listOf(interlude, clip, song), raw.getPlaylist(playlist.id).first()!!.tracks)
            visible.replacePlaylistTracks(playlist.id, listOf(interlude.copy(thumbnail = "updated"), song))
            assertEquals(listOf("interlude", "clip", "song"), raw.getPlaylist(playlist.id).first()!!.tracks.map { it.id })
            assertEquals("updated", visible.getPlaylist(playlist.id).first()!!.tracks.first().thumbnail)
            raw.setFavorite(clip, true)
            raw.setFavorite(song, true)
            assertEquals(listOf(song), visible.getFavoriteTracks().first())
            assertEquals(2, raw.getFavoriteTracks().first().size)
        } finally {
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }
}
