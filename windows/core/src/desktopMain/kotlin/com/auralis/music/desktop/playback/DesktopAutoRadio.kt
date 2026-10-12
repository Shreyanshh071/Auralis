package com.auralis.music.desktop.playback

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*

/** A single-song session grows in batches of fourteen, without changing explicit user lists. */
class DesktopAutoRadio(
    private val scope: CoroutineScope,
    private val queue: () -> DesktopPlaybackController.Queue,
    private val allowed: () -> Boolean,
    private val candidates: suspend (Track) -> List<Track>,
    private val append: (List<Track>) -> Unit,
    private val advance: () -> Unit
) {
    private var automatic = false
    private var generation = 0L
    private var job: Job? = null
    private var advanceWhenReady = false

    @Synchronized fun reset(automatic: Boolean) {
        cancelPending()
        this.automatic = automatic
    }

    @Synchronized fun cancelPending() {
        generation++
        job?.cancel()
        job = null
        advanceWhenReady = false
    }

    fun queueChanged() {
        val current = queue()
        if (current.current != null && current.tracks.lastIndex - current.index <= 1) request()
    }

    @Synchronized fun request(advanceWhenLoaded: Boolean = false) {
        if (!automatic || !allowed()) return
        val seed = queue().current ?: return
        advanceWhenReady = advanceWhenReady || advanceWhenLoaded
        if (job?.isActive == true) return
        val started = generation
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val found = candidates(seed)
                ensureActive()
                synchronized(this@DesktopAutoRadio) {
                    if (generation != started || !automatic || !allowed()) return@synchronized
                    val existing = queue().tracks.map { it.id }.toSet()
                    val batch = found.filter { it.id.isNotBlank() && it.id !in existing }.distinctBy { it.id }.take(14)
                    if (batch.isNotEmpty()) append(batch)
                    if (advanceWhenReady) {
                        advanceWhenReady = false
                        advance()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.auralis.music.util.Log.w("DesktopAutoRadio", "Queue extension failed: ${e.message}")
            } finally {
                synchronized(this@DesktopAutoRadio) {
                    if (generation == started) { advanceWhenReady = false; job = null }
                }
            }
        }.also { it.start() }
    }
}
