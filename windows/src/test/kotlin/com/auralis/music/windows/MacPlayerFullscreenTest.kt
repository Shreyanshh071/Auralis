package com.auralis.music.windows

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacPlayerFullscreenTest {

    @Test
    fun maximizedWindowStaysMaximizedWhenEnteringAndExitingPlayer() {
        var currentPlacement = WindowPlacement.Maximized
        var setCallCount = 0

        // Enter player fullscreen while already maximized
        val saved = applyMacPlayerFullscreen(
            enabled = true,
            currentPlacement = currentPlacement,
            savedPlacement = null,
            onSetPlacement = {
                currentPlacement = it
                setCallCount++
            }
        )

        // Must not touch placement (no unmaximize to Floating, no shrink)
        assertEquals(0, setCallCount, "Must not set placement when already maximized")
        assertEquals(WindowPlacement.Maximized, currentPlacement, "Must remain maximized")
        assertEquals(WindowPlacement.Maximized, saved, "Must remember it was already maximized")

        // Exit player fullscreen
        val afterExit = applyMacPlayerFullscreen(
            enabled = false,
            currentPlacement = currentPlacement,
            savedPlacement = saved,
            onSetPlacement = {
                currentPlacement = it
                setCallCount++
            }
        )

        // Must stay maximized (not shrink back to floating)
        assertEquals(0, setCallCount, "Must not set placement when exiting from already maximized")
        assertEquals(WindowPlacement.Maximized, currentPlacement, "Must stay maximized after exiting player")
        assertNull(afterExit, "Saved placement must be cleared")
    }

    @Test
    fun nativeFullscreenWindowStaysFullscreenWhenEnteringAndExitingPlayer() {
        var currentPlacement = WindowPlacement.Fullscreen
        var setCallCount = 0

        // Enter player fullscreen while in native fullscreen
        val saved = applyMacPlayerFullscreen(
            enabled = true,
            currentPlacement = currentPlacement,
            savedPlacement = null,
            onSetPlacement = {
                currentPlacement = it
                setCallCount++
            }
        )

        assertEquals(0, setCallCount, "Must not set placement when already in native fullscreen")
        assertEquals(WindowPlacement.Fullscreen, currentPlacement, "Must remain in native fullscreen")
        assertEquals(WindowPlacement.Fullscreen, saved)

        // Exit player fullscreen
        val afterExit = applyMacPlayerFullscreen(
            enabled = false,
            currentPlacement = currentPlacement,
            savedPlacement = saved,
            onSetPlacement = {
                currentPlacement = it
                setCallCount++
            }
        )

        assertEquals(0, setCallCount, "Must not set placement when exiting from native fullscreen")
        assertEquals(WindowPlacement.Fullscreen, currentPlacement, "Must remain in native fullscreen")
        assertNull(afterExit)
    }

    @Test
    fun floatingWindowExpandsToMaximizedAndRestoresToFloatingOnExit() {
        var currentPlacement = WindowPlacement.Floating
        var setCallCount = 0

        // Enter player fullscreen while floating
        val saved = applyMacPlayerFullscreen(
            enabled = true,
            currentPlacement = currentPlacement,
            savedPlacement = null,
            onSetPlacement = {
                currentPlacement = it
                setCallCount++
            }
        )

        assertEquals(1, setCallCount, "Must set placement to Maximized")
        assertEquals(WindowPlacement.Maximized, currentPlacement, "Window must expand to Maximized")
        assertEquals(WindowPlacement.Floating, saved, "Must record that window was previously Floating")

        // Exit player fullscreen
        val afterExit = applyMacPlayerFullscreen(
            enabled = false,
            currentPlacement = currentPlacement,
            savedPlacement = saved,
            onSetPlacement = {
                currentPlacement = it
                setCallCount++
            }
        )

        assertEquals(2, setCallCount, "Must restore placement to Floating")
        assertEquals(WindowPlacement.Floating, currentPlacement, "Window must return to Floating")
        assertNull(afterExit, "Saved placement must be cleared")
    }
}
