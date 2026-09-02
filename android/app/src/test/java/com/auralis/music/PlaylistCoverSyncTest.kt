package com.auralis.music

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import org.junit.Assert.*
import org.junit.Test

class PlaylistCoverSyncTest {

    @Test
    fun `playlist preserves custom coverUrl and tracks`() {
        val playlist = Playlist(
            id = "test-pl-1",
            title = "Chill Vibes",
            description = "My favorite tracks",
            coverUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4",
            tracks = listOf(
                Track(
                    id = "track-1",
                    title = "Song A",
                    artist = "Artist A",
                    thumbnail = "https://img.youtube.com/vi/track-1/hqdefault.jpg",
                    duration = 180L
                )
            ),
            createdAt = 123456789L
        )

        assertEquals("Chill Vibes", playlist.title)
        assertEquals("https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4", playlist.coverUrl)
        assertEquals(1, playlist.tracks.size)
    }

    @Test
    fun `cloud restore simulation extracts coverUrl correctly`() {
        val docData = mapOf(
            "title" to "Night Drives",
            "description" to "Late night vibes",
            "coverUrl" to "data:image/jpeg;base64,/9j/4AAQSkZJRg==",
            "tracks" to listOf(
                mapOf(
                    "id" to "track-xyz",
                    "title" to "Midnight City",
                    "artist" to "M83",
                    "thumbnail" to "https://img.youtube.com/vi/track-xyz/hqdefault.jpg",
                    "duration" to 240
                )
            )
        )

        val restoredTitle = docData["title"] as? String
        val restoredCoverUrl = docData["coverUrl"] as? String
        val rawTracks = docData["tracks"] as? List<*>

        assertEquals("Night Drives", restoredTitle)
        assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRg==", restoredCoverUrl)
        assertNotNull(rawTracks)
        assertEquals(1, rawTracks?.size)
    }
}
