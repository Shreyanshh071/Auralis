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
    fun reloadingThePlaylistKeepsEveryRowsId() {
        val a = Track(id = "a", title = "Alpha", artist = "x")
        val b = Track(id = "b", title = "Beta", artist = "x")
        val c = Track(id = "c", title = "Gamma", artist = "x")
        val rows = PlaylistGroupMove.reconcile(emptyList(), listOf(a, b, a, c))
        assertEquals(listOf("a_0", "b_1", "a_2", "c_3"), rows.map { it.instanceId })

        // The saved order comes back (or the old one in between): same ids, just moved.
        val reloaded = PlaylistGroupMove.reconcile(rows, listOf(c, a, b, a))
        assertEquals(listOf("c_3", "a_0", "b_1", "a_2"), reloaded.map { it.instanceId })

        // A new song gets an id no other row has.
        val d = Track(id = "d", title = "Delta", artist = "x")
        val withNew = PlaylistGroupMove.reconcile(reloaded, listOf(d, c, a, b, a))
        assertEquals(5, withNew.map { it.instanceId }.toSet().size)
        assertEquals("c_3", withNew[1].instanceId)
    }

    @Test
    fun theSavesOldOrderEchoIsIgnoredEvenAfterTheNewOrderArrived() {
        val saved = listOf("b", "c", "a")
        val old = listOf("a", "b", "c")
        // New order first (from memory), then the database's in-between old order, then the new.
        assertEquals(PlaylistGroupMove.Reload.ACCEPT, PlaylistGroupMove.judgeReload(saved, saved, 10))
        assertEquals(PlaylistGroupMove.Reload.IGNORE, PlaylistGroupMove.judgeReload(old, saved, 120))
        assertEquals(PlaylistGroupMove.Reload.ACCEPT, PlaylistGroupMove.judgeReload(saved, saved, 140))
        // A song added or removed is a real change.
        assertEquals(PlaylistGroupMove.Reload.ACCEPT_AND_FORGET, PlaylistGroupMove.judgeReload(listOf("a", "b"), saved, 200))
        // Long after the save, any order counts.
        assertEquals(PlaylistGroupMove.Reload.ACCEPT_AND_FORGET, PlaylistGroupMove.judgeReload(old, saved, 5_000))
    }

    @Test
    fun droppedSongStaysWhereItWasLetGo() {
        // Rows are 100px; the dragged song (lazy index 12) sits 250px from the top, and two songs
        // go back above it, so it becomes index 14. Top row: index 11, scrolled 50px.
        assertEquals(11 to 50, PlaylistGroupMove.scrollToKeepAnchor(14, 250, 100, firstSongLazyIndex = 2))
        // Exactly on a row edge.
        assertEquals(12 to 0, PlaylistGroupMove.scrollToKeepAnchor(14, 200, 100, firstSongLazyIndex = 2))
        // Partly above the top edge.
        assertEquals(14 to 30, PlaylistGroupMove.scrollToKeepAnchor(14, -30, 100, firstSongLazyIndex = 2))
        // Would reach into the header: leave the scroll alone.
        assertNull(PlaylistGroupMove.scrollToKeepAnchor(4, 450, 100, firstSongLazyIndex = 2))
    }

    @Test
    fun trainCarsCatchUpAndSettle() {
        val car = com.auralis.music.ui.library.PlaylistTrainCar(
            com.auralis.music.ui.library.PlaylistTrackItem("a_0", Track(id = "a", title = "Alpha", artist = "x")),
            startY = 0f
        )
        var overshot = false
        repeat(240) {
            com.auralis.music.ui.library.PlaylistTrainPhysics.step(car, target = 500f, carIndex = 1, dt = 1f / 120f)
            if (car.y > 500f) overshot = true
        }
        // It wobbles past the target once, then comes to rest on it.
        assertEquals(true, overshot)
        assertEquals(500f, car.y, 1f)
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
