package com.auralis.music

import com.auralis.music.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class AudioQueueManagerTest {

    private fun sampleTrack(id: String, title: String) = Track(
        id = id,
        title = title,
        artist = "Artist $id",
        duration = 180,
        thumbnail = "https://example.com/$id.jpg"
    )

    @Test
    fun `setQueue initializes queue and sets active track`() {
        val manager = AudioQueueManager()
        val tracks = listOf(sampleTrack("1", "Song 1"), sampleTrack("2", "Song 2"), sampleTrack("3", "Song 3"))

        val state = manager.setQueue(tracks, startIndex = 1)
        assertEquals(3, state.queue.size)
        assertEquals(1, state.currentIndex)
        assertEquals("Song 2", state.currentTrack?.title)
        assertTrue(state.hasNext)
        assertTrue(state.hasPrevious)
    }

    @Test
    fun `advanceNext and advancePrevious respect repeat modes`() {
        val manager = AudioQueueManager()
        val tracks = listOf(sampleTrack("1", "Song 1"), sampleTrack("2", "Song 2"))
        manager.setQueue(tracks, 0)

        // RepeatMode.OFF -> 0 -> 1 -> null
        assertEquals("Song 2", manager.advanceNext()?.title)
        assertNull(manager.advanceNext())

        // RepeatMode.ALL -> wraps around
        manager.setRepeatMode(RepeatMode.ALL)
        assertEquals("Song 1", manager.advanceNext()?.title)

        // RepeatMode.ONE -> repeats current track
        manager.setRepeatMode(RepeatMode.ONE)
        assertEquals("Song 1", manager.advanceNext()?.title)
    }

    @Test
    fun `toggleShuffle preserves current playing track at index 0 and un-shuffles back to original order`() {
        val manager = AudioQueueManager()
        val tracks = (1..10).map { sampleTrack(it.toString(), "Song $it") }
        manager.setQueue(tracks, startIndex = 3) // Playing "Song 4"

        val shuffled = manager.toggleShuffle()
        assertTrue(shuffled.isShuffled)
        assertEquals("Song 4", shuffled.currentTrack?.title)
        assertEquals("Song 4", shuffled.queue[0].title)
        assertEquals(0, shuffled.currentIndex)

        val unShuffled = manager.toggleShuffle()
        assertFalse(unShuffled.isShuffled)
        assertEquals("Song 1", unShuffled.queue[0].title)
        assertEquals("Song 4", unShuffled.currentTrack?.title)
        assertEquals(3, unShuffled.currentIndex)
    }

    @Test
    fun `playNext inserts track immediately after current track`() {
        val manager = AudioQueueManager()
        val tracks = listOf(sampleTrack("1", "Song 1"), sampleTrack("2", "Song 2"))
        manager.setQueue(tracks, startIndex = 0)

        val nextTrack = sampleTrack("inserted", "Priority Track")
        val state = manager.playNext(nextTrack)

        assertEquals(3, state.queue.size)
        assertEquals("Priority Track", state.queue[1].title)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `addToQueue appends track to end of queue`() {
        val manager = AudioQueueManager()
        val tracks = listOf(sampleTrack("1", "Song 1"), sampleTrack("2", "Song 2"))
        manager.setQueue(tracks, startIndex = 0)

        val endTrack = sampleTrack("last", "Queued Track")
        val state = manager.addToQueue(endTrack)

        assertEquals(3, state.queue.size)
        assertEquals("Queued Track", state.queue[2].title)
    }
}
