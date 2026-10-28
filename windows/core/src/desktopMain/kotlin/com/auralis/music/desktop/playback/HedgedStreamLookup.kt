package com.auralis.music.desktop.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Workers belong to the resolver's supervised scope, not the waiting lookup. Blocking extractor
 * calls can take time to notice interruption; a winning answer must not wait for their teardown. */
internal suspend fun <T : Any> hedgedStreamLookup(
    workers: CoroutineScope,
    hedgeMs: Long,
    budgetMs: Long,
    maxAttempts: Int,
    stop: () -> Boolean = { false },
    onHedge: () -> Unit = {},
    extract: suspend () -> T?
): T? {
    require(hedgeMs > 0 && budgetMs > 0 && maxAttempts > 0)
    data class Answer<T>(val value: T?)
    val answers = Channel<Answer<T>>(Channel.UNLIMITED)
    val jobs = mutableListOf<Job>()
    val deadline = System.nanoTime() + budgetMs * 1_000_000L
    var started = 0
    var completed = 0
    fun startOne() {
        started++
        jobs += workers.launch {
            val value = try { extract() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
            answers.trySend(Answer(value))
        }
    }
    try {
        currentCoroutineContext().ensureActive()
        if (stop()) return null
        startOne()
        while (completed < started) {
            currentCoroutineContext().ensureActive()
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return null
            val wait = if (started < maxAttempts) minOf(hedgeMs, remaining) else remaining
            val answer = withTimeoutOrNull(wait) { answers.receive() }
            if (answer == null) {
                if (started < maxAttempts) { onHedge(); startOne() }
            } else {
                completed++
                answer.value?.let { return it }
                if (stop()) return null
                if (started < maxAttempts) startOne()
            }
        }
        return null
    } finally {
        // Cancel without joining: the first usable answer is already safe to consume.
        jobs.forEach { it.cancel() }
        answers.close()
    }
}
