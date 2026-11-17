package com.auralis.music.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether the app is on screen. Background polling uses it to slow down while the app is hidden
 * (saving battery), and wakes straight back up the moment it returns.
 */
object AppVisibility {
    private val visible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = visible.asStateFlow()

    fun set(onScreen: Boolean) {
        visible.value = onScreen
    }

    /**
     * Waits [foregroundMs] while the app is on screen. While it's hidden, waits up to
     * [backgroundMs] but returns early as soon as the app comes back.
     */
    suspend fun pause(foregroundMs: Long, backgroundMs: Long) {
        if (visible.value) {
            kotlinx.coroutines.delay(foregroundMs)
        } else {
            withTimeoutOrNull(backgroundMs) { visible.first { it } }
        }
    }
}
