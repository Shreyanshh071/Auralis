package com.auralis.music.data.service

import com.auralis.music.domain.model.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object AudioTransitionPolicy {
    fun sameAlbum(current: Track, next: Track): Boolean {
        if (!current.albumId.isNullOrBlank() && !next.albumId.isNullOrBlank()) return current.albumId == next.albumId
        val album = current.album?.trim()?.takeUnless { it.isEmpty() || it.equals("Unknown album", true) } ?: return false
        return album.equals(next.album?.trim(), true) && current.artist.isNotBlank() && current.artist.equals(next.artist, true)
    }

    fun gains(progress: Float): Pair<Float, Float> {
        val angle = progress.coerceIn(0f, 1f) * PI / 2.0
        return cos(angle).toFloat() to sin(angle).toFloat()
    }

    fun fadeDuration(requestedMs: Long, remainingMs: Long, nextDurationMs: Long): Long =
        minOf(requestedMs, remainingMs - 250L, nextDurationMs / 2L).coerceAtLeast(0L)

    /* Session-player handoff. Both decoders play the same recording, so any offset between them
     * is heard as a repeated or skipped fragment while their gains swap. A seek is not instant:
     * the session player only starts moving once it is ready again, while the overlap decoder keeps
     * playing. Seeking to "where the overlap decoder is now" therefore always lands behind it by
     * the seek latency. drift = overlapPosition - sessionPosition (media ms; positive = session
     * behind). After seeking with lead L, drift == latency - L, so the lead that cancels the
     * latency is L + drift. */
    const val HANDOFF_TOLERANCE_MS = 20L
    const val HANDOFF_MAX_REALIGNS = 4
    const val HANDOFF_SWAP_MS = 160L
    const val HANDOFF_UNALIGNED_SWAP_MS = 30L

    fun handoffAligned(driftMs: Long): Boolean = kotlin.math.abs(driftMs) <= HANDOFF_TOLERANCE_MS

    fun nextHandoffLead(currentLeadMs: Long, driftMs: Long): Long =
        (currentLeadMs + driftMs).coerceIn(-1_000L, 4_000L)

    /** A long swap between misaligned copies is a flam/echo; if alignment failed, swap almost instantly. */
    fun handoffSwapMs(driftMs: Long): Long =
        if (handoffAligned(driftMs)) HANDOFF_SWAP_MS else HANDOFF_UNALIGNED_SWAP_MS

    /** Player positions jitter by a few ms per read; the median ignores a single stale clock read. */
    fun medianDrift(samples: LongArray): Long {
        require(samples.isNotEmpty())
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }
}
