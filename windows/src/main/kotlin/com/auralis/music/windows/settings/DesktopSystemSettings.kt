package com.auralis.music.windows.settings

import com.auralis.music.util.KeyValueStore
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

internal data class DesktopSystemSettings(val closeToTray: Boolean = false, val songNotifications: Boolean = false)

internal class DesktopSystemSettingsStore(private val store: KeyValueStore) {
    private val mutable = MutableStateFlow(DesktopSystemSettings(
        store.getString("closeToTray", "false") == "true",
        store.getString("songNotifications", "false") == "true"))
    val settings = mutable.asStateFlow()
    @Synchronized fun update(transform: (DesktopSystemSettings) -> DesktopSystemSettings) {
        val next = transform(mutable.value)
        store.putStrings(mapOf("closeToTray" to next.closeToTray.toString(), "songNotifications" to next.songNotifications.toString()))
        mutable.value = next
    }
}

internal object DesktopSystemPreferences {
    val store by lazy { DesktopSystemSettingsStore(PropertiesFileKeyValueStore(File(desktopAppDataDir(), "desktop-system.properties"), synchronous = true)) }
}
