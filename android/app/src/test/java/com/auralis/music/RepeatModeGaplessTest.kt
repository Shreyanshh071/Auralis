package com.auralis.music

import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatModeGaplessTest {
    private val tracks = listOf("a", "b", "c").map { Track(id = it, title = it, artist = "Artist", duration = 200, thumbnail = "") }

    private fun queueAt(index: Int, mode: RepeatMode) = AudioQueueManager().apply {
        setQueue(tracks, index)
        setRepeatMode(mode)
    }

    @Test fun `repeat one preloads nothing so the next song cannot play on`() {
        assertNull(queueAt(0, RepeatMode.ONE).gaplessNextTrack())
    }

    @Test fun `repeat one finishing a song replays that song`() {
        assertEquals("b", queueAt(1, RepeatMode.ONE).advanceNext()?.id)
    }

    @Test fun `other modes preload the following song`() {
        assertEquals("b", queueAt(0, RepeatMode.OFF).gaplessNextTrack()?.id)
        assertEquals("b", queueAt(0, RepeatMode.ALL).gaplessNextTrack()?.id)
    }

    @Test fun `repeat all wraps from the last song to the first`() {
        val queue = queueAt(2, RepeatMode.ALL)
        assertNull(queue.gaplessNextTrack())
        assertEquals("a", queue.advanceNext()?.id)
    }
}
