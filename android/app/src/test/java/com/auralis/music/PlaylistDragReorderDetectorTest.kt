package com.auralis.music

import com.auralis.music.domain.library.PlaylistManager
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.library.PlaylistDragReorderState
import com.auralis.music.ui.library.PlaylistReorderItemInfo
import com.auralis.music.ui.library.applyTargetSwap
import com.auralis.music.ui.library.evaluateTargetSwap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for playlist drag-and-drop reorder logic:
 * - Hysteresis threshold to prevent micro-jitter
 * - Stale-layout detection to prevent k <-> k-1 bounce oscillation
 * - Direction reversal without permanent lockout
 * - Boundary hold stability
 * - Fast drag and auto-scroll stability
 * - Playlist persistence parity
 */
class PlaylistDragReorderDetectorTest {

    private val itemHeight = 100f
    private val hysteresis = 20f // ~8-10dp in pixels
    private val grabOffsetY = 50f // grabbed in exact vertical center of 100px row

    private fun createItems(count: Int): MutableList<String> {
        return (0 until count).map { "track_$it" }.toMutableList()
    }

    private fun buildLayout(items: List<String>): List<PlaylistReorderItemInfo> {
        return items.mapIndexed { index, id ->
            PlaylistReorderItemInfo(
                key = id,
                offset = index * itemHeight.toInt(),
                size = itemHeight.toInt()
            )
        }
    }

    // 1. Upward single swap: 10 -> 9 must perform exactly one swap
    @Test
    fun test1_upwardSingleSwap_10_to_9_mustPerformExactlyOneSwap() {
        val items = createItems(15)
        val state = PlaylistDragReorderState()
        val layout = buildLayout(items)

        // Item 9 center is 950. Threshold = 950 - 20 = 930.
        // Pointer at 940 (above 930): no swap yet.
        val noSwap = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 940f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertFalse("Pointer before threshold should not trigger swap", noSwap)
        assertEquals("track_10", items[10])

        // Pointer crosses threshold: 925 < 930 -> swap occurs
        val swapped = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 925f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue("Crossing upward threshold must trigger swap", swapped)
        assertEquals("track_10", items[9])
        assertEquals("track_9", items[10])
        assertEquals("track_9", state.lastSwappedItemId)
        assertEquals(-1, state.lastSwapDirection)
    }

    // 2. Stale-layout repeated event: after 10 -> 9, feed another event using stale pre-swap offsets -> must NOT swap 9 -> 10
    @Test
    fun test2_staleLayoutRepeatedEvent_mustNotSwapBack() {
        val items = createItems(15)
        val state = PlaylistDragReorderState()
        val staleLayout = buildLayout(items) // item 9 at offset 900, item 10 at offset 1000

        // Perform initial swap 10 -> 9
        val firstSwap = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = staleLayout,
            pointerY = 925f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue(firstSwap)
        assertEquals("track_10", items[9])
        assertEquals("track_9", items[10])

        // Feed subsequent event while layout is STILL stale (item_10 offset 1000 >= item_9 offset 900)
        // Pointer is at 925f (or jittered to 935f)
        for (pY in listOf(925f, 930f, 940f, 955f, 970f)) {
            val bounced = applyTargetSwap(
                currentId = "track_10",
                localItems = items,
                idSelector = { it },
                visibleSongItems = staleLayout, // still stale!
                pointerY = pY,
                grabOffsetY = grabOffsetY,
                fallbackItemHeight = itemHeight,
                swapHysteresisPx = hysteresis,
                reorderState = state
            )
            assertFalse("Stale layout must suppress immediate bounce swap", bounced)
            assertEquals("Item 10 must remain at index 9 during stale layout", "track_10", items[9])
            assertEquals("Item 9 must remain at index 10 during stale layout", "track_9", items[10])
        }
    }

    // 3. Continued upward drag: 10 -> 9 -> 8 -> 7 exactly three swaps
    @Test
    fun test3_continuedUpwardDrag_10_to_9_to_8_to_7_exactlyThreeSwaps() {
        val items = createItems(15)
        val state = PlaylistDragReorderState()

        // Swap 1: 10 -> 9
        var layout = buildLayout(items)
        val swap1 = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 920f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue(swap1)
        assertEquals(9, items.indexOf("track_10"))

        // Layout updates for frame 2: items[8]=track_8 (center 850), items[9]=track_10, items[10]=track_9
        layout = buildLayout(items)
        // Swap 2: 9 -> 8 (threshold: 850 - 20 = 830)
        val swap2 = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 820f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue(swap2)
        assertEquals(8, items.indexOf("track_10"))

        // Layout updates for frame 3: items[7]=track_7 (center 750), items[8]=track_10
        layout = buildLayout(items)
        // Swap 3: 8 -> 7 (threshold: 750 - 20 = 730)
        val swap3 = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 720f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue(swap3)
        assertEquals(7, items.indexOf("track_10"))

        assertEquals("track_7", items[8])
        assertEquals("track_8", items[9])
        assertEquals("track_9", items[10])
    }

