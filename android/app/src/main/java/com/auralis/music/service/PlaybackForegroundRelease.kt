package com.auralis.music.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Recheck idle state after stream replacement callbacks have settled; never demote active audio. */
internal class PlaybackForegroundRelease(
    private val scope: CoroutineScope,
    private val shouldKeepForeground: () -> Boolean,
    private val releaseForeground: () -> Unit,
    private val idleDelayMs: Long = 1_000L
) {
    private var pendingRelease: Job? = null

    fun schedule() {
        if (pendingRelease?.isActive == true) return
        pendingRelease = scope.launch {
            delay(idleDelayMs)
            if (!shouldKeepForeground()) releaseForeground()
        }
    }

    fun cancel() {
        pendingRelease?.cancel()
        pendingRelease = null
    }
}
