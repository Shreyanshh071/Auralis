package com.auralis.music.windows

import com.auralis.music.desktop.playback.DesktopPlaybackController.Queue
import com.auralis.music.desktop.playback.DesktopPlaybackController.RepeatMode
import com.auralis.music.domain.model.Track
import kotlin.test.*

class RepeatPlaybackTest {
    private val tracks = listOf(Track(id = "a", title = "A", artist = "Artist"), Track(id = "b", title = "B", artist = "Artist"))
    @Test fun offAdvancesAndStopsAtEnd() {
        assertEquals(1, Queue(tracks, 0).afterFinished(RepeatMode.OFF)?.index)
        assertNull(Queue(tracks, 1).afterFinished(RepeatMode.OFF))
    }
    @Test fun repeatAllWrapsWithoutChangingOrder() {
        assertEquals(Queue(tracks, 0), Queue(tracks, 1).afterFinished(RepeatMode.ALL))
        assertEquals(0, Queue(tracks.take(1), 0).afterFinished(RepeatMode.ALL)?.index)
    }
    @Test fun repeatOneKeepsCurrentRecordingAndEmptyQueuesNeverRestart() {
        assertEquals(Queue(tracks, 1), Queue(tracks, 1).afterFinished(RepeatMode.ONE))
        RepeatMode.entries.forEach { assertNull(Queue().afterFinished(it)) }
    }
}
