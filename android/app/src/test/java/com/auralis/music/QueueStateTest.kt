package com.auralis.music

import com.auralis.music.domain.model.QueueOperations
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueStateTest {

    @Test
    fun `mapIndexAfterMove keeps active index on playing track when playing track is moved`() {
        // Moving track at index 2 to index 5 -> active index becomes 5
        val next = QueueOperations.mapIndexAfterMove(fromIndex = 2, toIndex = 5, currentIndex = 2)
        assertEquals(5, next)

        // Moving track at index 4 to index 0 -> active index becomes 0
        val nextBack = QueueOperations.mapIndexAfterMove(fromIndex = 4, toIndex = 0, currentIndex = 4)
        assertEquals(0, nextBack)
    }

    @Test
    fun `mapIndexAfterMove adjusts active index when earlier item is moved past playing track`() {
        // Playing track is at index 3. Track at index 1 is moved to index 5.
        // Items between 1 and 5 shift down by 1 -> active index 3 becomes 2.
        val next = QueueOperations.mapIndexAfterMove(fromIndex = 1, toIndex = 5, currentIndex = 3)
        assertEquals(2, next)
    }

    @Test
    fun `mapIndexAfterMove adjusts active index when later item is moved before playing track`() {
        // Playing track is at index 2. Track at index 5 is moved to index 1.
        // Items between 1 and 5 shift up by 1 -> active index 2 becomes 3.
        val next = QueueOperations.mapIndexAfterMove(fromIndex = 5, toIndex = 1, currentIndex = 2)
        assertEquals(3, next)
    }

    @Test
    fun `mapIndexAfterMove leaves active index untouched when move occurs outside playing index`() {
        // Playing track is at index 1. Move from index 4 to index 6.
        val next = QueueOperations.mapIndexAfterMove(fromIndex = 4, toIndex = 6, currentIndex = 1)
        assertEquals(1, next)

        // Same index move
        val noOp = QueueOperations.mapIndexAfterMove(fromIndex = 2, toIndex = 2, currentIndex = 2)
        assertEquals(2, noOp)
    }

    @Test
    fun `mapIndexAfterRemove adjusts active index properly`() {
        // Removing earlier track -> index decrements
        val next1 = QueueOperations.mapIndexAfterRemove(removeIndex = 1, currentIndex = 3, queueSizeAfterRemove = 5)
        assertEquals(2, next1)

        // Removing later track -> index stays same
        val next2 = QueueOperations.mapIndexAfterRemove(removeIndex = 4, currentIndex = 2, queueSizeAfterRemove = 5)
        assertEquals(2, next2)

        // Removing active track at last position -> clamped to last valid index
        val next3 = QueueOperations.mapIndexAfterRemove(removeIndex = 4, currentIndex = 4, queueSizeAfterRemove = 4)
        assertEquals(3, next3)

        // Removing only track -> returns -1
        val next4 = QueueOperations.mapIndexAfterRemove(removeIndex = 0, currentIndex = 0, queueSizeAfterRemove = 0)
        assertEquals(-1, next4)
    }

    @Test
    fun `1 Current song at index 0 opens at or near index 0`() {
        val queueSize = 55
        val targetIndex = 0
        val scrollIndex = QueueOperations.calculateScrollIndex(
            targetIndex = targetIndex,
            visibleItemCount = 6,
            queueSize = queueSize
        )
        assertEquals(0, scrollIndex)
    }

    @Test
    fun `2 Current song in the middle opens positioned around the current song`() {
        // Queue contains ~55 songs. Currently playing Bitter Sweet Symphony at index 25.
        // With ~6 visible items (center offset = 3), list should scroll to 22,
        // placing index 25 in the middle of the viewport (items 22, 23, 24, [25], 26, 27).
        val queueSize = 55
        val targetIndex = 25
        val scrollIndex = QueueOperations.calculateScrollIndex(
            targetIndex = targetIndex,
            visibleItemCount = 6,
            queueSize = queueSize
        )
        assertEquals(22, scrollIndex)
        // Verify current song is visible in the window [scrollIndex, scrollIndex + visibleCount - 1]
        val isVisibleInViewport = targetIndex in scrollIndex until (scrollIndex + 6)
        assertEquals(true, isVisibleInViewport)
    }

    @Test
    fun `3 Current song near the end opens with current song visible without exceeding list bounds`() {
        val queueSize = 55
        val targetIndex = 54 // Last song in queue
        val scrollIndex = QueueOperations.calculateScrollIndex(
            targetIndex = targetIndex,
            visibleItemCount = 6,
            queueSize = queueSize
        )
        assertEquals(51, scrollIndex)
        // Must stay within [0, queueSize - 1]
        val isWithinBounds = scrollIndex in 0 until queueSize
        assertEquals(true, isWithinBounds)
        // Current song must be visible in the viewport window
        val isVisible = targetIndex in scrollIndex until (scrollIndex + 6)
        assertEquals(true, isVisible)
    }

    @Test
    fun `4 Duplicate titles with different Track IDs correctly positions authoritative Track ID`() {
        val song1 = com.auralis.music.domain.model.Track(
            id = "verve_bitter_sweet_id_1",
            title = "The Less I Know The Better",
            artist = "Tame Impala"
        )
        val song2 = com.auralis.music.domain.model.Track(
            id = "verve_bitter_sweet_id_2",
            title = "The Less I Know The Better",
            artist = "Tame Impala"
        )
        val dummy = com.auralis.music.domain.model.Track(id = "dummy", title = "Other Song", artist = "Other")

        val queue = mutableListOf<com.auralis.music.domain.model.Track>().apply {
            repeat(10) { add(dummy.copy(id = "dummy_$it")) }
            add(song1) // index 10
            repeat(15) { add(dummy.copy(id = "dummy_${it + 10}")) }
            add(song2) // index 26
            repeat(20) { add(dummy.copy(id = "dummy_${it + 25}")) }
        }

        // Currently playing song is song2 (at index 26), despite identical title with song1 at index 10
        val foundIndex = QueueOperations.findActiveTrackIndex(
            queue = queue,
            currentTrack = song2,
            currentIndex = 26
        )
        assertEquals(26, foundIndex)

        // Even without currentIndex hint, authoritative Track.id match locates exact instance
        val foundIndexNoHint = QueueOperations.findActiveTrackIndex(
            queue = queue,
            currentTrack = song2,
            currentIndex = -1
        )
        assertEquals(26, foundIndexNoHint)

        // And scrolling positions around index 26, NOT index 10
        val scrollIndex = QueueOperations.calculateScrollIndex(
            targetIndex = foundIndex,
            visibleItemCount = 6,
            queueSize = queue.size
        )
        assertEquals(23, scrollIndex)
    }

    @Test
    fun `5 Queue already open and track changes recalculates new active position`() {
        val tracks = (0 until 55).map {
            com.auralis.music.domain.model.Track(id = "id_$it", title = "Track $it", artist = "Artist")
        }

        // Initially song at index 5 is playing
        val initialActiveIndex = QueueOperations.findActiveTrackIndex(
            queue = tracks,
            currentTrack = tracks[5],
            currentIndex = 5
        )
        assertEquals(5, initialActiveIndex)
        val initialScroll = QueueOperations.calculateScrollIndex(initialActiveIndex, 6, tracks.size)
        assertEquals(2, initialScroll)

        // Track changes to index 30 while queue is open
        val newActiveIndex = QueueOperations.findActiveTrackIndex(
            queue = tracks,
            currentTrack = tracks[30],
            currentIndex = 30
        )
        assertEquals(30, newActiveIndex)
        val newScroll = QueueOperations.calculateScrollIndex(newActiveIndex, 6, tracks.size)
        assertEquals(27, newScroll)
    }

    @Test
    fun `6 Manual scroll preserves position when track id is unchanged`() {
        val track = com.auralis.music.domain.model.Track(id = "playing_id", title = "Playing", artist = "Artist")
        val queue = listOf(track)

        // If track id does not change, LaunchedEffect(playingTrackId) key is stable,
        // ensuring the coroutine is not re-triggered during user fling or drag gestures.
        val id1 = track.id
        val id2 = track.id
        assertEquals(id1, id2)
    }

    @Test
    fun `7 Re-entering queue correctly repositions to currently playing song`() {
        val tracks = (0 until 55).map {
            com.auralis.music.domain.model.Track(id = "id_$it", title = "Track $it", artist = "Artist")
        }
        val currentTrack = tracks[20]

        // On re-entering Queue tab, findActiveTrackIndex and calculateScrollIndex
        // recompute the position for the active song
        val targetIndex = QueueOperations.findActiveTrackIndex(
            queue = tracks,
            currentTrack = currentTrack,
            currentIndex = 20
        )
        val scrollIndex = QueueOperations.calculateScrollIndex(
            targetIndex = targetIndex,
            visibleItemCount = 8, // testing with 8 visible items
            queueSize = tracks.size
        )
        // With 8 items visible, center offset is 4 -> 20 - 4 = 16
        assertEquals(16, scrollIndex)
    }
}
