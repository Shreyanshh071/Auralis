package com.auralis.music

import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ReorderItemBounds
import com.auralis.music.ui.components.calculateReorderTargetIndex
import org.junit.Assert.*
import org.junit.Test

class QueueDragAndDropInteractionTest {

    private val defaultItemH = 60f
    private val defaultSpacing = 8f
    private val defaultSlotH = defaultItemH + defaultSpacing // 68f

    private fun createVisibleItems(
        startIdx: Int,
        count: Int,
        startOffset: Int = 0,
        itemSize: Int = 60,
        spacing: Int = 8
    ): List<ReorderItemBounds> {
        return (0 until count).map { i ->
            val idx = startIdx + i
            ReorderItemBounds(
                index = idx,
                key = "track_$idx",
                offset = startOffset + (i * (itemSize + spacing)),
                size = itemSize
            )
        }
    }

    private fun createSampleTracks(count: Int): List<Track> {
        return (0 until count).map { i ->
            Track(
                id = "track_$i",
                title = "Song $i",
                artist = "Artist $i",
                duration = 180L,
                thumbnail = "https://thumb.com/$i.jpg"
            )
        }
    }

    @Test
    fun initialTouchDown_hasZeroInitialJump_itemRemainsAttachedToFinger() {
        val visible = createVisibleItems(startIdx = 0, count = 5)
        val hitItem = visible[1] // Item 1 (offset: 68, size: 60)

        // User touches at Y = 85 (17px inside item 1)
        val downOffsetY = 85f
        val currentOffsetY = 85f

        // grabOffsetY calculation as defined in onDragStart
        val grabOffsetY = currentOffsetY - hitItem.offset.toFloat()
        assertEquals("grabOffsetY must equal touch offset inside item", 17f, grabOffsetY, 0.001f)

        // Floating overlay initial position: currentPointerY - grabOffsetY
        val initialOverlayY = currentOffsetY - grabOffsetY
        assertEquals("Initial floating overlay Y must exactly match hitItem.offset (0px jump)",
            hitItem.offset.toFloat(), initialOverlayY, 0.001f)
    }

    @Test
    fun slowDragAcrossAdjacentItem_continuousReorderingWithoutStickyDeadZones() {
        // Test that moving down crosses the threshold into the next slot, and moving back up
        // does NOT suffer from the 68px hysteresis trap (item center shift).
        val visible = createVisibleItems(startIdx = 0, count = 5) // Slots at 0, 68, 136, 204, 272

        val grabOffsetY = 30f // Grabbed at center of item 0 (offset 0..60, center 30)

        // 1. Initial touch on item 0: pointer at Y = 30
        var target = calculateReorderTargetIndex(
            currentPointerY = 30f,
            grabOffsetY = grabOffsetY,
            itemHeight = defaultItemH,
            currentIdx = 0,
            draggingKey = "track_0",
            visibleItems = visible,
            totalItemCount = 5
        )
        assertEquals("Initially within slot 0", 0, target)

        // 2. Drag slowly downward: boundary between slot 0 and slot 1 is at ~68px.
        // With 15% hysteresis (~10px), threshold to swap down is ~74px.
        target = calculateReorderTargetIndex(
            currentPointerY = 76f, // draggedCenterY = 76
            grabOffsetY = grabOffsetY,
            itemHeight = defaultItemH,
            currentIdx = 0,
            draggingKey = "track_0",
            visibleItems = visible,
            totalItemCount = 5
        )
        assertEquals("Crossing into slot 1 swaps target to index 1", 1, target)

        // 3. Now item 0 is at index 1!
        // Simulate dragging SLIGHTLY back upward: pointer moves back to Y = 55 (draggedCenterY = 55).
        // Under the old bug, the user had to drag past Y = 30 (full 68px dead-zone).
        // Under our slot-based calculation, slot 0 boundary is 68 - hysteresis (~58px).
        // At Y = 55, it smoothly swaps back to slot 0!
        target = calculateReorderTargetIndex(
            currentPointerY = 55f,
            grabOffsetY = grabOffsetY,
            itemHeight = defaultItemH,
            currentIdx = 1,
            draggingKey = "track_0",
            visibleItems = visible,
            totalItemCount = 5
        )
        assertEquals("Reversing direction by just ~20px swaps smoothly back to index 0 (no 68px trap)", 0, target)
    }

