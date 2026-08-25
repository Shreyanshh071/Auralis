package com.auralis.music

import com.auralis.music.domain.library.PlaylistManager
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistManagerTest {

    private val track1 = Track(id = "t1", title = "Song One", artist = "Artist A", duration = 180, thumbnail = "https://t1")
    private val track2 = Track(id = "t2", title = "Song Two", artist = "Artist B", duration = 200, thumbnail = "https://t2")
    private val track3 = Track(id = "t3", title = "Song Three", artist = "Artist C", duration = 220, thumbnail = "https://t3")

    @Test
    fun `reorderTracks accurately shifts track indices`() {
        val original = listOf(track1, track2, track3)

        // Move track 1 from index 0 to index 2
        val reordered = PlaylistManager.reorderTracks(original, fromIndex = 0, toIndex = 2)

        assertEquals(3, reordered.size)
        assertEquals("t2", reordered[0].id)
        assertEquals("t3", reordered[1].id)
        assertEquals("t1", reordered[2].id)
    }

    @Test
    fun `exportBackupJson and parseBackupJson perform lossless serialization and deserialization`() {
        val samplePlaylist = Playlist(
            id = "pl-1",
            title = "Chill Beats",
            description = "My favorite relaxing songs",
            coverUrl = "https://cover",
            tracks = listOf(track1, track2)
        )
        val favorites = listOf(track3)

        val jsonString = PlaylistManager.exportBackupJson(listOf(samplePlaylist), favorites)
        assertNotNull(jsonString)
        assertTrue(jsonString.contains("Chill Beats"))
        assertTrue(jsonString.contains("Song One"))

        val backupData = PlaylistManager.parseBackupJson(jsonString)
        assertEquals(1, backupData.playlists.size)
        assertEquals(1, backupData.favorites.size)

        val restoredPlaylist = backupData.playlists[0]
        assertEquals("pl-1", restoredPlaylist.id)
        assertEquals("Chill Beats", restoredPlaylist.title)
        assertEquals(2, restoredPlaylist.tracks.size)
        assertEquals("Song One", restoredPlaylist.tracks[0].title)

        val restoredFav = backupData.favorites[0]
        assertEquals("t3", restoredFav.id)
        assertEquals("Song Three", restoredFav.title)
    }
}
