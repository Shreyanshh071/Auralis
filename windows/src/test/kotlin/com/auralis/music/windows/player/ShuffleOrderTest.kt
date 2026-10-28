package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.DesktopPlaybackController.Queue
import com.auralis.music.domain.model.Track
import kotlin.test.*

class ShuffleOrderTest {
    private val a = Track("a", "A", "Artist")
    private val b = Track("b", "B", "Artist")
    private val c = Track("c", "C", "Artist")

    @Test fun restoresSourceOrderAndKeepsTheCurrentSongAndListenedMarkers() {
        val restored = Queue(listOf(c, a, b), 0, setOf(0, 1)).inSourceOrder(listOf(a, b, c))
        assertEquals(listOf(a, b, c), restored.tracks)
        assertEquals(c, restored.current)
        assertEquals(setOf(0, 2), restored.listenedIndices)
    }

    @Test fun preservesAddedAndDuplicateOccurrencesWithoutRevivingRemovedSongs() {
        val restored = Queue(listOf(c, a, a), 2).inSourceOrder(listOf(a, b, a))
        assertEquals(listOf(a, a, c), restored.tracks)
        assertEquals(1, restored.index)
    }
}
