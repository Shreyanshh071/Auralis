package com.auralis.music.data.sync

/**
 * Decides whether this phone's connection is really too slow for smooth playback, from what
 * the player does. Only playback that stops to load in the middle of a song counts, or a song
 * that takes far too long to start. Loading at the start of a song and after a jump (a seek,
 * or a Listen Together catch-up) is normal and ignored.
 *
 * All times are monotonic milliseconds (SystemClock.elapsedRealtime()).
 */
class PlaybackStallDetector {

    companion object {
        /** A pause shorter than this is a blip, not a problem. */
        const val MIN_STALL_MS = 1_500L
        /** One pause this long is a problem on its own. */
        const val LONG_STALL_MS = 6_000L
        /** Two counted pauses within this window are a problem. */
        const val REPEAT_WINDOW_MS = 120_000L
        /** A song taking longer than this to start playing is a problem. */
        const val SLOW_START_MS = 12_000L
        /** Loading right after a jump is expected for this long. */
        const val SEEK_GRACE_MS = 4_000L
        /** No counted pause for this long means the connection has recovered. */
        const val RECOVERY_MS = 180_000L
    }

    var isPoor: Boolean = false
        private set

    private var loadingSinceMs: Long? = null
    private var seekGraceUntilMs: Long = 0L
    private var stallSinceMs: Long? = null
    private var stallCounted = false
    private var lastPlayingAtMs: Long = Long.MIN_VALUE / 2
    private val stallTimes = ArrayDeque<Long>()
    private var lastStallAtMs: Long? = null
    /** Only time actually spent loading counts as a slow start; a new song held paused is not loading. */
    private var buffering = false

    /** A new song started loading because playback should continue with it. */
    fun onTrackStart(nowMs: Long) {
        loadingSinceMs = nowMs
        stallSinceMs = null
    }

    fun onSeek(nowMs: Long) {
        seekGraceUntilMs = nowMs + SEEK_GRACE_MS
        stallSinceMs = null
    }

    /** Whether the verdict changed: true when it became poor or recovered. */
    fun onPlayerState(isPlaying: Boolean, isBuffering: Boolean, nowMs: Long): Boolean {
        val before = isPoor
        buffering = isBuffering
        if (isPlaying && !isBuffering) {
            lastPlayingAtMs = nowMs
            loadingSinceMs = null
            endStall(nowMs)
        } else if (isBuffering) {
            val midSong = loadingSinceMs == null && nowMs >= seekGraceUntilMs &&
                nowMs - lastPlayingAtMs <= 1_000L
            if (midSong && stallSinceMs == null) {
                stallSinceMs = nowMs
                stallCounted = false
            }
        } else {
            // Paused (by the user, the host or the system): nothing to measure.
            stallSinceMs = null
            loadingSinceMs = null
        }
        return evaluate(nowMs) != before
    }

    /** Called about once a second so a stall still in progress is noticed. */
    fun tick(nowMs: Long): Boolean {
        val before = isPoor
        return evaluate(nowMs) != before
    }

    private fun endStall(nowMs: Long) {
        val since = stallSinceMs ?: return
        if (!stallCounted && nowMs - since >= MIN_STALL_MS) record(nowMs)
        stallSinceMs = null
    }

    private fun record(nowMs: Long) {
        stallTimes.addLast(nowMs)
        lastStallAtMs = nowMs
        stallCounted = true
    }

    private fun evaluate(nowMs: Long): Boolean {
        while (stallTimes.isNotEmpty() && nowMs - stallTimes.first() > REPEAT_WINDOW_MS) stallTimes.removeFirst()
        val stalledFor = stallSinceMs?.let { nowMs - it } ?: 0L
        // A song that changed while paused (the room holds new songs at 0:00) never reports
        // "playing", so without this the wait itself was taken for a 12-second slow start.
        val loadingFor = if (buffering) loadingSinceMs?.let { nowMs - it } ?: 0L else 0L
        if (stalledFor >= MIN_STALL_MS && !stallCounted) record(nowMs)

        val problem = stalledFor >= LONG_STALL_MS ||
            loadingFor >= SLOW_START_MS ||
            stallTimes.size >= 2
        if (problem) {
            if (loadingFor >= SLOW_START_MS) lastStallAtMs = nowMs
            isPoor = true
        } else if (isPoor) {
            val quietFor = lastStallAtMs?.let { nowMs - it } ?: Long.MAX_VALUE
            if (quietFor >= RECOVERY_MS && stallSinceMs == null) {
                isPoor = false
                stallTimes.clear()
            }
        }
        return isPoor
    }
}