    // 4. Downward regression: 10 -> 11 -> 12 exactly two swaps
    @Test
    fun test4_downwardRegression_10_to_11_to_12_exactlyTwoSwaps() {
        val items = createItems(15)
        val state = PlaylistDragReorderState()

        // Swap 1: 10 -> 11 (item 11 center = 1150. Threshold = 1150 + 20 = 1170)
        var layout = buildLayout(items)
        val swap1 = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 1180f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue(swap1)
        assertEquals(11, items.indexOf("track_10"))
        assertEquals("track_11", state.lastSwappedItemId)
        assertEquals(1, state.lastSwapDirection)

        // Layout updates: item 10 at index 11, item 12 at index 12 (center = 1250. Threshold = 1270)
        layout = buildLayout(items)
        val swap2 = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 1280f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue(swap2)
        assertEquals(12, items.indexOf("track_10"))
        assertEquals("track_12", state.lastSwappedItemId)
        assertEquals(1, state.lastSwapDirection)
    }

    // 5. Reverse direction: 10 -> 9, then genuinely reverse and return to 10. Must be possible without permanent lockout.
    @Test
    fun test5_reverseDirection_10_to_9_thenGenuinelyReverseTo10_noPermanentLockout() {
        val items = createItems(15)
        val state = PlaylistDragReorderState()

        // Upward swap: 10 -> 9
        var layout = buildLayout(items)
        val upwardSwap = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 920f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue(upwardSwap)
        assertEquals(9, items.indexOf("track_10"))

        // Layout refreshes with new positions:
        // track_10 is now at offset 900 (center 950)
        // track_9 is now at offset 1000 (center 1050)
        layout = buildLayout(items)

        // User changes mind and drags DOWN past track_9:
        // Threshold = nextCenterY + hysteresis = 1050 + 20 = 1070
        val reverseSwap = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 1080f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue("Genuine reverse movement must execute swap back", reverseSwap)
        assertEquals("Item 10 must return to index 10 without permanent lockout", 10, items.indexOf("track_10"))
        assertEquals("track_9", items[9])
        assertEquals("track_9", state.lastSwappedItemId)
        assertEquals(1, state.lastSwapDirection)
    }

