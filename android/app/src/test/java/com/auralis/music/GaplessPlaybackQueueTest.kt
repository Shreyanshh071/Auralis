package com.auralis.music

import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.PlayerSettings
import com.auralis.music.domain.model.Track
import org.junit.Assert.*
import org.junit.Test

class GaplessPlaybackQueueTest {

    private fun sampleTrack(id: String, title: String) = Track(
        id = id,
        title = title,
        artist = "Artist $id",
        duration = 200,
        thumbnail = "https://example.com/$id.jpg"
    )

    @Test
    fun testGaplessQueueAdvance_TransitionsNextTrackCorrectly() {
        val manager = AudioQueueManager()
        val tracks = listOf(
            sampleTrack("t1", "Track 1"),
            sampleTrack("t2", "Track 2"),
            sampleTrack("t3", "Track 3")
        )
        manager.setQueue(tracks, startIndex = 0)

        assertEquals("t1", manager.state.currentTrack?.id)

        // Simulate upcoming track detection for pre-buffering
        val upcoming1 = manager.state.queue.getOrNull(manager.state.currentIndex + 1)
        assertNotNull(upcoming1)
        assertEquals("t2", upcoming1?.id)

        // Simulate seamless ExoPlayer onMediaItemTransition
        val nextTrack = manager.advanceNext()
        assertEquals("t2", nextTrack?.id)
        assertEquals(1, manager.state.currentIndex)

        // Upcoming for next cycle
        val upcoming2 = manager.state.queue.getOrNull(manager.state.currentIndex + 1)
        assertNotNull(upcoming2)
        assertEquals("t3", upcoming2?.id)

        // Second seamless transition
        val thirdTrack = manager.advanceNext()
        assertEquals("t3", thirdTrack?.id)
        assertEquals(2, manager.state.currentIndex)

        // End of queue has no upcoming track
        val upcoming3 = manager.state.queue.getOrNull(manager.state.currentIndex + 1)
        assertNull(upcoming3)
    }

    @Test
    fun testPlayerSettings_DefaultsAndUpdates() {
        val defaultSettings = PlayerSettings()
        assertTrue(defaultSettings.gaplessPlayback)
        assertFalse(defaultSettings.skipSilence)
        assertFalse(defaultSettings.spatialAudio)
        assertEquals(AudioQuality.AUTO, defaultSettings.audioQuality)

        val updatedSettings = defaultSettings.copy(
            gaplessPlayback = false,
            skipSilence = true,
            spatialAudio = true,
            audioQuality = AudioQuality.HIGH
        )
        assertFalse(updatedSettings.gaplessPlayback)
        assertTrue(updatedSettings.skipSilence)
        assertTrue(updatedSettings.spatialAudio)
        assertEquals(AudioQuality.HIGH, updatedSettings.audioQuality)
        assertEquals("High Quality", updatedSettings.audioQuality.displayName)
    }
}
