package com.auralis.music.desktop.library

import com.auralis.music.domain.model.Track
import kotlin.test.*

class DesktopListeningStatsTest {
    private val song = Track(id = "song", title = "Song", artist = "Artist", duration = 180)

    @Test fun sessionsSurviveRestartWithoutCountingEachFlushAsAnotherPlay() {
        val dir = kotlin.io.path.createTempDirectory("stats-test").toFile()
        try {
            val file = java.io.File(dir, "stats.json")
            val stats = DesktopListeningStats(file)
            stats.record("one", song, 1000, 10_000)
            stats.record("one", song, 1000, 35_000)
            stats.record("two", song, 2000, 5000)
            val restored = DesktopListeningStats(file).listens.value
            assertEquals(2, restored.size)
            assertEquals(40_000L, restored.sumOf { it.timeMs })
            assertEquals(1, restored.count { it.countsAsPlay })
            stats.clear()
            assertTrue(DesktopListeningStats(file).listens.value.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun pausedBufferingAndSleepingTimeAreNotListens() {
        val a = ListeningAccumulator()
        a.sample(song, true, 1_000_000_000, 1000)
        a.sample(song, true, 2_000_000_000, 2000)
        a.sample(song, false, 3_000_000_000, 3000)
        a.sample(song, false, 4_000_000_000, 4000)
        a.sample(song, true, 5_000_000_000, 5000)
        a.sample(song, true, 100_000_000_000, 100000)
        assertEquals(2000L, a.timeMs)
        a.sample(song, true, 101_000_000_000, 101000)
        assertEquals(3000L, a.timeMs)
        a.sample(song.copy(id = "next"), true, 102_000_000_000, 102000)
        assertEquals(0L, a.timeMs)
    }
}
