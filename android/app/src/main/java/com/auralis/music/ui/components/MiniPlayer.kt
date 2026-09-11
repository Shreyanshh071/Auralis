package com.auralis.music.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.player.MiniPlayer as PlayerMiniPlayer

/**
 * Backward-compatible MiniPlayer component delegating to the redesigned PlayerMiniPlayer.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    progress: Float = 0f, // 0.0f to 1.0f
    progressProvider: (() -> Float)? = null,
    progressState: com.auralis.music.ui.player.ProgressState? = null,
    queue: List<Track> = emptyList(),
    currentIndex: Int = 0,
    isFavorite: Boolean = false,
    userScrollEnabled: Boolean = true,
    onPlayPauseClick: () -> Unit,
    onNextClick: (() -> Unit)? = null,
    onPreviousClick: (() -> Unit)? = null,
    onSelectQueueTrack: ((Int) -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    PlayerMiniPlayer(
        track = track,
        isPlaying = isPlaying,
        progress = progress,
        progressProvider = progressProvider,
        progressState = progressState,
        queue = queue,
        currentIndex = currentIndex,
        isFavorite = isFavorite,
        userScrollEnabled = userScrollEnabled,
        onPlayPauseClick = onPlayPauseClick,
        onNextClick = onNextClick,
        onPreviousClick = onPreviousClick,
        onSelectQueueTrack = onSelectQueueTrack,
        onFavoriteToggle = onFavoriteToggle,
        onAddToPlaylist = onAddToPlaylist,
        onArtistClick = onArtistClick,
        onClose = onClose,
        onClick = onClick,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        hazeState = hazeState,
        modifier = modifier
    )
}

