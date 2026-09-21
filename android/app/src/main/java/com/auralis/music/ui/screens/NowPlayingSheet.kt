package com.auralis.music.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.player.NowPlayingModal
import com.auralis.music.ui.viewmodel.PlayerUiState

/**
 * Backward-compatible NowPlayingSheet wrapper delegating to the exact Auralis NowPlayingModal.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingSheet(
    uiState: PlayerUiState,
    playbackPositionState: State<Long>,
    lyricsClockSource: PlaybackClockSource? = null,
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
    onSearchLyricsManually: ((String, String) -> Unit)? = null,
    onSleepTimerSelect: (Int) -> Unit = {},
    onSelectQueueTrack: (Int) -> Unit = {},
    onReorderQueue: ((Int, Int) -> Unit)? = null,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onArtistClick: ((Artist) -> Unit)? = null,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    renderBackground: Boolean = true,
    currentQuality: com.auralis.music.domain.model.AudioQuality = com.auralis.music.domain.model.AudioQuality.AUTO,
    onAudioQualityChange: (com.auralis.music.domain.model.AudioQuality) -> Unit = {},
    isTrackPinned: ((String) -> Boolean)? = null,
    onPinTrackToSpeedDial: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    NowPlayingModal(
        uiState = uiState,
        playbackPositionState = playbackPositionState,
        lyricsClockSource = lyricsClockSource,
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
        onReorderQueue = onReorderQueue,
        onLyricsOffsetChange = onLyricsOffsetChange,
        onSearchLyricsManually = onSearchLyricsManually,
        onAddToPlaylist = onAddToPlaylist,
        onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
        onArtistClick = onArtistClick,
        onDismiss = onDismiss,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        renderBackground = renderBackground,
        currentQuality = currentQuality,
        onAudioQualityChange = onAudioQualityChange,
        isTrackPinned = isTrackPinned,
        onPinTrackToSpeedDial = onPinTrackToSpeedDial,
        modifier = modifier
    )
}
