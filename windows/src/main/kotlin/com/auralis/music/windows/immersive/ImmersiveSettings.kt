package com.auralis.music.windows.immersive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.auralis.music.windows.settings.DesktopPreferences
import com.auralis.music.windows.settings.SettingChoice

private const val OFF = "Off"

private fun idleLabel(minutes: Int) = if (minutes == 0) "Never" else "After $minutes minutes"

/** When immersive mode starts by itself, and what each display shows. */
@Composable
internal fun ImmersiveSettings() {
    val revision by DesktopPreferences.store.changes.collectAsState()
    val preferences = remember(revision) { ImmersivePreferences() }
    val isMac = remember { System.getProperty("os.name").orEmpty().lowercase().contains("mac") }
    if (isMac) {
        com.auralis.music.windows.settings.SettingToggle(
            title = "Show on Lock Screen",
            description = "Show live lyrics and playback controls on the macOS Lock Screen while music plays.",
            checked = preferences.macLockScreenOverlay,
            onChecked = { preferences.macLockScreenOverlay = it }
        )
    }
    SettingChoice(
        title = "Start when idle",
        description = "While music plays and nothing is fullscreen. Any input leaves. Press I to start it yourself.",
        selected = idleLabel(preferences.idleMinutes),
        options = ImmersiveIdleChoices.map(::idleLabel)
    ) { label -> preferences.idleMinutes = ImmersiveIdleChoices.first { idleLabel(it) == label } }
    val displays = remember { connectedDisplays(null) }
    displays.forEachIndexed { index, display ->
        val selected = if (preferences.isOff(display)) OFF else preferences.view(display, index).label
        SettingChoice(
            title = "Display ${index + 1}",
            description = "${display.bounds.width} × ${display.bounds.height}",
            selected = selected,
            options = ImmersiveView.entries.map { it.label } + OFF
        ) { label ->
            val view = ImmersiveView.entries.firstOrNull { it.label == label }
            preferences.setOff(display, view == null)
            if (view != null) preferences.setView(display, view)
        }
    }
}