    // 6. Hold at boundary: item near swap threshold remains stable and does not oscillate
    @Test
    fun test6_holdAtBoundary_itemNearSwapThreshold_remainsStableAndDoesNotOscillate() {
        val items = createItems(15)
        val state = PlaylistDragReorderState()

        // Swap 10 -> 9 at 925f
        var layout = buildLayout(items)
        val swap = applyTargetSwap(
            currentId = "track_10",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 925f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue(swap)

        // Simulate user hovering/holding thumb stationary near boundary for 30 consecutive frames
        // First 3 frames with stale layout, next 27 frames with updated layout
        var extraSwaps = 0
        for (frame in 1..30) {
            val currentLayout = if (frame <= 3) layout else buildLayout(items)
            // Thumb jitter +/- 3px around 925f
            val jitterY = 925f + ((frame % 5) - 2)
            val result = applyTargetSwap(
                currentId = "track_10",
                localItems = items,
                idSelector = { it },
                visibleSongItems = currentLayout,
                pointerY = jitterY,
                grabOffsetY = grabOffsetY,
                fallbackItemHeight = itemHeight,
                swapHysteresisPx = hysteresis,
                reorderState = state
            )
            if (result) extraSwaps++
        }

        assertEquals("Zero oscillation swaps while holding near boundary", 0, extraSwaps)
        assertEquals("track_10", items[9])
        assertEquals("track_9", items[10])
    }

    // 7. Fast upward drag: multiple upward swaps remain stable
    @Test
    fun test7_fastUpwardDrag_multipleUpwardSwapsRemainStable() {
        val items = createItems(15)
        val state = PlaylistDragReorderState()

        // Fast upward motion from index 10 up to index 5
        for (targetIndex in 9 downTo 5) {
            val layout = buildLayout(items)
            val targetCenter = targetIndex * itemHeight + (itemHeight / 2f)
            val pointerY = targetCenter - hysteresis - 5f

            val swapped = applyTargetSwap(
                currentId = "track_10",
                localItems = items,
                idSelector = { it },
                visibleSongItems = layout,
                pointerY = pointerY,
                grabOffsetY = grabOffsetY,
                fallbackItemHeight = itemHeight,
                swapHysteresisPx = hysteresis,
                reorderState = state
            )
            assertTrue("Fast drag upward must cleanly swap to index $targetIndex", swapped)
            assertEquals(targetIndex, items.indexOf("track_10"))
        }

        assertEquals(5, items.indexOf("track_10"))
    }

    // 8. Auto-scroll upward: no oscillating reorder
    @Test
    fun test8_autoScrollUpward_itemsShiftUnderStationaryPointer_noOscillation() {
        val items = createItems(15)
        val state = PlaylistDragReorderState()

        // Finger is stationary in top auto-scroll edge zone (pointerY = 150f)
        val pointerY = 150f
        // Start dragging track_5 (center 550f in global list, but with scroll offset it appears at 150f)
        // As auto-scroll runs, items scroll downward relative to viewport by scrollStep
        var scrollOffset = 400 // so track_5 is at 500 - 400 = 100 (center 150)
        var totalSwaps = 0

        // Track 5 starts at index 5
        var currentDraggedIndex = 5

        // Simulate 40 auto-scroll steps of 10px each
        for (step in 1..40) {
            scrollOffset -= 10 // list scrolls backward, items shift down in viewport

            val visibleItems = items.mapIndexed { idx, id ->
                PlaylistReorderItemInfo(
                    key = id,
                    offset = idx * 100 - scrollOffset,
                    size = 100
                )
            }

            val swapped = applyTargetSwap(
                currentId = "track_5",
                localItems = items,
                idSelector = { it },
                visibleSongItems = visibleItems,
                pointerY = pointerY,
                grabOffsetY = grabOffsetY,
                fallbackItemHeight = itemHeight,
                swapHysteresisPx = hysteresis,
                reorderState = state
            )

            if (swapped) {
                totalSwaps++
                val newIndex = items.indexOf("track_5")
                assertTrue("Auto-scroll swap must only advance upward (index decrease)", newIndex < currentDraggedIndex)
                currentDraggedIndex = newIndex
            }
        }

        // Each crossed item must have swapped exactly once with no backwards bounces
        assertEquals("track_5 should have advanced cleanly", currentDraggedIndex, items.indexOf("track_5"))
        assertEquals("Total swaps must equal net index change", 5 - currentDraggedIndex, totalSwaps)
    }

    // 9. Playlist persistence: final reordered sequence is preserved
    @Test
    fun test9_playlistPersistence_finalReorderedSequencePreserved() {
        val trackList = (0 until 15).map { Track(id = "id_$it", title = "Title $it", artist = "Artist $it") }
        val items = trackList.map { "${it.id}_0" }.toMutableList()
        val state = PlaylistDragReorderState()

        val startIdx = 10
        // Move item 10 up to index 7 step by step
        for (step in 9 downTo 7) {
            val layout = buildLayout(items)
            val prevCenter = step * itemHeight + 50f
            applyTargetSwap(
                currentId = items[step + 1],
                localItems = items,
                idSelector = { it },
                visibleSongItems = layout,
                pointerY = prevCenter - hysteresis - 5f,
                grabOffsetY = grabOffsetY,
                fallbackItemHeight = itemHeight,
                swapHysteresisPx = hysteresis,
                reorderState = state
            )
        }
        val finalIdx = items.indexOf("id_10_0")
        assertEquals(7, finalIdx)

        // Verify that PlaylistManager.reorderTracks(trackList, 10, 7) produces identical order
        val expectedReordered = PlaylistManager.reorderTracks(trackList, startIdx, finalIdx)
        val actualReorderedIds = items.map { it.removeSuffix("_0") }
        val expectedReorderedIds = expectedReordered.map { it.id }

        assertEquals(expectedReorderedIds, actualReorderedIds)
    }

    // 10. Existing playlist drag-down regression: standard behavior preserved
    @Test
    fun test10_existingPlaylistDragDownRegression_standardBehaviorPreserved() {
        val items = createItems(5)
        val state = PlaylistDragReorderState()
        val layout = buildLayout(items)

        // Drag item 1 down past item 2 (center 250. Threshold = 250 + 20 = 270)
        val swapped = applyTargetSwap(
            currentId = "track_1",
            localItems = items,
            idSelector = { it },
            visibleSongItems = layout,
            pointerY = 275f,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = itemHeight,
            swapHysteresisPx = hysteresis,
            reorderState = state
        )
        assertTrue("Downwards swap must execute cleanly", swapped)
        assertEquals(listOf("track_0", "track_2", "track_1", "track_3", "track_4"), items)
    }
}
