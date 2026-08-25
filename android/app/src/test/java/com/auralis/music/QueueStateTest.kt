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
}
