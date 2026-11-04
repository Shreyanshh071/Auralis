package com.auralis.music

import com.auralis.music.data.download.forEachDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadWorkersTest {
    @Test fun `two workers overlap transfers and process every song once`() = runTest {
        var active = 0
        var peak = 0
        val completed = mutableListOf<Int>()
        val startedAt = testScheduler.currentTime
        forEachDownload((0 until 6).toList()) { song ->
            active++
            peak = maxOf(peak, active)
            delay(1000)
            completed.add(song)
            active--
        }
        assertEquals(2, peak)
        assertEquals((0 until 6).toList(), completed.sorted())
        assertEquals(3000L, testScheduler.currentTime - startedAt)
    }

    @Test fun `a slow song does not hold up the next available worker`() = runTest {
        val finished = mutableMapOf<Int, Long>()
        forEachDownload(listOf(0, 1, 2)) { song ->
            delay(if (song == 0) 5000L else 1000L)
            finished[song] = testScheduler.currentTime
        }
        assertEquals(2000L, finished[2])
        assertEquals(5000L, finished[0])
    }

    @Test fun `cancelling the playlist stops both workers and leaves queued songs untouched`() = runTest {
        val started = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        val job = async {
            forEachDownload((0 until 100).toList()) { song ->
                started.add(song)
                try { awaitCancellation() } finally { stopped.add(song) }
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(listOf(0, 1), started)
        assertEquals(started.sorted(), stopped.sorted())
    }

    @Test fun `cancellation detected by one worker stops its sibling and cancels the playlist`() = runTest {
        val started = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        val job = async {
            forEachDownload((0 until 10).toList()) { song ->
                started.add(song)
                try {
                    if (song == 0) { delay(100); throw CancellationException("User cancelled") }
                    awaitCancellation()
                } finally { stopped.add(song) }
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(0, 1), started)
        assertEquals(started.sorted(), stopped.sorted())
    }

    @Test fun `empty playlists do not start work`() = runTest {
        forEachDownload(emptyList<Int>()) { fail("Unexpected download") }
    }
}
