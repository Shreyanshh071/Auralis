package com.auralis.music.windows

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.event.KeyEvent.*
import kotlin.test.*

@OptIn(InternalComposeUiApi::class)
class MacShortcutsTest {

    private class RecordingActions : ShortcutActions {
        var playPauseCount = 0
        var nextCount = 0
        var previousCount = 0
        var seekDeltas = mutableListOf<Long>()
        var volumeDeltas = mutableListOf<Float>()
        var muteCount = 0
        var searchFocusCount = 0
        var togglePlayerCount = 0
        var lyricsCount = 0
        var queueCount = 0
        var playerClosed = false
        var settingsOpened = false
        var windowClosed = false
        var windowMinimized = false

        override fun togglePlayPause() { playPauseCount++ }
        override fun next() { nextCount++ }
        override fun previous() { previousCount++ }
        override fun seekBy(deltaMs: Long) { seekDeltas.add(deltaMs) }
        override fun volumeBy(delta: Float) { volumeDeltas.add(delta) }
        override fun toggleMute() { muteCount++ }
        override fun focusSearch() { searchFocusCount++ }
        override fun togglePlayer() { togglePlayerCount++ }
        override fun showLyrics() { lyricsCount++ }
        override fun showQueue() { queueCount++ }
        override fun closePlayer(): Boolean { playerClosed = true; return true }
        override fun openSettings() { settingsOpened = true }
        override fun closeWindow() { windowClosed = true }
        override fun minimizeWindow() { windowMinimized = true }
    }

    private fun keyEvent(
        key: Key,
        meta: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        type: KeyEventType = KeyEventType.KeyDown
    ): KeyEvent = KeyEvent(
        key = key,
        type = type,
        codePoint = 0,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        isAltPressed = false,
        isShiftPressed = shift
    )

    @Test
    fun testMacCommandShortcuts() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        val actions = RecordingActions()

        // ⌘F -> Search
        assertTrue(handleShortcut(keyEvent(Key.F, meta = true), typing = false, actions = actions))
        assertEquals(1, actions.searchFocusCount)

        // ⌘K -> Search
        assertTrue(handleShortcut(keyEvent(Key.K, meta = true), typing = false, actions = actions))
        assertEquals(2, actions.searchFocusCount)

        // ⌘, -> Settings
        assertTrue(handleShortcut(keyEvent(Key.Comma, meta = true), typing = false, actions = actions))
        assertTrue(actions.settingsOpened)

        // ⌘W -> Close Window
        assertTrue(handleShortcut(keyEvent(Key.W, meta = true), typing = false, actions = actions))
        assertTrue(actions.windowClosed)

        // ⌘M -> Minimize Window
        assertTrue(handleShortcut(keyEvent(Key.M, meta = true), typing = false, actions = actions))
        assertTrue(actions.windowMinimized)

        // ⌘U -> Queue (macOS standard)
        assertTrue(handleShortcut(keyEvent(Key.U, meta = true), typing = false, actions = actions))
        assertEquals(1, actions.queueCount)

        // ⌘P -> Toggle Player
        assertTrue(handleShortcut(keyEvent(Key.P, meta = true), typing = false, actions = actions))
        assertEquals(1, actions.togglePlayerCount)

        // ⌘L -> Lyrics
        assertTrue(handleShortcut(keyEvent(Key.L, meta = true), typing = false, actions = actions))
        assertEquals(1, actions.lyricsCount)

        // ⌘Up / ⌘Down -> Volume
        assertTrue(handleShortcut(keyEvent(Key.DirectionUp, meta = true), typing = false, actions = actions))
        assertTrue(handleShortcut(keyEvent(Key.DirectionDown, meta = true), typing = false, actions = actions))
        assertEquals(listOf(0.05f, -0.05f), actions.volumeDeltas)

        // ⌘Right / ⌘Left -> Next / Previous
        assertTrue(handleShortcut(keyEvent(Key.DirectionRight, meta = true), typing = false, actions = actions))
        assertTrue(handleShortcut(keyEvent(Key.DirectionLeft, meta = true), typing = false, actions = actions))
        assertEquals(1, actions.nextCount)
        assertEquals(1, actions.previousCount)

        // On Mac, ⌘Q should NOT trigger showQueue()
        actions.queueCount = 0
        assertFalse(handleShortcut(keyEvent(Key.Q, meta = true), typing = false, actions = actions))
        assertEquals(0, actions.queueCount)

        // Space / K -> Play/Pause
        assertTrue(handleShortcut(keyEvent(Key.Spacebar), typing = false, actions = actions))
        assertTrue(handleShortcut(keyEvent(Key.K), typing = false, actions = actions))
        assertEquals(2, actions.playPauseCount)

        // Escape -> Close player
        assertTrue(handleShortcut(keyEvent(Key.Escape), typing = false, actions = actions))
        assertTrue(actions.playerClosed)
    }

    @Test
    fun testTypingSuppressesEditingKeys() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        val actions = RecordingActions()

        // Space and K do not toggle play/pause when typing
        assertFalse(handleShortcut(keyEvent(Key.Spacebar), typing = true, actions = actions))
        assertFalse(handleShortcut(keyEvent(Key.K), typing = true, actions = actions))
        assertEquals(0, actions.playPauseCount)

        // But ⌘F and ⌘, still work while typing
        assertTrue(handleShortcut(keyEvent(Key.F, meta = true), typing = true, actions = actions))
        assertEquals(1, actions.searchFocusCount)
        assertTrue(handleShortcut(keyEvent(Key.Comma, meta = true), typing = true, actions = actions))
        assertTrue(actions.settingsOpened)
    }
}
