package com.auralis.music

import com.auralis.music.domain.library.PlaylistManager
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistReorderTest {

    private val trackA = Track(id = "1", title = "Song A", artist = "Artist A")
    private val trackB = Track(id = "2", title = "Song B", artist = "Artist B")
    private val trackC = Track(id = "3", title = "Song C", artist = "Artist C")
    private val trackD = Track(id = "4", title = "Song D", artist = "Artist D")

    private val initialList = listOf(trackA, trackB, trackC, trackD)

    @Test
    fun testMoveDown() {
        // Move Song A from index 0 to index 2
        val reordered = PlaylistManager.reorderTracks(initialList, 0, 2)
        assertEquals(listOf(trackB, trackC, trackA, trackD), reordered)
    }

    @Test
    fun testMoveUp() {
        // Move Song D from index 3 to index 1
        val reordered = PlaylistManager.reorderTracks(initialList, 3, 1)
        assertEquals(listOf(trackA, trackD, trackB, trackC), reordered)
    }

    @Test
    fun testMoveToTop() {
        // Move Song C from index 2 to index 0
        val reordered = PlaylistManager.reorderTracks(initialList, 2, 0)
        assertEquals(listOf(trackC, trackA, trackB, trackD), reordered)
    }

    @Test
    fun testMoveToBottom() {
        // Move Song A from index 0 to index 3
        val reordered = PlaylistManager.reorderTracks(initialList, 0, 3)
        assertEquals(listOf(trackB, trackC, trackD, trackA), reordered)
    }

    @Test
    fun testInvalidIndicesReturnOriginalList() {
        val reorderedInvalidFrom = PlaylistManager.reorderTracks(initialList, -1, 2)
        assertEquals(initialList, reorderedInvalidFrom)

        val reorderedInvalidTo = PlaylistManager.reorderTracks(initialList, 1, 99)
        assertEquals(initialList, reorderedInvalidTo)

        val reorderedSame = PlaylistManager.reorderTracks(initialList, 2, 2)
        assertEquals(initialList, reorderedSame)
    }
}
