package com.auralis.music.windows

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerShortcutTest {
    @OptIn(InternalComposeUiApi::class)
    private fun keyDown(key: Key) = KeyEvent(key = key, type = KeyEventType.KeyDown)

    private class Actions(var playerOpen: Boolean) : ShortcutActions {
        var immersives = 0
        var closes = 0
        override fun togglePlayPause() {}
        override fun next() {}
        override fun previous() {}
        override fun seekBy(deltaMs: Long) {}
        override fun volumeBy(delta: Float) {}
        override fun toggleMute() {}
        override fun focusSearch() {}
        override fun togglePlayer() {}
        override fun showLyrics() {}
        override fun showQueue() {}
        override fun closePlayer(): Boolean = false
        override fun hidePlayer(): Boolean {
            if (!playerOpen) return false
            playerOpen = false
            closes++
            return true
        }
        override fun openImmersive() { immersives++ }
    }

    @Test
    fun iClosesTheOpenPlayerInsteadOfOpeningImmersive() {
        val actions = Actions(playerOpen = true)
        assertTrue(handleShortcut(keyDown(Key.I), typing = false, actions = actions))
        assertFalse(actions.playerOpen)
        assertEquals(1, actions.closes)
        assertEquals(0, actions.immersives)
    }

    @Test
    fun iOpensImmersiveWhenThePlayerIsClosed() {
        val actions = Actions(playerOpen = false)
        assertTrue(handleShortcut(keyDown(Key.I), typing = false, actions = actions))
        assertEquals(0, actions.closes)
        assertEquals(1, actions.immersives)
    }

    @Test
    fun iDoesNothingWhileTyping() {
        val actions = Actions(playerOpen = true)
        assertFalse(handleShortcut(keyDown(Key.I), typing = true, actions = actions))
        assertTrue(actions.playerOpen)
        assertEquals(0, actions.immersives)
    }
}
