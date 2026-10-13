package com.auralis.music.windows.player

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import com.auralis.music.desktop.playback.DesktopPlaybackController.Queue
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class QueueHistoryTest {
    private val tracks = (0..11).map { Track("song$it", "Song $it", "Artist") }

    @Test fun skippedSongsAndUnplayedDuplicateOccurrencesNeverBecomeHistory() {
        val jumped = Queue(tracks, 2, setOf(0, 2))
        assertEquals(listOf(0), jumped.history.map { it.index })
        assertEquals(listOf(0), jumped.copy(tracks = listOf(tracks[0], tracks[0], tracks[2])).history.map { it.index })
        assertTrue(Queue(tracks, 8).history.isEmpty(), "Starting midway through a list has no listened history")
    }

    @Test fun jumpingAheadRetainsAllUnplayedSongsForNextPlayback() {
        val initial = Queue(tracks.take(4), 0, setOf(0))
        val jumped = initial.copy(index = 2).retainUnplayed()
        assertEquals(listOf("song0", "song2", "song1", "song3"), jumped.tracks.map { it.id })
        assertEquals("song2", jumped.current?.id)
        assertEquals(listOf("song0"), jumped.history.map { it.value.id })
        assertEquals(listOf("song1", "song3"), jumped.tracks.drop(jumped.index + 1).map { it.id })
        val partlyHeard = jumped.copy(listenedIndices = jumped.listenedIndices + jumped.index)
        val next = partlyHeard.afterFinished(com.auralis.music.desktop.playback.DesktopPlaybackController.RepeatMode.OFF)!!.retainUnplayed()
        assertEquals("song1", next.current?.id, "Next must play the first retained song")
        assertEquals(listOf("song0", "song2"), next.history.map { it.value.id })
        assertEquals(initial.tracks.map { it.id }.sorted(), next.tracks.map { it.id }.sorted())
    }

    @Test fun duplicateOccurrencesAndUnplayedCurrentSongsAreRetained() {
        val initial = Queue(listOf(tracks[0], tracks[0], tracks[2], tracks[3]), 0, setOf(0))
        val jumped = initial.copy(index = 3).retainUnplayed()
        assertEquals(listOf("song0", "song3", "song0", "song2"), jumped.tracks.map { it.id })
        val skippedBeforePlaying = jumped.copy(index = 3).retainUnplayed()
        assertEquals(listOf("song0", "song2", "song3", "song0"), skippedBeforePlaying.tracks.map { it.id })
        assertEquals(setOf(0), skippedBeforePlaying.listenedIndices)
    }

    @Test fun clearingHistoryKeepsUnplayedSongsAndCurrentListeningState() {
        val queue = Queue(tracks.take(4), 2, setOf(0, 2)).retainUnplayed().withoutHistory()
        assertEquals(listOf("song2", "song1", "song3"), queue.tracks.map { it.id })
        assertEquals(0, queue.index)
        assertEquals(setOf(0), queue.listenedIndices)
        assertTrue(queue.history.isEmpty())
        assertEquals(queue, queue.retainUnplayed(), "Repeated normalization preserves order and state")
    }

    @Test fun steppingBackThroughHeardSongsPreservesPreviousAndNextOrder() {
        val played = Queue(tracks.take(4), 3, (0..3).toSet())
        val previous = played.copy(index = 2).retainUnplayed()
        val previousAgain = previous.copy(index = 1).retainUnplayed()
        assertEquals("song2", previous.current?.id)
        assertEquals("song1", previousAgain.current?.id)
        assertEquals(played.tracks, previousAgain.tracks)
        assertEquals(played.listenedIndices, previousAgain.listenedIndices)
    }

    @Test fun opensAtContinuePlayingWithShortUpcomingListAndHistoryStaysAbove() {
        val queue = Queue(tracks, 11, (0..10).toSet())
        val state = LazyListState()
        val scene = ImageComposeScene(400, 600, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { QueueList(queue, false, {}, {}, {}, {}, listState = state) }
        }
        try {
            repeat(10) { scene.render(it * 16_666_667L).close() }
            assertEquals(12, state.firstVisibleItemIndex, "History header and 11 listened rows should start above the viewport")
            assertEquals("continue_header", state.layoutInfo.visibleItemsInfo.first().key)
            runBlocking { state.scrollToItem(0) }
            repeat(5) { scene.render((it + 10) * 16_666_667L).close() }
            assertEquals("history_header", state.layoutInfo.visibleItemsInfo.first().key)
        } finally { scene.close() }
    }
}
