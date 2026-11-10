package com.auralis.music

import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.Track
import org.junit.Assert.*
import org.junit.Test

class QueueListeningHistoryTest {
    private val songs = (0..4).map { Track("song$it", "Song $it", "Artist") }

    @Test fun startingPartwayThroughAListDoesNotInventHistoryOrChangeItsOrder() {
        val manager = AudioQueueManager()
        val state = manager.setQueue(songs, 3)
        assertTrue(state.listenedIndices.isEmpty())
        assertEquals(songs, state.queue)
        manager.markCurrentListened()
        manager.advanceNext()
        assertEquals(setOf(3), manager.state.listenedIndices)
        assertEquals(songs[4], manager.state.currentTrack)
    }

    @Test fun jumpingAheadContinuesFromTheChosenSongAndSkippedSongsAreNotHistory() {
        // Playing the 1st of 6, tap the 4th: Up next is 5, 6 (not 2, 3, 5, 6).
        val six = (1..6).map { Track("song$it", "Song $it", "Artist") }
        val manager = AudioQueueManager()
        manager.setQueue(six)
        manager.markCurrentListened()
        val jumped = manager.selectIndex(3)
        assertEquals(six, jumped.queue)
        assertEquals(3, jumped.currentIndex)
        assertEquals(listOf("song5", "song6"), jumped.queue.drop(jumped.currentIndex + 1).map { it.id })
        assertEquals(setOf(0), jumped.listenedIndices)
        manager.markCurrentListened()
        manager.advanceNext()
        assertEquals(six[4], manager.state.currentTrack)
        assertEquals(setOf(0, 3), manager.state.listenedIndices)
    }

    @Test fun duplicateOccurrencesHaveIndependentHistoryAndCanBeSelected() {
        val manager = AudioQueueManager()
        manager.setQueue(listOf(songs[0], songs[0], songs[2], songs[3]))
        manager.markCurrentListened()
        manager.selectIndex(3)
        assertEquals(listOf(songs[0], songs[0], songs[2], songs[3]), manager.state.queue)
        assertEquals(setOf(0), manager.state.listenedIndices)
        manager.selectIndex(2)
        assertEquals(2, manager.state.currentIndex)
        assertFalse(2 in manager.state.listenedIndices)
    }

    @Test fun queueEditsRemapHeardOccurrencesWithoutMarkingInsertedSongs() {
        val manager = AudioQueueManager()
        manager.setQueue(songs)
        manager.markCurrentListened()
        manager.advanceNext()
        manager.markCurrentListened()
        manager.playNext(songs[4])
        assertEquals(setOf(0, 1), manager.state.listenedIndices)
        manager.moveItem(0, 3)
        assertEquals(setOf(0, 3), manager.state.listenedIndices)
        manager.removeItem(0)
        assertEquals(setOf(2), manager.state.listenedIndices)
        manager.addToQueue(songs[0])
        assertEquals(setOf(3), manager.state.listenedIndices)
    }

    @Test fun newQueueResetsHistoryAndRestoringRejectsInvalidPositions() {
        val manager = AudioQueueManager()
        manager.setQueue(songs)
        manager.restoreListenedIndices(setOf(-1, 0, 2, 100))
        assertEquals(setOf(0, 2), manager.state.listenedIndices)
        manager.toggleShuffle()
        assertEquals(setOf(songs[0], songs[2]), manager.state.listenedIndices.map { manager.state.queue[it] }.toSet())
        manager.setQueue(songs)
        assertTrue(manager.state.listenedIndices.isEmpty())
    }

    @Test fun clearingOnlyHeardRowsPreservesUnplayedPrefixAndCurrentSong() {
        val manager = AudioQueueManager()
        manager.setQueue(songs, 2)
        manager.markCurrentListened()
        manager.advanceNext()
        val history = (0 until manager.state.currentIndex).filter { it in manager.state.listenedIndices }
        history.asReversed().forEach(manager::removeItem)
        assertEquals(listOf(songs[0], songs[1], songs[3], songs[4]), manager.state.queue)
        assertEquals(songs[3], manager.state.currentTrack)
        assertTrue(manager.state.listenedIndices.isEmpty())
    }

    @Test fun steppingBackAndSequentialNextNeverReorderTheQueue() {
        val manager = AudioQueueManager()
        manager.setQueue(songs)
        repeat(3) { manager.markCurrentListened(); manager.advanceNext() }
        manager.advancePrevious()
        manager.advancePrevious()
        assertEquals(songs, manager.state.queue)
        assertEquals(songs[1], manager.state.currentTrack)
        assertEquals(setOf(0, 1, 2), manager.state.listenedIndices)
    }
}
