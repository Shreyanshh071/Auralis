package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.WindowsAudioOutput
import com.auralis.music.desktop.playback.WindowsAudioOutputs
import org.junit.Assume.assumeTrue
import kotlin.test.*

class WindowsAudioOutputsTest {
    @Test fun realWindowsEndpointsAreAvailableWithoutStartingPlayback() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val endpoints = WindowsAudioOutputs.read()
        assertTrue(endpoints.isNotEmpty(), "This Windows test machine has an active speaker output")
        assertEquals(1, endpoints.count { it.isDefault })
        assertTrue(endpoints.all { it.id.isNotBlank() && it.name.isNotBlank() })
        assertEquals(endpoints.size, endpoints.distinctBy { it.id }.size)
        println("Windows default audio output: ${endpoints.single { it.isDefault }.name}")
        println("Active Windows audio outputs: ${endpoints.joinToString { it.name }}")
        val rows = WindowsAudioOutputs.menu(endpoints, emptyList())
        assertTrue(rows.first().second.contains(endpoints.single { it.isDefault }.name))
        assertTrue(endpoints.filterNot { it.isDefault }.all { endpoint -> rows.any { it.first == endpoint.id && it.second == endpoint.name } })
        assertEquals(endpoints.size, rows.size, "Default endpoint must not appear twice")
    }

    @Test fun menuRefreshTracksWindowsDefaultAndHotPluggedOutputsByTheirRealIds() {
        val speakers = WindowsAudioOutput("speaker-endpoint", "Speakers (Realtek Audio)", true)
        val headset = WindowsAudioOutput("headset-endpoint", "Headphones (Bluetooth)", false)
        val initial = WindowsAudioOutputs.menu(listOf(speakers, headset), emptyList())
        assertEquals("" to "Windows default — Speakers (Realtek Audio)", initial.first())
        val changed = WindowsAudioOutputs.menu(listOf(headset.copy(isDefault = true)), emptyList())
        assertEquals("" to "Windows default — Headphones (Bluetooth)", changed.first())
        assertFalse(changed.any { it.first == speakers.id })
        assertEquals(1, changed.size, "A single default endpoint needs a single menu row")
        assertEquals(headset.id, initial.last().first, "Other selections use the actual endpoint identifier")
    }

    @Test fun enumerationFailureKeepsTheActualDefaultRoutingOptionAndUsesVlcFallbackNames() {
        assertEquals(listOf("" to "Windows default"), WindowsAudioOutputs.menu(emptyList(), emptyList()))
        assertEquals(listOf("" to "Windows default", "vlc-id" to "USB DAC"),
            WindowsAudioOutputs.menu(emptyList(), listOf("" to "Default", "vlc-id" to "USB DAC", "vlc-id" to "USB DAC")))
    }
}
