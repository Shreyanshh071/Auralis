package com.auralis.music.windows.settings

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.settings.DiscordControl
import com.auralis.music.ui.settings.HistoryControl
import com.auralis.music.ui.settings.NowPlayingControl
import com.auralis.music.ui.settings.SettingsPlatform
import com.auralis.music.ui.settings.StorageControl
import com.auralis.music.ui.settings.UpdaterControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * [SettingsPlatform] on Windows. No system app-settings page, wallpaper colours or per-app
 * wallpaper colours; app language and artwork cache are managed here. Updates
 * come as a new installer. Discord status goes through [discord] when the desktop client is there.
 */
class DesktopSettingsPlatform(
    private val playback: DesktopPlaybackController?,
    private val historyRepository: HistoryRepository,
    private val search: SearchRepository,
    override val discord: DiscordControl?
) : SettingsPlatform {
    override val appVersion: String get() = DesktopReleaseCheck.version
    override val openSystemAppSettings: (() -> Unit)? = null
    override fun systemColorScheme(isDark: Boolean): ColorScheme? = null
    override val appLanguage = DesktopAppLanguage
    override val updater: UpdaterControl? = null

    override val storage = object : StorageControl {
        override fun downloadedSongsBytes(): Long = com.auralis.music.desktop.library.DesktopDownloads.instance.entries.value.filter { com.auralis.music.desktop.library.DesktopDownloads.instance.available(it) }.sumOf { it.bytes }
        override fun songCacheBytes() = 0L
        override fun imageCacheBytes() = DesktopCaches.imageBytes()
        override fun clearSongCache() = 0L
        override fun clearImageCache(): Long { val bytes = DesktopCaches.imageBytes(); DesktopCaches.clearImages(); return bytes }
    }

    override val history = object : HistoryControl {
        override suspend fun clearListenHistory() = historyRepository.clearHistory()
        override suspend fun clearSearchHistory() = search.clearSearchHistory()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val none = MutableStateFlow<Track?>(null)

    override val nowPlaying = object : NowPlayingControl {
        override val track: StateFlow<Track?> =
            playback?.queue?.map { it.current }?.stateIn(scope, SharingStarted.Eagerly, playback.queue.value.current) ?: none
        override val isPlaying: StateFlow<Boolean> =
            playback?.state?.map { it == VlcAudioPlayer.State.PLAYING }?.stateIn(scope, SharingStarted.Eagerly, false)
                ?: MutableStateFlow(false)
        override val positionMs: StateFlow<Long> = playback?.positionMs ?: MutableStateFlow(0L)
        override val durationMs: StateFlow<Long> = playback?.lengthMs ?: MutableStateFlow(0L)
        override fun seekTo(ms: Long) { playback?.seekTo(ms) }
    }
}
