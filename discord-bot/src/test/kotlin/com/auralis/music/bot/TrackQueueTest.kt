package com.auralis.music.bot

import com.auralis.music.domain.model.Track
import kotlin.test.*

class TrackQueueTest {
    private fun item(id: String) = QueuedTrack(Track(id = id, title = id), "someone")
    private fun filled(vararg ids: String) = TrackQueue().apply { add(ids.map(::item)) }

    @Test fun playsInOrderThenEnds() {
        val queue = filled("a", "b")
        assertEquals("a", queue.advance()?.track?.id)
        assertEquals("b", queue.advance()?.track?.id)
        assertNull(queue.advance())
        assertNull(queue.current)
    }

    @Test fun trackLoopRepeatsUntilSkipped() {
        val queue = filled("a", "b").apply { advance(); loop = LoopMode.TRACK }
        assertEquals("a", queue.advance()?.track?.id)
        assertEquals("b", queue.advance(skipped = true)?.track?.id)
    }

    @Test fun queueLoopSendsFinishedSongsToTheBack() {
        val queue = filled("a", "b").apply { advance(); loop = LoopMode.QUEUE }
        assertEquals("b", queue.advance()?.track?.id)
        assertEquals("a", queue.advance()?.track?.id)
    }

    @Test fun playNextGoesInFront() {
        val queue = filled("a", "b").apply { add(listOf(item("c")), next = true) }
        assertEquals(listOf("c", "a", "b"), queue.tracks.map { it.track.id })
    }

    @Test fun positionsAreOneBasedAndChecked() {
        val queue = filled("a", "b", "c")
        assertNull(queue.remove(0))
        assertNull(queue.remove(4))
        assertEquals("b", queue.remove(2)?.track?.id)
        assertEquals("c", queue.move(2, 1)?.track?.id)
        assertEquals(listOf("c", "a"), queue.tracks.map { it.track.id })
        assertNull(queue.move(1, 3))
    }

    @Test fun recentIdsCoverPlayedCurrentAndQueued() {
        val queue = filled("a", "b", "c").apply { advance(); advance() }
        assertEquals(setOf("a", "b", "c"), queue.recentIds)
    }
}

class TimeFormatTest {
    @Test fun parsesSecondsAndClockTimes() {
        assertEquals(90_000L, parseTime("90"))
        assertEquals(90_000L, parseTime("1:30"))
        assertEquals(3_725_000L, parseTime("1:02:05"))
        assertNull(parseTime("soon"))
        assertNull(parseTime("-5"))
        assertNull(parseTime("1:2:3:4"))
    }

    @Test fun formatsWithHoursOnlyWhenNeeded() {
        assertEquals("1:05", formatTime(65_000))
        assertEquals("1:02:05", formatTime(3_725_000))
    }
}
