package com.auralis.music.domain.stats

/**
 * Older builds banked a playing song every 10 seconds as its own playback event, so one listen
 * was stored as many ~10s pieces and never reached the 30s a "play" needs (every song showed
 * 0 plays). This finds the pieces of each listen so they can be joined back into one event.
 *
 * Pure, so the rules are unit-testable. Safe to run repeatedly: a whole listen written by the
 * current tracker is longer than a piece, so it is never joined to what follows it.
 */
object ListenChunkMerger {
    data class Event(val id: Long, val trackId: String, val timestamp: Long, val playTimeMs: Long)

    /** A piece was cut by the 10s tick, so it is at most this long. */
    private const val MAX_PIECE_MS = 10_500L
    /** The next piece starts where the last one ended, give or take clock jitter or a pause. */
    private const val MAX_GAP_MS = 120_000L
    private const val CLOCK_SLACK_MS = 1_500L
    /** A listen can't be much longer than the song itself; beyond that it's a replay. */
    private const val LENGTH_SLACK_MS = 15_000L

    /**
     * Groups [events] into listens. Returns only groups of two or more pieces, each in time order.
     * [durationMs] gives a song's length (0 when unknown).
     */
    fun findChunkedListens(events: List<Event>, durationMs: (String) -> Long): List<List<Event>> {
        val result = mutableListOf<List<Event>>()
        for ((trackId, trackEvents) in events.groupBy { it.trackId }) {
            val songMs = durationMs(trackId)
            var group = mutableListOf<Event>()
            var total = 0L
            for (e in trackEvents.sortedBy { it.timestamp }) {
                val prev = group.lastOrNull()
                val continues = prev != null &&
                    prev.playTimeMs <= MAX_PIECE_MS &&
                    (e.timestamp - (prev.timestamp + prev.playTimeMs)) in -CLOCK_SLACK_MS..MAX_GAP_MS &&
                    (songMs <= 0L || total + e.playTimeMs <= songMs + LENGTH_SLACK_MS)
                if (continues) {
                    group += e
                    total += e.playTimeMs
                } else {
                    if (group.size > 1) result += group
                    group = mutableListOf(e)
                    total = e.playTimeMs
                }
            }
            if (group.size > 1) result += group
        }
        return result
    }
}
