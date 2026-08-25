package com.auralis.music.data.sync

import com.auralis.music.domain.model.Track
import kotlin.math.abs

data class RoomParticipant(
    val uid: String,
    val displayName: String,
    val isHost: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis()
)

data class RoomState(
    val roomId: String,
    val hostUid: String,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val broadcastTimestampMs: Long = 0,
    val playbackRate: Float = 1.0f,
    val participants: Map<String, RoomParticipant> = emptyMap()
)

object ListenTogetherSyncMath {
    const val DRIFT_THRESHOLD_MS = 1500L

    /**
     * Calculates the estimated host playback position at [nowMs].
     */
    fun calculateEstimatedHostPosition(
        broadcastPositionMs: Long,
        broadcastTimestampMs: Long,
        isPlaying: Boolean,
        playbackRate: Float = 1.0f,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        if (!isPlaying || broadcastTimestampMs <= 0) return broadcastPositionMs
        val elapsed = (nowMs - broadcastTimestampMs).coerceAtLeast(0)
        return broadcastPositionMs + (elapsed * playbackRate).toLong()
    }

    /**
     * Determines whether client drift exceeds the [thresholdMs] window (default 1500ms).
     */
    fun shouldResync(
        clientPositionMs: Long,
        estimatedHostPositionMs: Long,
        thresholdMs: Long = DRIFT_THRESHOLD_MS
    ): Boolean {
        return abs(clientPositionMs - estimatedHostPositionMs) > thresholdMs
    }
}
