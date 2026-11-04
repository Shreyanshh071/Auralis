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
}
