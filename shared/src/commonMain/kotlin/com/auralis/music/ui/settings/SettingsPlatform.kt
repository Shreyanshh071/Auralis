package com.auralis.music.ui.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Settings pages need from the platform. Pieces a platform doesn't have are null, and
 * the pages show why instead of a button that does nothing.
 */
interface SettingsPlatform {
    /** The app's version name, e.g. "1.1.2". */
    val appVersion: String

    /** Opens the system's settings page for this app; null where the system has none. */
    val openSystemAppSettings: (() -> Unit)?

    /** Material You colours from the wallpaper; null where the system has none. */
    fun systemColorScheme(isDark: Boolean): ColorScheme?

    val appLanguage: AppLanguageControl?
    val storage: StorageControl
    val history: HistoryControl
    val updater: UpdaterControl?
    val discord: DiscordControl?
    val nowPlaying: NowPlayingControl
}

/** The app's own display language (separate from the content language). */
interface AppLanguageControl {
    /** The chosen language's name, or "System default". */
    fun currentLabel(): String
    fun currentTag(): String
    fun available(): List<String>
    fun apply(tag: String)
}

interface StorageControl {
    /** Offline downloads; null where the app has none. */
    fun downloadedSongsBytes(): Long?
    fun songCacheBytes(): Long
    fun imageCacheBytes(): Long
    /** Each returns the bytes freed. */
    fun clearSongCache(): Long
    fun clearImageCache(): Long
}

/** Clears what the app keeps about listening and searching. */
interface HistoryControl {
    suspend fun clearListenHistory()
    suspend fun clearSearchHistory()
}

interface UpdaterControl {
    val isChecking: StateFlow<Boolean>
    /** Starts a check; [UpdateFlowOverlay] shows its progress and result. */
    fun checkNow()
    @Composable
    fun UpdateFlowOverlay(onDismiss: () -> Unit)
}

/** The Discord status connection. */
interface DiscordControl {
    /** Why connecting works differently here (shown on the page), or null. */
    val note: String?
    fun connect(onResult: (success: Boolean, username: String, detail: String) -> Unit)
    fun disconnect()
    fun refresh()
}

/** The playing song, for previews (Discord status). */
interface NowPlayingControl {
    val track: StateFlow<Track?>
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    fun seekTo(ms: Long)
}

val LocalSettingsPlatform = staticCompositionLocalOf<SettingsPlatform> { error("No SettingsPlatform provided") }
