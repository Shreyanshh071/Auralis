package com.auralis.music.windows.profile

/**
 * When the desktop checks the account library on its own, without a local edit to trigger it.
 * A healthy Firestore account is checked every [intervalMs]; an account whose changes arrive by
 * notification is not checked at all; a failed sync is retried after 1, 2, 4, 8 minutes and then
 * at the interval, whichever backend it failed on.
 */
internal class LibraryCheckSchedule(private val intervalMs: Long = 15 * 60_000L, private val firstRetryMs: Long = 60_000L) {
    private var failures = 0
    private var lastAt = Long.MIN_VALUE

    fun due(now: Long, notified: Boolean): Boolean {
        if (lastAt == Long.MIN_VALUE) lastAt = now
        if (failures == 0) return !notified && now - lastAt >= intervalMs
        return now - lastAt >= (firstRetryMs shl (failures - 1).coerceAtMost(10)).coerceAtMost(intervalMs)
    }

    fun done(now: Long, succeeded: Boolean) {
        lastAt = now
        failures = if (succeeded) 0 else failures + 1
    }
}
