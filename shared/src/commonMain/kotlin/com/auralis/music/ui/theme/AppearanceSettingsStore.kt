package com.auralis.music.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.auralis.music.domain.model.AppearanceSettings
import kotlinx.coroutines.flow.Flow

/** Where the appearance settings are kept: the phone's DataStore, a file on desktop. */
interface AppearanceSettingsStore {
    /** Defaults, returned at once so the first frame never waits for storage. */
    fun getInitialSettings(): AppearanceSettings
    val settingsFlow: Flow<AppearanceSettings>
    suspend fun updateSettings(settings: AppearanceSettings)
    /** Hides one of the Top / Weekly / Monthly Most playlists from the Library. */
    suspend fun hideMostPlayedPlaylist(playlistId: String)
}

/** The app's [AppearanceSettingsStore], for screens that change appearance settings. */
val LocalAppearanceSettingsStore = staticCompositionLocalOf<AppearanceSettingsStore?> { null }
