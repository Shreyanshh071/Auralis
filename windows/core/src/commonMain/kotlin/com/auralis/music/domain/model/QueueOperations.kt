package com.auralis.music.domain.model

object QueueOperations {
    /**
     * Calculates the new active playing index when a track is moved from [fromIndex] to [toIndex].
     *
     * Invariants:
     * - If currentIndex == fromIndex, the playing track moved to toIndex.
     * - If fromIndex < currentIndex <= toIndex, tracks shifted left, so currentIndex moves -1.
     * - If toIndex <= currentIndex < fromIndex, tracks shifted right, so currentIndex moves +1.
     * - Otherwise, currentIndex is untouched.
     */
    fun mapIndexAfterMove(fromIndex: Int, toIndex: Int, currentIndex: Int): Int {
        if (fromIndex == toIndex || currentIndex < 0) return currentIndex
        return when {
            currentIndex == fromIndex -> toIndex
            fromIndex < currentIndex && currentIndex <= toIndex -> currentIndex - 1
            toIndex <= currentIndex && currentIndex < fromIndex -> currentIndex + 1
            else -> currentIndex
        }
    }

    /**
     * Calculates the new active playing index when a track at [removeIndex] is removed.
     */
    fun mapIndexAfterRemove(removeIndex: Int, currentIndex: Int, queueSizeAfterRemove: Int): Int {
        if (queueSizeAfterRemove <= 0) return -1
        return when {
            currentIndex < removeIndex -> currentIndex
            currentIndex > removeIndex -> currentIndex - 1
            else -> currentIndex.coerceAtMost(queueSizeAfterRemove - 1)
        }
    }

    /**
     * Calculates recency-weighted play count score using a 30-day decay window.
     */
    fun calculateRecencyScore(count: Int, lastPlayedEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Double {
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        val ageMs = (nowEpochMs - lastPlayedEpochMs).coerceAtLeast(0)
        val recencyBoost = if (ageMs < thirtyDaysMs) {
            1.0 + (1.0 - ageMs.toDouble() / thirtyDaysMs)
        } else {
            0.5
        }
        return count * recencyBoost
    }

    /**
     * Finds the index of the currently playing track in [queue] using authoritative [Track.id].
     *
     * Invariants:
     * - Returns -1 if [currentTrack] is null or [queue] is empty.
     * - If [currentIndex] is within bounds and queue[currentIndex].id == currentTrack.id,
     *   trusts [currentIndex] directly (crucial for duplicate instances of the same track ID).
     * - Otherwise locates the first track matching [currentTrack.id] (handling duplicate titles
     *   with different IDs safely).
     */
    fun findActiveTrackIndex(
        queue: List<Track>,
        currentTrack: Track?,
        currentIndex: Int = -1
    ): Int {
        if (currentTrack == null || queue.isEmpty()) return -1
        if (currentIndex in queue.indices && queue[currentIndex].id == currentTrack.id) {
            return currentIndex
        }
        return queue.indexOfFirst { it.id == currentTrack.id }
    }

    /**
     * Calculates the first visible item index for LazyListState so that [targetIndex]
     * is positioned approximately in the middle of the viewport.
     *
     * @param targetIndex The 0-based index of the target track in the queue.
     * @param visibleItemCount The number of visible items in the viewport (typically 6-8).
     * @param queueSize Total number of items in the queue.
     * @return The item index to pass to LazyListState.scrollToItem so target is centered.
     */
    fun calculateScrollIndex(
        targetIndex: Int,
        visibleItemCount: Int,
        queueSize: Int
    ): Int {
        if (targetIndex < 0 || queueSize <= 0) return 0
        val centerOffset = (visibleItemCount / 2).coerceAtLeast(1)
        val maxScrollIndex = (queueSize - 1).coerceAtLeast(0)
        return (targetIndex - centerOffset).coerceIn(0, maxScrollIndex)
    }
}
