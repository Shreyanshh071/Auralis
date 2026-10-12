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
