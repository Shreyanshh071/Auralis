package com.auralis.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * Reports `(token, hasFocus)` for editable text inputs so window-level shortcuts
 * stay suppressed even when a dialog or popup sits outside the main search bar.
 */
val LocalTypingFocusTracker = staticCompositionLocalOf<((Any, Boolean) -> Unit)?> { null }

/**
 * Registers this modifier's focus state with [LocalTypingFocusTracker] and clears it on dispose.
 */
@Composable
fun Modifier.reportTypingFocus(): Modifier {
    val tracker = LocalTypingFocusTracker.current ?: return this
    val token = remember { Any() }
    DisposableEffect(tracker, token) {
        onDispose { tracker(token, false) }
    }
    return this.onFocusChanged { state -> tracker(token, state.isFocused) }
}
