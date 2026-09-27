package com.auralis.music

import com.auralis.music.domain.stats.ListenChunkMerger
import com.auralis.music.domain.stats.ListenChunkMerger.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenChunkMergerTest {
    private val songMs = 216_000L // The Less I Know The Better, 3:36

    private fun pieces(trackId: String, start: Long, vararg lengths: Long): List<Event> {
        var t = start
        return lengths.mapIndexed { i, len ->
            Event(id = start + i, trackId = trackId, timestamp = t, playTimeMs = len).also { t += len }
        }
    }

    @Test
    fun `a 40s listen stored as 10s pieces becomes one listen`() {
        // The Stats screenshot: 0:40 listened but 0 plays, because no piece reached 30s.
        val events = pieces("tlikb", 1_000_000L, 4_000L, 10_000L, 10_000L, 10_000L, 6_000L)
        val groups = ListenChunkMerger.findChunkedListens(events) { songMs }
        assertEquals(1, groups.size)
        assertEquals(40_000L, groups[0].sumOf { it.playTimeMs })
    }

    @Test
    fun `a pause between pieces still joins them`() {
        val a = Event(1, "s", 0L, 10_000L)
        val b = Event(2, "s", 10_000L + 60_000L, 8_000L) // resumed after a one-minute pause
        assertEquals(1, ListenChunkMerger.findChunkedListens(listOf(a, b)) { songMs }.size)
    }

    @Test
    fun `whole listens from the current tracker are never joined`() {
        // Two real listens back to back (a replay): the first is far longer than a piece.
        val first = Event(1, "s", 0L, songMs)
        val replay = Event(2, "s", songMs, songMs)
        assertTrue(ListenChunkMerger.findChunkedListens(listOf(first, replay)) { songMs }.isEmpty())
    }

    @Test
    fun `pieces never grow past the song's length`() {
        // 30 pieces of 10s is 300s, longer than a 216s song: that has to be two listens.
        val events = pieces("s", 0L, *LongArray(30) { 10_000L })
        val groups = ListenChunkMerger.findChunkedListens(events) { songMs }
        assertEquals(2, groups.size)
        groups.forEach { g -> assertTrue(g.sumOf { it.playTimeMs } <= songMs + 15_000L) }
    }

    @Test
    fun `different songs and far-apart listens stay separate`() {
        val a = Event(1, "a", 0L, 10_000L)
        val b = Event(2, "b", 10_000L, 10_000L)
        val laterA = Event(3, "a", 3_600_000L, 10_000L)
        assertTrue(ListenChunkMerger.findChunkedListens(listOf(a, b, laterA)) { songMs }.isEmpty())
    }
}
