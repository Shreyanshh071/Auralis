package com.auralis.music.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Suspend only decorative work behind a modal. Lyrics stay visible above a sheet, so they keep
 * running, as do audio and provider requests.
 */
@Stable
class PlayerModalVisualState {
    var openCount by mutableIntStateOf(0)
        private set
    val animationsActive: Boolean get() = openCount == 0
    fun opened() { openCount++ }
    fun closed() { openCount = (openCount - 1).coerceAtLeast(0) }
}

val LocalPlayerModalVisualState = staticCompositionLocalOf<PlayerModalVisualState?> { null }
