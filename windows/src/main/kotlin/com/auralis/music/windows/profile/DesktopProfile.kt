package com.auralis.music.windows.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.auralis.music.data.datastore.SettingsStores
import com.auralis.music.domain.model.PlayerSettings
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.profile.ProfileSheet
import com.auralis.music.ui.screens.SettingsScreen
import com.auralis.music.ui.viewmodel.AuthUiState
import com.auralis.music.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

/**
 * The phone's Profile sheet on Windows: Settings, imports, YouTube account and Discord. There is
 * no Auralis account on Windows yet (Firebase's Android sign-in), so it shows the signed-out
 * profile; the library on this PC is local.
 */
@Composable
fun DesktopProfile(
    stores: SettingsStores,
    libraryViewModel: LibraryViewModel,
    history: HistoryRepository,
    search: SearchRepository,
    hasActiveTrack: Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val playerSettings by stores.settings.settingsFlow.collectAsState(PlayerSettings())
    val library by libraryViewModel.uiState.collectAsState()
    val authUiState = remember { AuthUiState() }
    ProfileSheet(
        authUiState = authUiState,
        playerSettings = playerSettings,
        onThemeModeChange = { scope.launch { stores.settings.setThemeMode(it) } },
        onAudioQualityChange = { scope.launch { stores.settings.setAudioQuality(it) } },
        onToggleGaplessPlayback = { scope.launch { stores.settings.setGaplessPlayback(it) } },
        onToggleSkipSilence = { scope.launch { stores.settings.setSkipSilence(it) } },
        onToggleSpatialAudio = { scope.launch { stores.settings.setSpatialAudio(it) } },
        onClearCache = { com.auralis.music.desktop.playback.DesktopStreamResolver.clearCache() },
        onImportYouTubePlaylist = libraryViewModel::importYouTubePlaylist,
        onImportYouTubeLibraryPlaylists = libraryViewModel::importYouTubeLibraryPlaylists,
        onClearYouTubeImportMessage = libraryViewModel::clearYouTubeImportMessage,
        isImportingYouTube = library.isImporting,
        youtubeImportMessage = library.importMessage,
        onOpenPlaylistSelector = {},
        onSyncLikedMusic = {},
        onDisconnect = {},
        onClosePlaylistSelector = {},
        onTogglePlaylistSelection = {},
        onSelectAllPlaylists = {},
        onImportSpotifyPlaylist = { libraryViewModel.importSpotifyPlaylist(it) },
        onImportSpotifyLibraryPlaylists = libraryViewModel::importSpotifyLibraryPlaylists,
        onClearSpotifyImportMessage = libraryViewModel::clearSpotifyImportMessage,
        isImportingSpotify = library.isImportingSpotify,
        spotifyImportMessage = library.spotifyImportMessage,
        onDismiss = {
            libraryViewModel.clearSpotifyImportMessage()
            libraryViewModel.clearYouTubeImportMessage()
            onDismiss()
        },
        historyRepository = history,
        searchRepository = search,
        hasActiveTrack = hasActiveTrack
    )
}

/** The phone's Settings hub on Windows, opened straight from the sidebar. */
@Composable
fun DesktopSettings(
    stores: SettingsStores,
    history: HistoryRepository,
    search: SearchRepository,
    hasActiveTrack: Boolean,
    onOpenProfile: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val playerSettings by stores.settings.settingsFlow.collectAsState(PlayerSettings())
    SettingsScreen(
        settings = playerSettings,
        onThemeModeChange = { scope.launch { stores.settings.setThemeMode(it) } },
        onAudioQualityChange = { scope.launch { stores.settings.setAudioQuality(it) } },
        onToggleGaplessPlayback = { scope.launch { stores.settings.setGaplessPlayback(it) } },
        onToggleSkipSilence = { scope.launch { stores.settings.setSkipSilence(it) } },
        onToggleSpatialAudio = { scope.launch { stores.settings.setSpatialAudio(it) } },
        onClearCache = { com.auralis.music.desktop.playback.DesktopStreamResolver.clearCache() },
        onNavigateToAccount = onOpenProfile,
        onDismiss = onDismiss,
        historyRepository = history,
        searchRepository = search,
        hasActiveTrack = hasActiveTrack
    )
}
