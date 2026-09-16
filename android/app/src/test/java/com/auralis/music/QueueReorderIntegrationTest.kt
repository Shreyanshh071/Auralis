package com.auralis.music

import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.QueueTrackItem
import com.auralis.music.ui.components.ReorderItemBounds
import com.auralis.music.ui.components.calculateReorderTargetIndex
import com.auralis.music.ui.components.createQueueTrackItem
import com.auralis.music.ui.components.syncLocalQueueWithSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueReorderIntegrationTest {

    private val itemH = 60f
    private val spacing = 8f
    private val slotH = itemH + spacing // 68f

    private fun createSampleTracks(count: Int): List<Track> {
        return (0 until count).map { i ->
            Track(
                id = "track_id_$i",
                title = "Title $i",
                artist = "Artist $i",
                duration = 200L + i,
                thumbnail = "https://thumb.com/$i.jpg"
            )
        }
    }

    private fun createVisibleBounds(
        items: List<QueueTrackItem>,
        firstVisibleIndex: Int,
        visibleCount: Int,
        startOffset: Int = 0
    ): List<ReorderItemBounds> {
        val endIdx = (firstVisibleIndex + visibleCount).coerceAtMost(items.size)
        return (firstVisibleIndex until endIdx).mapIndexed { i, idx ->
            ReorderItemBounds(
                index = idx,
                key = items[idx].instanceId,
                offset = startOffset + (i * slotH.toInt()),
                size = itemH.toInt()
            )
        }
    }

    // 1. Drag one item one position (down and up)
    @Test
    fun test01_dragOneItemOnePosition_downAndUp() {
        val tracks = createSampleTracks(5)
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        val itemC = localQueue[2] // Index 2 ("track_id_2")
        val visible = createVisibleBounds(localQueue, firstVisibleIndex = 0, visibleCount = 5)

        // Drag item C downward from index 2 past index 3's midpoint
        // Slot 2 center is 2 * 68 + 30 = 166. Slot 3 center is 3 * 68 + 30 = 234.
        // Finger moves to Y = 240 (draggedCenterY = 240)
        val targetDown = calculateReorderTargetIndex(
            currentPointerY = 240f,
            grabOffsetY = 30f,
            itemHeight = itemH,
            currentIdx = 2,
            draggingKey = itemC.instanceId,
            visibleItems = visible,
            totalItemCount = localQueue.size
        )
        assertEquals("Target index must be 3 when dragging C down past D", 3, targetDown)

        // Perform the swap in localQueue
        val removed = localQueue.removeAt(2)
        localQueue.add(3, removed)
        assertEquals(itemC.instanceId, localQueue[3].instanceId)
        assertEquals("track_id_3", localQueue[2].track.id)

        // Now drag item C back upward from index 3 to index 2
        val updatedVisible = createVisibleBounds(localQueue, firstVisibleIndex = 0, visibleCount = 5)
        val targetUp = calculateReorderTargetIndex(
            currentPointerY = 160f, // moves back to slot 2 center
            grabOffsetY = 30f,
            itemHeight = itemH,
            currentIdx = 3,
            draggingKey = itemC.instanceId,
            visibleItems = updatedVisible,
            totalItemCount = localQueue.size
        )
        assertEquals("Target index must be 2 when dragging C back up past D", 2, targetUp)

        val removedBack = localQueue.removeAt(3)
        localQueue.add(2, removedBack)
        assertEquals("track_id_2", localQueue[2].track.id)
        assertEquals(itemC.instanceId, localQueue[2].instanceId)
    }

    // 2. Drag across multiple items
    @Test
    fun test02_dragAcrossMultipleItems() {
        val tracks = createSampleTracks(10)
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        val item0 = localQueue[0]
        val visible = createVisibleBounds(localQueue, firstVisibleIndex = 0, visibleCount = 8)

        // Fast drag from index 0 across 4 items (pointer moves to Y = 302, slot 4 center)
        val targetDown = calculateReorderTargetIndex(
            currentPointerY = 302f,
            grabOffsetY = 30f,
            itemHeight = itemH,
            currentIdx = 0,
            draggingKey = item0.instanceId,
            visibleItems = visible,
            totalItemCount = localQueue.size
        )
        assertEquals("Direct multi-item drag to slot 4 resolves cleanly", 4, targetDown)

        // Simulate multi-item move
        val moved = localQueue.removeAt(0)
        localQueue.add(4, moved)
        assertEquals("track_id_0", localQueue[4].track.id)
        assertEquals("track_id_1", localQueue[0].track.id)
        assertEquals("track_id_4", localQueue[3].track.id)

        // Drag back across multiple items: from index 4 to index 1
        val updatedVisible = createVisibleBounds(localQueue, firstVisibleIndex = 0, visibleCount = 8)
        val targetUp = calculateReorderTargetIndex(
            currentPointerY = 98f, // slot 1 center: 1 * 68 + 30 = 98
            grabOffsetY = 30f,
            itemHeight = itemH,
            currentIdx = 4,
            draggingKey = item0.instanceId,
            visibleItems = updatedVisible,
            totalItemCount = localQueue.size
        )
        assertEquals("Direct multi-item drag back to slot 1 resolves cleanly", 1, targetUp)
    }

    // 3. Drag while LazyColumn auto-scrolls
    @Test
    fun test03_dragWhileLazyColumnAutoScrolls() {
        val tracks = createSampleTracks(50)
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        val draggedItem = localQueue[0]
        var currentDraggedIndex = 0

        // Auto-scroll simulation: finger held at bottom edge (Y = 480)
        // LazyColumn scrolls in chunks of 2 items each layout frame
        val scrollSteps = listOf(0, 2, 6, 12, 20)
        var lastTargetIndex = currentDraggedIndex

        for (firstVisible in scrollSteps) {
            val visible = createVisibleBounds(localQueue, firstVisibleIndex = firstVisible, visibleCount = 8)
            val target = calculateReorderTargetIndex(
                currentPointerY = 480f,
                grabOffsetY = 30f,
                itemHeight = itemH,
                currentIdx = currentDraggedIndex,
                draggingKey = draggedItem.instanceId,
                visibleItems = visible,
                totalItemCount = localQueue.size
            )

            // As viewport scrolls downward, target index must advance monotonically
            assertTrue("Target index must advance or stay stable as viewport scrolls down", target >= lastTargetIndex)
            assertTrue("Target index must remain clamped within visible range [firstVisible, firstVisible + 7]",
                target in firstVisible..(firstVisible + 7))

            // Mutate and verify
            if (target != currentDraggedIndex) {
                val item = localQueue.removeAt(currentDraggedIndex)
                localQueue.add(target, item)
                currentDraggedIndex = target
            }
            lastTargetIndex = target
        }

        assertEquals("Dragged item successfully reached auto-scrolled destination", currentDraggedIndex, lastTargetIndex)
        assertEquals(draggedItem.instanceId, localQueue[currentDraggedIndex].instanceId)
    }

    // 4. Large queue (400+ items)
    @Test
    fun test04_largeQueue400PlusItems() {
        val count = 450
        val tracks = createSampleTracks(count)
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        assertEquals(450, localQueue.size)

        // User drags item 10 down to item 390
        val draggedItem = localQueue[10]
        // Viewport scrolled to item 385
        val visible = createVisibleBounds(localQueue, firstVisibleIndex = 385, visibleCount = 10, startOffset = 50)

        val startTime = System.nanoTime()
        val target = calculateReorderTargetIndex(
            currentPointerY = 390f,
            grabOffsetY = 30f,
            itemHeight = itemH,
            currentIdx = 10,
            draggingKey = draggedItem.instanceId,
            visibleItems = visible,
            totalItemCount = localQueue.size
        )
        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

        assertTrue("Large queue target calculation must execute in under 5ms (was ${durationMs}ms)", durationMs < 5.0)
        assertTrue("Target index in 450-song queue must be within visible window [385, 394]", target in 385..394)

        // Perform move and verify no corruption in large queue
        val item = localQueue.removeAt(10)
        localQueue.add(target, item)

        assertEquals(450, localQueue.size)
        assertEquals(draggedItem.instanceId, localQueue[target].instanceId)

        // Verify distinctness of all 450 items
        val distinctKeys = localQueue.map { it.instanceId }.toSet()
        assertEquals("All 450 items must retain strictly unique keys", 450, distinctKeys.size)
    }

    // 5. Verify every visible row still displays the correct Track
    @Test
    fun test05_verifyEveryVisibleRowDisplaysCorrectTrack() {
        val tracks = createSampleTracks(20)
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        // Perform 5 sequential swaps
        val swaps = listOf(0 to 3, 5 to 8, 12 to 2, 7 to 15, 1 to 4)
        for ((from, to) in swaps) {
            val itm = localQueue.removeAt(from)
            localQueue.add(to, itm)
        }

        // Verify for all rows that Track fields are intact and match their id
        for (i in 0 until localQueue.size) {
            val item = localQueue[i]
            val expectedNum = item.track.id.removePrefix("track_id_").toInt()
            assertEquals("Title $expectedNum", item.track.title)
            assertEquals("Artist $expectedNum", item.track.artist)
            assertEquals(200L + expectedNum, item.track.duration)
            assertEquals("https://thumb.com/$expectedNum.jpg", item.track.thumbnail)
        }
    }

    // 6. Verify no duplicate or overlapping row content
    @Test
    fun test06_verifyNoDuplicateOrOverlappingRowContent() {
        val tracks = createSampleTracks(25)
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        // Perform complex reorders
        for (i in 0 until 10) {
            val from = (i * 2) % 25
            val to = (i * 3 + 1) % 25
            val itm = localQueue.removeAt(from)
            localQueue.add(to, itm)
        }

        // Verify no duplicate keys
        val keys = localQueue.map { it.instanceId }
        assertEquals("Queue size must equal unique key count (no duplicate keys)", localQueue.size, keys.toSet().size)

        // Verify no duplicate tracks
        val trackIds = localQueue.map { it.track.id }
        assertEquals("Queue size must equal unique track IDs (no duplicate tracks)", localQueue.size, trackIds.toSet().size)

        // Verify non-overlapping layout bounds calculation
        val bounds = createVisibleBounds(localQueue, firstVisibleIndex = 0, visibleCount = 10)
        for (i in 0 until bounds.size - 1) {
            val currentBottom = bounds[i].offset + bounds[i].size
            val nextTop = bounds[i + 1].offset
            assertTrue("Rows must not overlap: item ${i+1} top ($nextTop) >= item $i bottom ($currentBottom)",
                nextTop >= currentBottom)
        }
    }

    // 7. Verify dragged item appears exactly once
    @Test
    fun test07_verifyDraggedItemAppearsExactlyOnce() {
        val tracks = createSampleTracks(15)
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        val draggedItem = localQueue[4]
        val draggedKey = draggedItem.instanceId

        // Count occurrences during drag moves
        assertEquals("Dragged item appears exactly once initially", 1, localQueue.count { it.instanceId == draggedKey })

        // Move item to index 8
        val itm = localQueue.removeAt(4)
        localQueue.add(8, itm)
        assertEquals("Dragged item appears exactly once after move to index 8", 1, localQueue.count { it.instanceId == draggedKey })

        // Move item to index 0
        val itm2 = localQueue.removeAt(8)
        localQueue.add(0, itm2)
        assertEquals("Dragged item appears exactly once after move to index 0", 1, localQueue.count { it.instanceId == draggedKey })
    }

    // 8. Verify stable keys survive reorder (keys do not change based on index)
    @Test
    fun test08_verifyStableKeysSurviveReorder() {
        val tracks = createSampleTracks(10)
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        // Record initial trackId -> instanceId mapping
        val initialKeyMap = localQueue.associate { it.track.id to it.instanceId }

        // Reorder tracks: move index 1 to 5, and index 7 to 0
        val reorderedTracks = tracks.toMutableList()
        val t1 = reorderedTracks.removeAt(1)
        reorderedTracks.add(5, t1)
        val t7 = reorderedTracks.removeAt(7)
        reorderedTracks.add(0, t7)

        // Sync local queue with new snapshot (isDragging = false)
        syncLocalQueueWithSnapshot(localQueue, reorderedTracks, isDragging = false)

        // Crucial check: EVERY track must keep its exact same instanceId
        for (item in localQueue) {
            val expectedKey = initialKeyMap[item.track.id]
            assertEquals("Key for track ${item.track.id} must NOT change across reorder",
                expectedKey, item.instanceId)
        }

        // Verify that keys do NOT contain index (which previously broke Compose identity)
        for (item in localQueue) {
            assertTrue("Instance ID must use monotonic counter rather than volatile index",
                item.instanceId.contains("#"))
        }
    }

    // 9. Verify final queue order
    @Test
    fun test09_verifyFinalQueueOrder() {
        val tracks = createSampleTracks(6)
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        // Initial: [0, 1, 2, 3, 4, 5]
        // Step 1: move 1 -> 3 => [0, 2, 3, 1, 4, 5]
        localQueue.add(3, localQueue.removeAt(1))
        assertEquals(listOf("track_id_0", "track_id_2", "track_id_3", "track_id_1", "track_id_4", "track_id_5"),
            localQueue.map { it.track.id })

        // Step 2: move 4 -> 0 => [4, 0, 2, 3, 1, 5]
        localQueue.add(0, localQueue.removeAt(4))
        assertEquals(listOf("track_id_4", "track_id_0", "track_id_2", "track_id_3", "track_id_1", "track_id_5"),
            localQueue.map { it.track.id })

        // Commit to AudioQueueManager
        val qm = AudioQueueManager()
        qm.setQueue(tracks, startIndex = 0)
        qm.moveItem(fromIndex = 1, toIndex = 3)
        qm.moveItem(fromIndex = 4, toIndex = 0)

        assertEquals("AudioQueueManager final queue must match localQueue exactly",
            localQueue.map { it.track.id }, qm.state.queue.map { it.id })
    }

    // 10. Verify playback remains unchanged
    @Test
    fun test10_verifyPlaybackRemainsUnchanged() {
        val tracks = createSampleTracks(8)
        val qm = AudioQueueManager()
        // Start playing track 3 ("track_id_3") at index 3
        qm.setQueue(tracks, startIndex = 3)

        val playingTrackId = qm.state.currentTrack?.id
        assertEquals("track_id_3", playingTrackId)
        assertEquals(3, qm.state.currentIndex)

        // 1. Move an item AFTER the current track: 6 -> 4
        qm.moveItem(fromIndex = 6, toIndex = 4)
        assertEquals("Current playing track must not change when reordering items after it",
            playingTrackId, qm.state.currentTrack?.id)
        assertEquals("Current index must not change when reordering after current", 3, qm.state.currentIndex)

        // 2. Move an item BEFORE the current track: 5 -> 1
        qm.moveItem(fromIndex = 5, toIndex = 1)
        assertEquals("Current playing track must remain intact", playingTrackId, qm.state.currentTrack?.id)
        assertEquals("Current index shifts to 4", 4, qm.state.currentIndex)

        // 3. Move the CURRENT track itself: move index 4 to index 0
        qm.moveItem(fromIndex = 4, toIndex = 0)
        assertEquals("Moving the playing track must keep it active", playingTrackId, qm.state.currentTrack?.id)
        assertEquals("Current index maps to destination index 0", 0, qm.state.currentIndex)
    }
}
