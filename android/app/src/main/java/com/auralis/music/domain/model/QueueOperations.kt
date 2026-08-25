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
}
