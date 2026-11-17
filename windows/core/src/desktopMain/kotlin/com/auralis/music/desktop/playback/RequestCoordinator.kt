package com.auralis.music.desktop.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class RequestPriority { FOREGROUND, BACKGROUND }

/** The gate refused background work because enough is already waiting. */
class GateFullException : IllegalStateException("Too much background work is waiting")

/**
 * Limits how much YouTube work runs at once ([permits], one by default: no speculative duplicate
 * extractions) and hands the next free slot to foreground work before background work (preload,
 * downloads, companion video). A waiting ticket can be promoted when a foreground caller joins it.
 * Background waiters beyond [maxWaiting] are refused rather than queued without bound.
 */
class PriorityGate(private val permits: Int = 1, private val maxWaiting: Int = 8) {
    class Ticket internal constructor(@Volatile var priority: RequestPriority, internal val seq: Long) {
        internal val granted = CompletableDeferred<Unit>()
        /** Raises this ticket to [to] if that's more urgent. */
        fun promote(to: RequestPriority) { if (to.ordinal < priority.ordinal) priority = to }
    }

    private class Held(val gate: PriorityGate) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private val lock = Any()
    private var active = 0
    private var seq = 0L
    private val waiting = ArrayList<Ticket>()

    fun newTicket(priority: RequestPriority): Ticket = synchronized(lock) { Ticket(priority, seq++) }

    val activeCount: Int get() = synchronized(lock) { active }
    val waitingCount: Int get() = synchronized(lock) { waiting.size }

    suspend fun <T> withPermit(ticket: Ticket, block: suspend () -> T): T {
        // Nested work that already holds this gate must not wait for itself.
        if (currentCoroutineContext()[Held]?.gate === this) return block()
        acquire(ticket)
        try {
            return withContext(Held(this)) { block() }
        } finally {
            release()
        }
    }

    private suspend fun acquire(ticket: Ticket) {
        synchronized(lock) {
            if (active < permits && waiting.isEmpty()) { active++; return }
            if (ticket.priority == RequestPriority.BACKGROUND && waiting.size >= maxWaiting) throw GateFullException()
            waiting += ticket
        }
        try {
            ticket.granted.await()
        } catch (e: CancellationException) {
            synchronized(lock) {
                // Granted just as we were canceled: pass the slot on.
                if (!waiting.remove(ticket)) releaseLocked()
            }
            throw e
        }
    }

    private fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        val next = waiting.minWithOrNull(compareBy<Ticket>({ it.priority.ordinal }, { it.seq }))
        if (next != null) {
            waiting.remove(next)
            next.granted.complete(Unit)
        } else {
            active--
        }
    }
}

/**
 * Compatible requests share one piece of work. Each caller is a waiter; when the last waiter
 * leaves (canceled: the song was skipped, the view closed) the work is canceled, but never while
 * another caller still needs its result. A foreground caller joining background work promotes it.
 */
class SharedFlights<K : Any, V>(private val scope: CoroutineScope, private val gate: PriorityGate) {
    private inner class Flight(val deferred: Deferred<V>, val ticket: PriorityGate.Ticket) {
        var waiters = 1
    }

    private val lock = Any()
    private val flights = HashMap<K, Flight>()

    val size: Int get() = synchronized(lock) { flights.size }

    fun isActive(key: K): Boolean = synchronized(lock) { flights[key]?.deferred?.isActive == true }

    suspend fun await(key: K, priority: RequestPriority, work: suspend (PriorityGate.Ticket) -> V): V {
        val flight = synchronized(lock) {
            flights[key]?.takeIf { it.deferred.isActive }?.also { existing ->
                existing.waiters++
                existing.ticket.promote(priority)
            } ?: run {
                val ticket = gate.newTicket(priority)
                val deferred = scope.async(start = CoroutineStart.LAZY) { work(ticket) }
                val created = Flight(deferred, ticket)
                flights[key] = created
                deferred.invokeOnCompletion { synchronized(lock) { if (flights[key] === created) flights.remove(key) } }
                deferred.start()
                created
            }
        }
        try {
            return flight.deferred.await()
        } finally {
            synchronized(lock) {
                flight.waiters--
                if (flight.waiters <= 0 && flight.deferred.isActive) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.deferred.cancel()
                }
            }
        }
    }
}
