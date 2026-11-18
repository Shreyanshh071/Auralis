package com.auralis.music.windows.history

import com.auralis.music.desktop.playback.DesktopPlaybackController.Queue
import com.auralis.music.desktop.playback.VlcAudioPlayer.State
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EstablishedHistoryPlaysTest {
    @Test fun sessionChangingBeforeQueueDoesNotLoseTheNewSong() = runTest {
        val old = Track("old", "Old", "A"); val next = Track("next", "Next", "A")
        val queue = MutableStateFlow(Queue(listOf(old), 0))
        val state = MutableStateFlow(State.PLAYING)
        val session = MutableStateFlow(1L)
        val established = MutableStateFlow<String?>(old.id)
        val heard = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            establishedHistoryPlays(queue, state, session, established).collect { heard += it.second.id }
        }
        runCurrent()
        session.value = 2L; runCurrent()
        established.value = null; runCurrent()
        queue.value = Queue(listOf(next), 0); runCurrent()
        established.value = next.id; runCurrent()
        assertEquals(next.id, heard.last())
        val count = heard.size
        state.value = State.PAUSED; runCurrent()
        state.value = State.PLAYING; runCurrent()
        assertEquals(count, heard.size, "pause/resume must not record another play")
    }
}
