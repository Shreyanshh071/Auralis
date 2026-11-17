package com.auralis.music.desktop.playback

import kotlinx.coroutines.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RequestCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun stop() = scope.cancel()

    @Test fun tenIdenticalCallersShareOneExtraction() = runBlocking {
        val gate = PriorityGate()
        val flights = SharedFlights<String, String>(scope, gate)
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val callers = List(10) {
            async(Dispatchers.Default) {
                flights.await("video", RequestPriority.FOREGROUND) { ticket ->
                    gate.withPermit(ticket) { runs.incrementAndGet(); release.await(); "url" }
                }
            }
        }
        delay(100)
        release.complete(Unit)
        assertEquals(List(10) { "url" }, callers.awaitAll())
        assertEquals(1, runs.get())
    }

    @Test fun oneWaiterLeavingDoesNotCancelAnotherCallersResult() = runBlocking {
        val flights = SharedFlights<String, String>(scope, PriorityGate())
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val leaving = launch { flights.await("k", RequestPriority.FOREGROUND) { started.complete(Unit); release.await(); "done" } }
        started.await()
        val staying = async { flights.await("k", RequestPriority.FOREGROUND) { error("must join, not start again") } }
        delay(50)
        leaving.cancelAndJoin()
        release.complete(Unit)
        assertEquals("done", staying.await())
    }

    @Test fun workNobodyWantsAnyMoreIsCanceled() = runBlocking {
        val flights = SharedFlights<String, String>(scope, PriorityGate())
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val waiter = launch {
            flights.await("k", RequestPriority.FOREGROUND) {
                started.complete(Unit)
                try { awaitCancellation() } finally { canceled.complete(Unit) }
            }
        }
        started.await()
        waiter.cancelAndJoin()
        withTimeout(1_000) { canceled.await() }
        assertEquals(0, flights.size)
    }

    @Test fun foregroundWorkGoesBeforeQueuedBackgroundWork() = runBlocking {
        val gate = PriorityGate(permits = 1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val hold = CompletableDeferred<Unit>()
        val first = launch { gate.withPermit(gate.newTicket(RequestPriority.FOREGROUND)) { hold.await() } }
        delay(20)
        val background = launch { gate.withPermit(gate.newTicket(RequestPriority.BACKGROUND)) { order += "background" } }
        delay(20)
        val foreground = launch { gate.withPermit(gate.newTicket(RequestPriority.FOREGROUND)) { order += "foreground" } }
        delay(20)
        assertEquals(1, gate.activeCount)
        hold.complete(Unit)
        joinAll(first, background, foreground)
        assertEquals(listOf("foreground", "background"), order)
    }

    @Test fun aForegroundCallerPromotesQueuedPreloadWork() = runBlocking {
        val gate = PriorityGate(permits = 1)
        val flights = SharedFlights<String, String>(scope, gate)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val hold = CompletableDeferred<Unit>()
        val busy = launch { gate.withPermit(gate.newTicket(RequestPriority.FOREGROUND)) { hold.await() } }
        delay(20)
        val other = launch { gate.withPermit(gate.newTicket(RequestPriority.BACKGROUND)) { order += "other background" } }
        delay(20)
        val preload = async { flights.await("next", RequestPriority.BACKGROUND) { t -> gate.withPermit(t) { order += "next song"; "url" } } }
        delay(20)
        // The listener skips to the preloading song: the same work becomes foreground.
        val play = async { flights.await("next", RequestPriority.FOREGROUND) { error("joins the preload") } }
        delay(20)
        hold.complete(Unit)
        assertEquals("url", play.await())
        assertEquals("url", preload.await())
        joinAll(busy, other)
        assertEquals(listOf("next song", "other background"), order)
    }

    @Test fun backgroundWaitingIsBounded() = runBlocking {
        val gate = PriorityGate(permits = 1, maxWaiting = 2)
        val hold = CompletableDeferred<Unit>()
        val busy = launch { gate.withPermit(gate.newTicket(RequestPriority.FOREGROUND)) { hold.await() } }
        delay(20)
        val waiting = List(2) { launch { gate.withPermit(gate.newTicket(RequestPriority.BACKGROUND)) { } } }
        delay(20)
        assertFailsWith<GateFullException> { gate.withPermit(gate.newTicket(RequestPriority.BACKGROUND)) { } }
        // Foreground work is never refused.
        val fg = launch { gate.withPermit(gate.newTicket(RequestPriority.FOREGROUND)) { } }
        hold.complete(Unit)
        joinAll(busy, fg, *waiting.toTypedArray())
        assertEquals(0, gate.activeCount)
    }

    @Test fun nestedWorkHoldingTheGateDoesNotDeadlock() = runBlocking {
        val gate = PriorityGate(permits = 1)
        val result = withTimeout(1_000) {
            gate.withPermit(gate.newTicket(RequestPriority.FOREGROUND)) {
                gate.withPermit(gate.newTicket(RequestPriority.FOREGROUND)) { "nested" }
            }
        }
        assertEquals("nested", result)
    }

    @Test fun aCanceledWaiterPassesItsSlotOn() = runBlocking {
        val gate = PriorityGate(permits = 1)
        val hold = CompletableDeferred<Unit>()
        val busy = launch { gate.withPermit(gate.newTicket(RequestPriority.FOREGROUND)) { hold.await() } }
        delay(20)
        val abandoned = launch { gate.withPermit(gate.newTicket(RequestPriority.FOREGROUND)) { fail("canceled before its turn") } }
        delay(20)
        abandoned.cancelAndJoin()
        hold.complete(Unit)
        busy.join()
        withTimeout(1_000) { gate.withPermit(gate.newTicket(RequestPriority.BACKGROUND)) { } }
        assertEquals(0, gate.activeCount)
        assertEquals(0, gate.waitingCount)
    }
}
