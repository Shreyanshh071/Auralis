package com.auralis.music

import com.auralis.music.data.datastore.PersistedQueue
import com.auralis.music.data.datastore.QueueDataStore
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import org.junit.Assert.*
import org.junit.Test

class QueueDataStoreTest {

    @Test
    fun `serializeTracks and deserializeTracks correctly round-trips track list`() {
        val tracks = listOf(
            Track(
                id = "track_1",
                title = "Creep",
                artist = "Radiohead",
                album = "Pablo Honey",
                duration = 239L,
                thumbnail = "https://img.youtube.com/vi/track_1/hqdefault.jpg",
                source = TrackSource.YOUTUBE,
                channelTitle = "Radiohead",
                views = "1.2B",
                dominantColor = 0xFF1E2430.toInt()
            ),
            Track(
                id = "track_2",
                title = "Love Me Not",
                artist = "Ravyn Lenae",
                album = "Bird's Eye",
                duration = 214L,
                thumbnail = "https://img.youtube.com/vi/track_2/hqdefault.jpg",
                source = TrackSource.LOCAL,
                channelTitle = "Ravyn Lenae",
                views = "50M",
                dominantColor = null
            )
        )

        val json = QueueDataStore.serializeTracks(tracks)
        assertNotNull(json)
        assertTrue(json.contains("track_1"))
        assertTrue(json.contains("Creep"))
        assertTrue(json.contains("track_2"))

        val deserialized = QueueDataStore.deserializeTracks(json)
        assertEquals(2, deserialized.size)

        val first = deserialized[0]
        assertEquals("track_1", first.id)
        assertEquals("Creep", first.title)
        assertEquals("Radiohead", first.artist)
        assertEquals("Pablo Honey", first.album)
        assertEquals(239L, first.duration)
        assertEquals("https://img.youtube.com/vi/track_1/hqdefault.jpg", first.thumbnail)
        assertEquals(TrackSource.YOUTUBE, first.source)
        assertEquals("Radiohead", first.channelTitle)
        assertEquals("1.2B", first.views)
        assertEquals(0xFF1E2430.toInt(), first.dominantColor)

        val second = deserialized[1]
        assertEquals("track_2", second.id)
        assertEquals("Love Me Not", second.title)
        assertEquals("Ravyn Lenae", second.artist)
        assertEquals("Bird's Eye", second.album)
        assertEquals(214L, second.duration)
        assertEquals(TrackSource.LOCAL, second.source)
        assertNull(second.dominantColor)
    }

    @Test
    fun `deserializeTracks gracefully handles invalid or empty json`() {
        assertEquals(emptyList<Track>(), QueueDataStore.deserializeTracks(""))
        assertEquals(emptyList<Track>(), QueueDataStore.deserializeTracks("invalid json"))
        assertEquals(emptyList<Track>(), QueueDataStore.deserializeTracks("[]"))
        assertEquals(emptyList<Track>(), QueueDataStore.deserializeTracks("{}"))
    }

    @Test
    fun `PersistedQueue defaults hold expected values`() {
        val queue = PersistedQueue()
        assertTrue(queue.tracks.isEmpty())
        assertEquals(-1, queue.currentIndex)
        assertEquals(0L, queue.lastPositionMs)
        assertFalse(queue.isUserQueue)
        assertFalse(queue.isShuffled)
        assertEquals("OFF", queue.repeatMode)
    }
}
