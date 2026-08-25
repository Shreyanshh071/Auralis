package com.auralis.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.player.NowPlayingModal
import com.auralis.music.ui.viewmodel.PlayerUiState

/**
 * Backward-compatible NowPlayingSheet wrapper delegating to the exact Auralis NowPlayingModal.
 */
@Composable
fun NowPlayingSheet(
    uiState: PlayerUiState,
    userPlaylists: List<Playlist> = emptyList(),
    onPlayPauseClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLyricsView: () -> Unit = {},
    onLyricsOffsetChange: (Long) -> Unit = {},
    onSleepTimerSelect: (Int) -> Unit = {},
    onSelectQueueTrack: (Int) -> Unit = {},
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    NowPlayingModal(
        uiState = uiState,
        userPlaylists = userPlaylists,
        onPlayPauseClick = onPlayPauseClick,
        onSeekTo = onSeekTo,
        onNextClick = onNextClick,
        onPreviousClick = onPreviousClick,
        onToggleShuffle = onToggleShuffle,
        onToggleRepeat = onToggleRepeat,
        onToggleFavorite = onToggleFavorite,
        onSleepTimerSelect = onSleepTimerSelect,
        onSelectQueueTrack = onSelectQueueTrack,
        onLyricsOffsetChange = onLyricsOffsetChange,
        onAddToPlaylist = onAddToPlaylist,
        onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
