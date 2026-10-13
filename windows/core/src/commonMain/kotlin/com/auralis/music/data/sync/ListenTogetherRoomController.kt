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

    /** How often a guest compares its position with the host's. */
    const val GUEST_DRIFT_CHECK_MS = 1_000L

    /** A guest this far from the host jumps to the host's position (a jump smaller than this is audible). */
    const val GUEST_MAX_DRIFT_MS = 700L

    /** No drift check this soon after a jump or song change: the player is still settling. */
    const val SEEK_SETTLE_MS = 1_500L

    /** A jump lands this far ahead, covering the moment the player takes to restart (starting guess). */
    const val SEEK_LEAD_MS = 200L

    /** The most a catch-up jump ever lands ahead of the host's position. */
    const val MAX_SEEK_LEAD_MS = 3_000L

    /**
     * Learns how far ahead to jump. After a jump the player needs time to load audio at the new
     * spot while the host keeps playing, so it lands behind by that much ([lagMs] > 0). Without
     * learning it, every correction landed behind again and jumped again. Moves most of the way
     * towards the measured need each time, so it settles in one or two jumps.
     */
    fun nextSeekLead(currentLeadMs: Long, lagMs: Long): Long =
        (currentLeadMs + (lagMs * 0.8).toLong()).coerceIn(0L, MAX_SEEK_LEAD_MS)

    /**
     * After any song change, next/previous from anyone is refused for this long when guests may
     * control playback, so two people skipping at once can't skip twice.
     */
    const val TRACK_CHANGE_LOCK_MS = 3_000L

    /** A room whose host has had no guests this long starts closing. */
    const val ROOM_EMPTY_CLOSE_MS = 5L * 60 * 1000

    /** A room where nothing has played this long starts closing. */
    const val ROOM_IDLE_CLOSE_MS = 30L * 60 * 1000

    /** How long the closing warning shows before the room actually closes. */
    const val ROOM_CLOSE_WARNING_MS = 60L * 1000

    /**
     * Why the room should start closing now, or null to stay open. [aloneSinceMs] is when the host
     * last became the only one in it; [idleSinceMs] when music last stopped. Null means "not now".
     */
    fun roomClosingReason(aloneSinceMs: Long?, idleSinceMs: Long?, nowMs: Long): String? = when {
        aloneSinceMs != null && nowMs - aloneSinceMs >= ROOM_EMPTY_CLOSE_MS -> ROOM_CLOSING_EMPTY
        idleSinceMs != null && nowMs - idleSinceMs >= ROOM_IDLE_CLOSE_MS -> ROOM_CLOSING_IDLE
        else -> null
    }

    fun trackChangeLockRemainingMs(lastTrackChangeAtMs: Long, nowMs: Long): Long =
        (lastTrackChangeAtMs + TRACK_CHANGE_LOCK_MS - nowMs).coerceIn(0L, TRACK_CHANGE_LOCK_MS)

    /**
     * Whether the guest is already playing the host's audio. [localResolvedId] is the video the
     * guest's own lookup picked for the host's track id, if any.
     */
    fun isPlayingHostAudio(localTrackId: String?, hostTrackId: String, hostVideoId: String?, localResolvedId: String?): Boolean {
        if (localTrackId == null) return false
        if (hostVideoId == null) return localTrackId == hostTrackId
        if (localTrackId == hostVideoId) return true
        if (localTrackId != hostTrackId) return false
        return (localResolvedId ?: hostTrackId) == hostVideoId
    }

    /** How often every participant refreshes its presence record. */
    const val HEARTBEAT_INTERVAL_MS = 20_000L

    /**
     * A participant that has not checked in for this long is treated as gone. Android freezes an
     * app in the background when nothing is playing, so its check-ins pause; at 75 s that showed
     * "disconnected" after ~2 minutes of silence and made guests leave. Leaving on purpose still
     * removes someone at once; a silent phone now counts as gone only after the room's own idle
     * close (30 min + 1 min warning) would have closed the room anyway.
     */
    const val PRESENCE_STALE_MS = 32L * 60 * 1000

    /** How many queue items before the current track are shared with the room. */
    const val QUEUE_WINDOW_BEFORE = 20

    /** Total queue items shared with the room: the Firestore rule's cap, so a 99-song playlist arrives whole. */
    const val QUEUE_WINDOW_SIZE = 100

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
