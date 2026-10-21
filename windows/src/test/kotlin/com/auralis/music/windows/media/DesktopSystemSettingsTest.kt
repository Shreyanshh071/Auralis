package com.auralis.music.windows.media

import com.auralis.music.domain.model.Track
import com.auralis.music.util.KeyValueStore
import com.auralis.music.windows.settings.*
import kotlin.test.*

class DesktopSystemSettingsTest {
    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, default: String) = values[key] ?: default
        override fun putStrings(values: Map<String, String>) { this.values.putAll(values) }
        override fun clear() = values.clear()
    }
    @Test fun bothPreferencesPersistIndependentlyAndApplyImmediately() {
        val disk = MemoryStore()
        val store = DesktopSystemSettingsStore(disk)
        assertEquals(DesktopSystemSettings(), store.settings.value)
        store.update { it.copy(closeToTray = true) }
        assertTrue(store.settings.value.closeToTray)
        assertFalse(store.settings.value.songNotifications)
        store.update { it.copy(songNotifications = true) }
        assertEquals(DesktopSystemSettings(true, true), DesktopSystemSettingsStore(disk).settings.value)
        store.update { it.copy(closeToTray = false) }
        assertEquals(DesktopSystemSettings(false, true), DesktopSystemSettingsStore(disk).settings.value)
    }
    @Test fun closeNeverHidesTheWindowWithoutAnAvailableTray() {
        assertFalse(shouldCloseToTray(false, true))
        assertFalse(shouldCloseToTray(true, false))
        assertTrue(shouldCloseToTray(true, true))
    }
    @Test fun notificationsUseActualTrackOnlyWhenItStartsInBackground() {
        val a = Track("a", "First song", "First artist")
        val b = Track("b", "Second song", "Second artist")
        val tracker = SongNotificationTracker(a.id)
        assertNull(tracker.started(a, true, true, true), "Restored startup song is not a new start")
        assertNull(tracker.started(b, false, true, true), "Buffering or paused is not playing")
        assertEquals(b, tracker.started(b, true, true, true))
        assertNull(tracker.started(b, true, true, true), "Repeated playback-state events cannot duplicate a notice")
        assertNull(tracker.started(a, true, true, false), "Foreground songs do not notify")
        assertNull(tracker.started(a, true, true, true), "Backgrounding a playing song does not notify")
        assertNull(tracker.started(b, true, false, true), "Disabled notifications are silent")
        assertNull(tracker.started(b, true, true, true), "Enabling a setting does not announce an old song")
        assertEquals(a, tracker.started(a, true, true, true))
    }
    @Test fun firstPlaybackOfARestoredButIdleSongIsANewStart() {
        val track = Track("restored", "Restored song", "Artist")
        val tracker = SongNotificationTracker(null)
        assertNull(tracker.started(track, false, true, true))
        assertEquals(track, tracker.started(track, true, true, true))
        assertNull(tracker.started(track, true, true, true))
    }
}
