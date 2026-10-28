package com.auralis.music.desktop.playback

import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HedgedStreamLookupTest {
    @Test fun fastWinnerDoesNotWaitForABlockingLoserToStop() = runBlocking {
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val started = System.nanoTime()
        try {
            val result = hedgedStreamLookup(workers, 25, 4000, 2) {
                if (calls.incrementAndGet() == 1) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (release.count > 0 && System.nanoTime() < deadline) {
                        try { release.await(10, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                    }
                    "slow"
                } else "fast"
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertEquals("fast", result)
            assertTrue(elapsed < 1500, "Winner waited for canceled blocking work: ${elapsed}ms")
            println("HEDGED_STREAM_WINNER_MS=$elapsed (loser blocks for up to 3000ms)")
        } finally { release.countDown(); workers.cancel() }
    }

    @Test fun failedExtractionIsReplacedWithoutWaitingForTheHedgeTimer() = runBlocking {
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val calls = AtomicInteger()
        val started = System.nanoTime()
        try {
            val result = hedgedStreamLookup(workers, 2000, 5000, 3) {
                if (calls.incrementAndGet() == 1) null else "usable"
            }
            assertEquals("usable", result)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
        } finally { workers.cancel() }
    }

    @Test fun knownRestrictionStopsRepeatedExtractions() = runBlocking {
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val restricted = AtomicBoolean()
        val calls = AtomicInteger()
        try {
            val result = hedgedStreamLookup<String>(workers, 100, 1000, 3, stop = { restricted.get() }) {
                calls.incrementAndGet(); restricted.set(true); null
            }
            assertNull(result)
            assertEquals(1, calls.get())
        } finally { workers.cancel() }
    }

    @Test fun cancelingAWaitCancelsItsWorkersAndDoesNotStartMore() = runBlocking {
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val calls = AtomicInteger()
        val began = CompletableDeferred<Unit>()
        try {
            val waiting = launch {
                hedgedStreamLookup<String>(workers, 1000, 5000, 3) {
                    calls.incrementAndGet(); began.complete(Unit); awaitCancellation()
                }
            }
            began.await()
            waiting.cancelAndJoin()
            delay(50)
            assertEquals(1, calls.get())
            assertTrue(workers.coroutineContext[Job]!!.children.none { it.isActive })
        } finally { workers.cancel() }
    }
}
