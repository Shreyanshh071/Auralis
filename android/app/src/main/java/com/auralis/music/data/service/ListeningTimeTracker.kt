package com.auralis.music.data.service

import android.content.Context
import android.os.SystemClock
import com.auralis.music.data.datastore.PrivacyDataStore
import com.auralis.music.data.local.AuralisDatabase
import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records how long each song was *actually* played, for Stats.
 *
 * Previously every song start was logged as a full-length listen, so a song skipped after five
 * seconds still added its whole duration, and "time listened" was just plays × length.
 *
 * This watches the app-wide [AuralisAudioPlayer] (not a screen), sums the wall time spent in the
 * playing state for the current song, and writes a playback event with that real duration when
 * the song changes, playback shuts down, or (while still playing) every [PERIODIC_FLUSH_MS] — the
 * periodic flush is what lets Stats climb while a song is still playing instead of only once it
 * ends. One listen is one event: the first flush inserts it and later flushes add to it. (Each
 * flush used to insert its own 10s event, so no event ever reached the 30s a play needs and every
 * song showed 0 plays.) Pauses are excluded; a repeat-one loop keeps adding to the same listen.
 */
object ListeningTimeTracker {
    /** Listens shorter than this are noise (instant skips) and are not stored at all. */
    private const val MIN_RECORDED_MS = 1_000L

    /** How often an in-progress listen is banked, so Stats updates while still playing. */
    private const val PERIODIC_FLUSH_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var tickerJob: Job? = null

    /** The listen being recorded. [eventId] is set once its row exists. */
    private class Listen(val startedAtWallMs: Long) {
        var eventId: Long? = null
    }

    /** Serialises the database side of flushes, so one listen can never get two rows. */
    private val dbMutex = kotlinx.coroutines.sync.Mutex()

    private var track: Track? = null
    private var listen = Listen(0L)
    private var accumulatedMs = 0L
    private var segmentStartElapsed: Long? = null

    /** Idempotent: safe to call from both the activity and the media service. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        val player = AuralisAudioPlayer.getInstance(appContext)
        job = scope.launch {
            combine(player.currentTrack, player.isPlaying) { t, playing -> t to playing }
                .collect { (current, playing) ->
                    val now = SystemClock.elapsedRealtime()
                    if (current?.id != track?.id) {
                        flush(appContext, now)
                        track = current
                        listen = Listen(System.currentTimeMillis())
                        accumulatedMs = 0L
                        segmentStartElapsed = null
                    }
                    if (playing && current != null && segmentStartElapsed == null) {
                        segmentStartElapsed = now
                    } else if (!playing) {
                        segmentStartElapsed?.let { accumulatedMs += now - it }
                        segmentStartElapsed = null
                    }
                }
        }
        // Same scope (single-threaded Main.immediate) as the collector above, so a periodic
        // flush here and a boundary flush there can never run at the same instant.
        if (tickerJob?.isActive != true) {
            tickerJob = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(PERIODIC_FLUSH_MS)
                    if (segmentStartElapsed != null) {
                        flush(appContext, SystemClock.elapsedRealtime())
                    }
                }
            }
        }
    }

    /** Saves the in-progress listen (e.g. when the service is being destroyed). */
    fun flushNow(context: Context) {
        val appContext = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        scope.launch { flush(appContext, now) }
    }

    private suspend fun flush(context: Context, nowElapsed: Long) {
        val finished = track ?: return
        val current = listen
        val listenedMs = accumulatedMs + (segmentStartElapsed?.let { nowElapsed - it } ?: 0L)
        // Reset first so a second flush can't bank the same time twice. The listen itself stays
        // until the song changes, so a periodic flush adds to its row instead of starting another.
        accumulatedMs = 0L
        segmentStartElapsed = if (segmentStartElapsed != null) nowElapsed else null
        if (finished.id.isBlank() || listenedMs <= 0L) return
        // A skip under a second is noise, but once a listen has a row every second counts.
        if (current.eventId == null && listenedMs < MIN_RECORDED_MS) {
            accumulatedMs += listenedMs
            return
        }

        withContext(NonCancellable + Dispatchers.IO) {
            val paused = runCatching {
                PrivacyDataStore(context).settingsFlow.first().pauseListenHistory
            }.getOrDefault(false)
            if (paused) return@withContext
            val db = AuralisDatabase.getInstance(context)
            dbMutex.lock()
            try {
                val existing = current.eventId
                if (existing != null && db.playbackEventDao().addPlayTime(existing, listenedMs) > 0) {
                    return@withContext
                }
                // Ensure the song row exists even if the app UI never saw this song.
                db.trackDao().upsertTrackPreservingFavorite(finished.toEntity())
                current.eventId = db.playbackEventDao().insertEvent(
                    PlaybackEventEntity(
                        trackId = finished.id,
                        timestamp = current.startedAtWallMs,
                        playTimeMs = listenedMs
                    )
                )
            } finally {
                dbMutex.unlock()
            }
        }
    }
}
