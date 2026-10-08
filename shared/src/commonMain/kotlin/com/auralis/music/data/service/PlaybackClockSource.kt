package com.auralis.music.data.service

/**
 * The finest-grained playback clock the currently active engine can report.
 *
 * Exists so the lyrics renderer can sample the *player's own* position once per
 * displayed frame instead of consuming a `StateFlow` that a background ticker
 * refreshes on its own schedule. A `StateFlow` mirror is a quantised staircase:
 * the value is only as fresh as the last tick, so a word sweep driven by it
 * steps where the song runs.
 *
 * Implementations must be safe to call from the UI thread once per frame:
 * cheap, non-blocking, and never throwing. When the underlying engine cannot be
 * read from the calling thread, return the last mirrored value rather than
 * touching the engine off-thread.
 */
interface PlaybackClockSource {

    /**
     * Current playback position in milliseconds, as coarse or as fine as the
     * engine happens to be. Callers smooth this; see
     * [com.auralis.music.ui.lyrics.carriedPositionMs].
     */
    fun rawPositionMs(): Long

    /**
     * Whether audio is actually advancing. `false` while paused, and while the
     * engine is stalled on a buffer refill. Callers must not extrapolate the
     * position while this is `false`.
     */
    fun isPlaying(): Boolean

    /** Playback rate multiplier, `1.0f` at normal speed. */
    fun speed(): Float

    /**
     * Whether the engine is currently buffering or preparing after a seek or network stall.
     */
    fun isBuffering(): Boolean = false
}
