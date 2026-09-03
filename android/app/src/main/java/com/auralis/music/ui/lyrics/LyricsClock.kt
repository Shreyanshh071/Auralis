package com.auralis.music.ui.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import com.auralis.music.data.service.PlaybackClockSource

/**
 * Upper bound on how far the clock may be extrapolated past the newest reading
 * the engine gave us.
 *
 * A word sweep needs a position for *this* frame, but the engine only publishes
 * a new number every so often. Between publications we carry the last one
 * forward by the elapsed frame time. If the engine stops publishing — a buffer
 * underrun, a decoder stall, a paused `isPlaying` that lags reality — an
 * unclamped carry would keep running and the highlight would sprint ahead of
 * the audio. 100 ms is short enough to be invisible and long enough to bridge
 * any normal gap between readings.
 */
const val MAX_CLOCK_CARRY_MS = 100L

/**
 * Anchor-and-carry interpolation of a coarse playback clock. Pure, so the timing
 * rules are unit-testable without Compose or a player.
 *
 * The contract:
 * - A raw reading that differs from the anchor is the truth — return it verbatim
 *   and let the caller re-anchor. This makes seek, pause/resume, track change
 *   and buffer recovery self-correcting with no special cases.
 * - While the reading sits on a plateau, add the wall time elapsed since the
 *   anchor, scaled by playback rate.
 * - Never extrapolate while stopped: a highlight must freeze mid-word on pause,
 *   not drift on.
 * - Clamp the carry to [MAX_CLOCK_CARRY_MS], and clamp a negative wall delta to
 *   zero so clock skew can never walk the position backwards.
 *
 * @param rawMs       position the engine reports this frame
 * @param anchorRawMs raw reading captured when the anchor was set
 * @param anchorWallMs wall time captured when the anchor was set
 * @param nowWallMs   wall time of this frame, same time base as [anchorWallMs]
 * @param speed       playback rate multiplier
 * @param isPlaying   whether audio is actually advancing
 */
fun carriedPositionMs(
    rawMs: Long,
    anchorRawMs: Long,
    anchorWallMs: Long,
    nowWallMs: Long,
    speed: Float,
    isPlaying: Boolean
): Long {
    if (!isPlaying || rawMs != anchorRawMs) return rawMs
    val carriedMs = (nowWallMs - anchorWallMs).coerceIn(0L, MAX_CLOCK_CARRY_MS)
    return rawMs + (carriedMs * speed.coerceAtLeast(0f)).toLong()
}

/**
 * A playback position sampled once per displayed frame and smoothed by
 * [carriedPositionMs].
 *
 * Reading the returned [State] inside a draw lambda (`Canvas`, `drawBehind`)
 * invalidates the draw phase only — composition never re-runs, which is what
 * keeps a 60 Hz word sweep off the recomposition path.
 *
 * The frame loop runs only while [enabled]. Pass `enabled = lyricsVisible &&
 * isPlaying` so a paused or hidden lyrics view stops requesting frames entirely;
 * while stopped the state mirrors [fallbackPositionMs] instead, so a seek made
 * while paused still moves the highlight.
 */
@Composable
fun rememberLyricsClock(
    source: PlaybackClockSource?,
    enabled: Boolean,
    fallbackPositionMs: State<Long>
): State<Long> {
    val clock = remember { mutableLongStateOf(fallbackPositionMs.value) }

    LaunchedEffect(source, enabled, fallbackPositionMs) {
        if (source == null || !enabled) {
            // Track the coarse mirror so seeks and ticker updates still land.
            snapshotFlow { fallbackPositionMs.value }.collect { clock.longValue = it }
            return@LaunchedEffect
        }

        var anchorRawMs = source.rawPositionMs()
        var anchorWallMs = withFrameMillis { it }
        var anchorSpeed = source.speed()
        clock.longValue = anchorRawMs

        while (true) {
            val frameWallMs = withFrameMillis { it }
            val rawMs = source.rawPositionMs()
            val playing = source.isPlaying()

            clock.longValue = carriedPositionMs(
                rawMs = rawMs,
                anchorRawMs = anchorRawMs,
                anchorWallMs = anchorWallMs,
                nowWallMs = frameWallMs,
                speed = anchorSpeed,
                isPlaying = playing
            )

            if (rawMs != anchorRawMs) {
                anchorRawMs = rawMs
                anchorWallMs = frameWallMs
                anchorSpeed = source.speed()
            }
        }
    }

    return clock
}
