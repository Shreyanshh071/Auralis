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
 * ends: each flush banks the segment played so far as its own event and keeps counting from zero,
 * so nothing is double-counted. Pauses are excluded; a repeat-one loop keeps adding to the same
 * listen.
 */
object ListeningTimeTracker {
    /** Listens shorter than this are noise (instant skips) and are not stored at all. */
    private const val MIN_RECORDED_MS = 1_000L

    /** How often an in-progress listen is banked, so Stats updates while still playing. */
    private const val PERIODIC_FLUSH_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var tickerJob: Job? = null

    private var track: Track? = null
    private var startedAtWallMs = 0L
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
                        startedAtWallMs = System.currentTimeMillis()
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
        val listenedMs = accumulatedMs + (segmentStartElapsed?.let { nowElapsed - it } ?: 0L)
        val startedAt = startedAtWallMs
        val stillPlaying = segmentStartElapsed != null
        // Reset first so a second flush for the same song can't double count. A periodic flush
        // (song still playing) starts the next event's clock at now, since this event just
        // banked everything up to this instant; a boundary flush (track changed/stopped) leaves
        // startedAtWallMs alone, since the very next thing to set it is the new track starting.
        accumulatedMs = 0L
        segmentStartElapsed = if (stillPlaying) nowElapsed else null
        if (stillPlaying) startedAtWallMs = System.currentTimeMillis()
        if (listenedMs < MIN_RECORDED_MS || finished.id.isBlank()) return

        withContext(NonCancellable + Dispatchers.IO) {
            val paused = runCatching {
                PrivacyDataStore(context).settingsFlow.first().pauseListenHistory
            }.getOrDefault(false)
            if (paused) return@withContext
            val db = AuralisDatabase.getInstance(context)
            // Ensure the song row exists even if the app UI never saw this song.
            db.trackDao().upsertTrackPreservingFavorite(finished.toEntity())
            db.playbackEventDao().insertEvent(
                PlaybackEventEntity(
                    trackId = finished.id,
                    timestamp = startedAt,
                    playTimeMs = listenedMs
                )
            )
        }
    }
}
