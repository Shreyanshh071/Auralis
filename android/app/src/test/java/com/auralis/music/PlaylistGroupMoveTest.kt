package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.ui.library.PlaylistGroupMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistGroupMoveTest {

    private val items = listOf("A", "B", "C", "D", "E", "F")

    @Test
    fun selectedSongsJumpToTopInTheirOrder() {
        val picked = setOf("E", "B")
        assertEquals(listOf("B", "E", "A", "C", "D", "F"), PlaylistGroupMove.toTop(items) { it in picked })
    }

    @Test
    fun selectedSongsJumpToBottomInTheirOrder() {
        val picked = setOf("A", "D")
        assertEquals(listOf("B", "C", "E", "F", "A", "D"), PlaylistGroupMove.toBottom(items) { it in picked })
    }

    @Test
    fun draggingTheGroupLandsEverySelectedSongAroundTheDraggedOne() {
        // B, D and F are ticked; the user drags D. B and F leave the list during the drag.
        val picked = setOf("B", "D", "F")
        val (before, after) = PlaylistGroupMove.splitAroundAnchor(items, "D") { it in picked }
        assertEquals(listOf("B"), before)
        assertEquals(listOf("F"), after)

        // Remaining list while dragging: A C D E. D is dropped first.
        val droppedAtTop = listOf("D", "A", "C", "E")
        assertEquals(
            listOf("B", "D", "F", "A", "C", "E"),
            PlaylistGroupMove.insertAroundAnchor(droppedAtTop, "D", before, after)
        )
        // Or dropped last.
        val droppedAtBottom = listOf("A", "C", "E", "D")
        assertEquals(
            listOf("A", "C", "E", "B", "D", "F"),
            PlaylistGroupMove.insertAroundAnchor(droppedAtBottom, "D", before, after)
        )
    }

    @Test
    fun hiddenPlaceholderRowsKeepTheirSlots() {
        val a = Track(id = "a", title = "Alpha", artist = "x")
        val hidden = Track(id = "h", title = "Track 7", artist = "x")
        val b = Track(id = "b", title = "Beta", artist = "x")
        val c = Track(id = "c", title = "Gamma", artist = "x")
        val result = PlaylistGroupMove.applyVisibleOrder(
            listOf(a, hidden, b, c), listOf(c, a, b), PlaylistGroupMove::isShownInPlaylist
        )
        assertEquals(listOf(c, hidden, a, b), result)
    }

    @Test
    fun aStaleOrderIsIgnored() {
        val a = Track(id = "a", title = "Alpha", artist = "x")
        val b = Track(id = "b", title = "Beta", artist = "x")
        val c = Track(id = "c", title = "Gamma", artist = "x")
        assertNull(PlaylistGroupMove.applyVisibleOrder(listOf(a, b), listOf(b, c), PlaylistGroupMove::isShownInPlaylist))
        assertNull(PlaylistGroupMove.applyVisibleOrder(listOf(a, b, c), listOf(b, a), PlaylistGroupMove::isShownInPlaylist))
    }

    @Test
    fun duplicateSongsSurviveAGroupMove() {
        val a = Track(id = "a", title = "Alpha", artist = "x")
        val b = Track(id = "b", title = "Beta", artist = "x")
        val result = PlaylistGroupMove.applyVisibleOrder(
            listOf(a, b, a), listOf(b, a, a), PlaylistGroupMove::isShownInPlaylist
        )
        assertEquals(listOf(b, a, a), result)
    }
}
