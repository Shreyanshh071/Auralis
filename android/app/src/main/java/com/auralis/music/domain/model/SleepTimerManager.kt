package com.auralis.music.domain.model

class SleepTimerManager {
    var deadlineEpochMs: Long? = null
        private set

    val isActive: Boolean
        get() = deadlineEpochMs != null && deadlineEpochMs!! > System.currentTimeMillis()

    fun setTimer(durationMinutes: Int): Long {
        val durationMs = durationMinutes.toLong() * 60 * 1000
        val deadline = System.currentTimeMillis() + durationMs
        deadlineEpochMs = deadline
        return deadline
    }

    fun cancel() {
        deadlineEpochMs = null
    }

    fun getRemainingSeconds(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val deadline = deadlineEpochMs ?: return 0
        val remainingMs = (deadline - nowEpochMs).coerceAtLeast(0)
        return remainingMs / 1000
    }

    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val deadline = deadlineEpochMs ?: return false
        return nowEpochMs >= deadline
    }
}