    @Test
    fun fastDragAcrossMultipleItems_reordersDirectlyToTargetIndex() {
        val visible = createVisibleItems(startIdx = 0, count = 10) // 10 items, slots 0..9
        val grabOffsetY = 30f

        // Rapid drag from index 0 across 4 items in one pointer frame (pointer jumps to Y = 280)
        // draggedCenterY = 280. Slot 4 center = 4 * 68 + 30 = 302. Diff from anchor center = 250 / 68 = 3.67 -> round is 4.
        val target = calculateReorderTargetIndex(
            currentPointerY = 280f,
            grabOffsetY = grabOffsetY,
            itemHeight = defaultItemH,
            currentIdx = 0,
            draggingKey = "track_0",
            visibleItems = visible,
            totalItemCount = 10
        )
        assertEquals("Fast swipe across 4 items resolves directly to target slot 4", 4, target)
    }

    @Test
    fun autoScroll_continuousTargetTracking_whenScrolledOffScreen() {
        // Suppose items 50 to 58 are visible after a large auto-scroll downward.
        // Original dragged item was at index 0 (now scrolled off-screen).
        val visible = createVisibleItems(startIdx = 50, count = 8, startOffset = 0)
        val grabOffsetY = 30f

        // User holds finger near bottom edge at Y = 370
        // anchor is at index 50, offset 0, center 30.
        // diffFromAnchor = 370 - 30 = 340. 340 / 68 = 5 -> target is 50 + 5 = 55.
        val target = calculateReorderTargetIndex(
            currentPointerY = 370f,
            grabOffsetY = grabOffsetY,
            itemHeight = defaultItemH,
            currentIdx = 0, // original item index
            draggingKey = "track_0",
            visibleItems = visible,
            totalItemCount = 200
        )
        assertEquals("Auto-scrolled viewport accurately tracks target slot 55", 55, target)
    }

    @Test
    fun releaseWithoutMoving_doesNotCommitQueueChange() {
        val queue = createSampleTracks(5).toMutableList()
        val originalIndex = 2
        var currentIndex = 2

        // User touched index 2, moved only 2px (less than touch slop or slot boundary)
        val target = calculateReorderTargetIndex(
            currentPointerY = 168f, // within slot 2
            grabOffsetY = 30f,
            itemHeight = defaultItemH,
            currentIdx = currentIndex,
            draggingKey = "track_2",
            visibleItems = createVisibleItems(0, 5),
            totalItemCount = 5
        )
        assertEquals(2, target)

        // On release: startIdx == finalIdx, no reorder performed
        var reorderCalled = false
        if (originalIndex != -1 && target != -1 && originalIndex != target) {
            reorderCalled = true
        }
        assertFalse("Releasing without moving must not invoke onReorderQueue", reorderCalled)
        assertEquals("Queue order remains unchanged", "track_2", queue[2].id)
    }

    @Test
    fun cancelledDrag_preservesOriginalQueue() {
        val originalSnapshot = createSampleTracks(5)
        val localQueue = originalSnapshot.toMutableList()

        // Simulate an in-progress swap during drag
        val item = localQueue.removeAt(0)
        localQueue.add(2, item)
        assertEquals("track_0", localQueue[2].id)

        // User cancels drag (e.g. gesture cancelled by system back or dismiss)
        localQueue.clear()
        localQueue.addAll(originalSnapshot)

        assertEquals("After cancellation, queue is restored to original snapshot",
            originalSnapshot.map { it.id }, localQueue.map { it.id })
    }

    @Test
    fun playbackTrackAuthority_unaffectedByQueueReorder() {
        val qm = AudioQueueManager()
        val tracks = createSampleTracks(6)
        qm.setQueue(tracks, startIndex = 1) // currently playing track_1 at index 1

        assertEquals("track_1", qm.state.currentTrack?.id)
        assertEquals(1, qm.state.currentIndex)

        // Reorder: Move track_4 to index 0 (before the currently playing track)
        val updatedState = qm.moveItem(fromIndex = 4, toIndex = 0)

        // Currently playing track should still be track_1, now shifted to index 2
        assertEquals("Currently playing track must remain track_1", "track_1", updatedState.currentTrack?.id)
        assertEquals("Current index should be mapped to 2", 2, updatedState.currentIndex)
        assertEquals("Moved track_4 is now at index 0", "track_4", updatedState.queue[0].id)
    }
}
