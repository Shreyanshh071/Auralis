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

/**
 * One measurement of (server clock - local clock), taken around a write that stamped
 * a server timestamp. The server stamped it somewhere between [sentAtMs] and [ackAtMs]
 * local time, so the offset is known to within half of that round trip.
 */
data class ClockOffsetSample(
    val offsetMs: Long,
    val uncertaintyMs: Long,
    val takenAtMs: Long
)

object ListenTogetherSyncMath {
    const val DRIFT_THRESHOLD_MS = 2500L

    /** How often every participant refreshes its presence record. */
    const val HEARTBEAT_INTERVAL_MS = 20_000L

    /** A participant that has not checked in for this long is treated as gone. */
    const val PRESENCE_STALE_MS = 75_000L

    /** How many queue items before the current track are shared with the room. */
    const val QUEUE_WINDOW_BEFORE = 10

    /** Total queue items shared with the room (Firestore rule caps the queue at 100). */
    const val QUEUE_WINDOW_SIZE = 50

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

    fun clockOffsetSample(sentAtMs: Long, ackAtMs: Long, serverStampMs: Long): ClockOffsetSample {
        val midpoint = sentAtMs + (ackAtMs - sentAtMs) / 2
        return ClockOffsetSample(
            offsetMs = serverStampMs - midpoint,
            uncertaintyMs = ((ackAtMs - sentAtMs) / 2).coerceAtLeast(0),
            takenAtMs = ackAtMs
        )
    }

    /** The most precise of the given samples, or null when there are none. */
    fun bestClockOffset(samples: List<ClockOffsetSample>): ClockOffsetSample? =
        samples.minByOrNull { it.uncertaintyMs }

    /**
     * When the host's broadcast happened, expressed on this device's clock.
     *
     * Prefers the server stamp (immune to the two phones' clocks disagreeing) and falls back
     * to the host's own wall-clock stamp when the server stamp or our offset is unknown.
     */
    fun hostBroadcastLocalTime(
        serverUpdatedAtMs: Long?,
        hostWallClockUpdatedAtMs: Long,
        localClockOffsetMs: Long?
    ): Long {
        if (serverUpdatedAtMs != null && localClockOffsetMs != null) {
            return serverUpdatedAtMs - localClockOffsetMs
        }
        return hostWallClockUpdatedAtMs
    }

    /** True when a presence record last stamped at [lastSeenServerMs] is too old. Unknown stamps are never stale. */
    fun isPresenceStale(
        lastSeenServerMs: Long?,
        nowServerMs: Long,
        staleAfterMs: Long = PRESENCE_STALE_MS
    ): Boolean {
        if (lastSeenServerMs == null) return false
        return nowServerMs - lastSeenServerMs > staleAfterMs
    }

    /**
     * The slice of [queue] shared with the room, and the current track's index inside that slice.
     * Keeps the current track inside the slice even deep into a long queue.
     */
    fun <T> queueWindow(
        queue: List<T>,
        currentIndex: Int,
        before: Int = QUEUE_WINDOW_BEFORE,
        size: Int = QUEUE_WINDOW_SIZE
    ): Pair<List<T>, Int> {
        if (queue.isEmpty()) return emptyList<T>() to 0
        val current = currentIndex.coerceIn(0, queue.lastIndex)
        val start = (current - before).coerceAtLeast(0)
            .coerceAtMost((queue.size - size).coerceAtLeast(0))
        val end = (start + size).coerceAtMost(queue.size)
        return queue.subList(start, end) to (current - start)
    }

    /**
     * Where [trackId] sits in the room [queue]: the broadcast [queueIndex] when it really points
     * at that track (handles duplicates), otherwise the first match, otherwise 0.
     */
    fun resolveQueueIndex(queue: List<Track>, queueIndex: Int, trackId: String): Int {
        if (queue.getOrNull(queueIndex)?.id == trackId) return queueIndex
        return queue.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
    }
}
