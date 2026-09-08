package com.auralis.music.domain.model

class SleepTimerManager {
    var deadlineEpochMs: Long? = null
        private set

    val isSet: Boolean
        get() = deadlineEpochMs != null

    fun isActive(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val deadline = deadlineEpochMs ?: return false
        return deadline > nowEpochMs
    }

    val isActive: Boolean
        get() = isActive(System.currentTimeMillis())

    fun setTimerSeconds(durationSeconds: Long): Long {
        val durationMs = durationSeconds.coerceAtLeast(0L) * 1000L
        val deadline = System.currentTimeMillis() + durationMs
        deadlineEpochMs = deadline
        return deadline
    }

    fun setTimer(durationMinutes: Int): Long {
        return setTimerSeconds(durationMinutes.toLong() * 60L)
    }

    fun cancel() {
        deadlineEpochMs = null
    }

    fun getRemainingSeconds(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val deadline = deadlineEpochMs ?: return 0L
        val remainingMs = (deadline - nowEpochMs).coerceAtLeast(0L)
        return remainingMs / 1000L
    }

    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val deadline = deadlineEpochMs ?: return false
        return nowEpochMs >= deadline
    }
}
