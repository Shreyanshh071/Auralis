package com.auralis.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * Desktop back: the most recently registered enabled handler wins, like Android's back stack of
 * callbacks. The window calls [DesktopBackDispatcher.back] on Esc and the mouse back button.
 */
object DesktopBackDispatcher {
    private class Entry(var enabled: () -> Boolean, var onBack: () -> Unit)
    private val entries = mutableListOf<Entry>()

    /** Runs the newest enabled handler; false when none wanted the back press. */
    fun back(): Boolean {
        val entry = synchronized(entries) { entries.lastOrNull { it.enabled() } } ?: return false
        entry.onBack()
        return true
    }

    internal fun add(enabled: () -> Boolean, onBack: () -> Unit): Any = Entry(enabled, onBack).also { synchronized(entries) { entries += it } }
    internal fun remove(token: Any) { synchronized(entries) { entries.remove(token) } }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentEnabled = rememberUpdatedState(enabled)
    val currentOnBack = rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val token = DesktopBackDispatcher.add({ currentEnabled.value }, { currentOnBack.value() })
        onDispose { DesktopBackDispatcher.remove(token) }
    }
}
