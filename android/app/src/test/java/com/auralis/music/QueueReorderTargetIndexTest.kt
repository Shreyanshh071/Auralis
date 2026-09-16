package com.auralis.music

import com.auralis.music.ui.components.ReorderItemBounds
import com.auralis.music.ui.components.calculateReorderTargetIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueReorderTargetIndexTest {

    private val itemH = 64f

    private fun createVisibleItems(startIdx: Int, count: Int, startOffset: Int = 0, itemSize: Int = 64): List<ReorderItemBounds> {
        return (0 until count).map { i ->
            val idx = startIdx + i
            ReorderItemBounds(
                index = idx,
                key = "track_$idx",
                offset = startOffset + (i * itemSize),
                size = itemSize
            )
        }
    }

    @Test
    fun edgeCase1_smallDragDoesNotChangeTargetIndex() {
        val visible = createVisibleItems(startIdx = 0, count = 6)
        val target = calculateReorderTargetIndex(
            currentPointerY = 42f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 0,
            draggingKey = "track_0",
            visibleItems = visible,
            totalItemCount = 10
        )
        assertEquals(0, target)
    }

    @Test
    fun edgeCase2_dragDownwardAcrossAdjacentItemSwapsWhenPassingMidpoint() {
        val visible = createVisibleItems(startIdx = 0, count = 6)
        val target = calculateReorderTargetIndex(
            currentPointerY = 97f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 0,
            draggingKey = "track_0",
            visibleItems = visible,
            totalItemCount = 10
        )
        assertEquals(1, target)
    }

    @Test
    fun edgeCase3_dragUpwardAcrossAdjacentItemSwapsWhenPassingMidpoint() {
        val visible = createVisibleItems(startIdx = 0, count = 6)
        val target = calculateReorderTargetIndex(
            currentPointerY = 95f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 2,
            draggingKey = "track_2",
            visibleItems = visible,
            totalItemCount = 10
        )
        assertEquals(1, target)
    }

    @Test
    fun edgeCase4and6_autoScrollDownwardWhenOriginalItemIsScrolledOffScreen() {
        val visible = createVisibleItems(startIdx = 50, count = 8, startOffset = 0, itemSize = 64)
        val target = calculateReorderTargetIndex(
            currentPointerY = 360f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 5,
            draggingKey = "track_5",
            visibleItems = visible,
            totalItemCount = 400
        )
        assertEquals(55, target)
    }

    @Test
    fun edgeCase5and7_autoScrollUpwardWhenOriginalItemIsScrolledOffScreen() {
        val visible = createVisibleItems(startIdx = 20, count = 8, startOffset = 0, itemSize = 64)
        val target = calculateReorderTargetIndex(
            currentPointerY = 150f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 120,
            draggingKey = "track_120",
            visibleItems = visible,
            totalItemCount = 400
        )
        assertEquals(22, target)
    }

    @Test
    fun edgeCase8_large400SongQueueReorderingToFarDistance() {
        val visible = createVisibleItems(startIdx = 380, count = 10, startOffset = 100, itemSize = 70)
        val target = calculateReorderTargetIndex(
            currentPointerY = 490f,
            grabOffsetY = 35f,
            itemHeight = 70f,
            currentIdx = 12,
            draggingKey = "track_12",
            visibleItems = visible,
            totalItemCount = 400
        )
        assertEquals(385, target)
    }

    @Test
    fun edgeCase9_dragPastVisibleTopEdgeClampsToFirstVisibleIndexOrZero() {
        val visible = createVisibleItems(startIdx = 0, count = 5, startOffset = 0, itemSize = 64)
        val target = calculateReorderTargetIndex(
            currentPointerY = -50f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 3,
            draggingKey = "track_3",
            visibleItems = visible,
            totalItemCount = 100
        )
        assertEquals(0, target)
    }

    @Test
    fun edgeCase10_dragPastVisibleBottomEdgeClampsToLastVisibleIndex() {
        val visible = createVisibleItems(startIdx = 50, count = 5, startOffset = 0, itemSize = 64)
        val target = calculateReorderTargetIndex(
            currentPointerY = 1000f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 50,
            draggingKey = "track_50",
            visibleItems = visible,
            totalItemCount = 100
        )
        assertEquals(54, target)
    }

    @Test
    fun edgeCase11_naturalHysteresisPreventsOscillationWithoutTimerThrottle() {
        val visible = createVisibleItems(startIdx = 0, count = 2)

        val target1 = calculateReorderTargetIndex(
            currentPointerY = 97f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 0,
            draggingKey = "track_0",
            visibleItems = visible,
            totalItemCount = 2
        )
        assertEquals(1, target1)

        val updatedVisible = listOf(
            ReorderItemBounds(index = 0, key = "track_1", offset = 0, size = 64),
            ReorderItemBounds(index = 1, key = "track_0", offset = 64, size = 64)
        )

        val target2 = calculateReorderTargetIndex(
            currentPointerY = 97f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 1,
            draggingKey = "track_0",
            visibleItems = updatedVisible,
            totalItemCount = 2
        )
        assertEquals(1, target2)
    }

    @Test
    fun edgeCase12_emptyListAndInvalidIndexSafety() {
        val targetEmpty = calculateReorderTargetIndex(
            currentPointerY = 100f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 0,
            draggingKey = "track_0",
            visibleItems = emptyList(),
            totalItemCount = 0
        )
        assertEquals(0, targetEmpty)

        val targetOutOfRange = calculateReorderTargetIndex(
            currentPointerY = 100f,
            grabOffsetY = 32f,
            itemHeight = itemH,
            currentIdx = 50,
            draggingKey = "track_50",
            visibleItems = createVisibleItems(0, 5),
            totalItemCount = 10
        )
        assertEquals(50, targetOutOfRange)
    }
}
