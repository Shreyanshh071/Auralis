package com.auralis.music.windows

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import java.awt.event.KeyEvent.VK_DOWN
import java.awt.event.KeyEvent.VK_ESCAPE
import java.awt.event.KeyEvent.VK_F
import java.awt.event.KeyEvent.VK_K
import java.awt.event.KeyEvent.VK_L
import java.awt.event.KeyEvent.VK_LEFT
import java.awt.event.KeyEvent.VK_M
import java.awt.event.KeyEvent.VK_P
import java.awt.event.KeyEvent.VK_Q
import java.awt.event.KeyEvent.VK_RIGHT
import java.awt.event.KeyEvent.VK_SPACE
import java.awt.event.KeyEvent.VK_UP

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
    // Compare key codes, not keys: an arrow on the number pad (Num Lock off) is a different Key
    // from the main arrow key but the same key code, and both should work.
    val code = event.key.nativeKeyCode
    // Work everywhere, even in the search field.
    if (ctrl) {
        when (code) {
            VK_UP -> { actions.volumeBy(0.05f); return true }
            VK_DOWN -> { actions.volumeBy(-0.05f); return true }
            VK_F, VK_K -> { actions.focusSearch(); return true }
            VK_P -> { actions.togglePlayer(); return true }
            VK_L -> { actions.showLyrics(); return true }
            VK_Q -> { actions.showQueue(); return true }
            else -> {}
        }
    }
    if (code == VK_ESCAPE && actions.closePlayer()) return true
    if (typing) return false
    if (ctrl) {
        when (code) {
            VK_RIGHT -> actions.next()
            VK_LEFT -> actions.previous()
            else -> return false
        }
        return true
    }
    val step = if (event.isShiftPressed) 15_000L else 5_000L
    when (code) {
        VK_SPACE, VK_K -> actions.togglePlayPause()
        VK_RIGHT -> actions.seekBy(step)
        VK_LEFT -> actions.seekBy(-step)
        VK_M -> actions.toggleMute()
        else -> return false
    }
    return true
}
