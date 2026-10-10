package com.auralis.music.windows

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** What the keyboard shortcuts can do. */
interface ShortcutActions {
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekBy(deltaMs: Long)
    fun volumeBy(delta: Float)
    fun toggleMute()
    fun focusSearch()
    fun togglePlayer()
    fun showLyrics()
    fun showQueue()
    /** Closes the player; false when it wasn't open. */
    fun closePlayer(): Boolean
}

/**
 * The Windows keyboard shortcuts, checked before the focused control sees the key. Keys that
 * also edit text (Space, arrows, Ctrl+arrows, K, M) only act when no text field has focus, so
 * typing and moving through a search is never interrupted.
 *
 * | Keys | Action |
 * |---|---|
 * | Space / K | Play / pause |
 * | Ctrl+→ / Ctrl+← | Next / previous song |
 * | → / ← | Forward / back 5 s (Shift: 15 s) |
 * | Ctrl+↑ / Ctrl+↓ | Volume up / down |
 * | M | Mute / unmute |
 * | Ctrl+F or Ctrl+K | Search |
 * | Ctrl+P | Open / close the player |
 * | Ctrl+L / Ctrl+Q | Player with lyrics / queue |
 * | Esc | Close the player |
 */
fun handleShortcut(event: KeyEvent, typing: Boolean, actions: ShortcutActions): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val ctrl = event.isCtrlPressed
    // Work everywhere, even in the search field.
    if (ctrl) {
        when (event.key) {
            Key.DirectionUp -> { actions.volumeBy(0.05f); return true }
            Key.DirectionDown -> { actions.volumeBy(-0.05f); return true }
            Key.F, Key.K -> { actions.focusSearch(); return true }
            Key.P -> { actions.togglePlayer(); return true }
            Key.L -> { actions.showLyrics(); return true }
            Key.Q -> { actions.showQueue(); return true }
            else -> {}
        }
    }
    if (event.key == Key.Escape && actions.closePlayer()) return true
    if (typing) return false
    if (ctrl) {
        when (event.key) {
            Key.DirectionRight -> actions.next()
            Key.DirectionLeft -> actions.previous()
            else -> return false
        }
        return true
    }
    val step = if (event.isShiftPressed) 15_000L else 5_000L
    when (event.key) {
        Key.Spacebar, Key.K -> actions.togglePlayPause()
        Key.DirectionRight -> actions.seekBy(step)
        Key.DirectionLeft -> actions.seekBy(-step)
        Key.M -> actions.toggleMute()
        else -> return false
    }
    return true
}
