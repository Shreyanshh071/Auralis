package com.auralis.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.player.MiniPlayer as PlayerMiniPlayer

/**
 * Backward-compatible MiniPlayer component delegating to the redesigned PlayerMiniPlayer.
 */
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    progress: Float, // 0.0f to 1.0f
    isFavorite: Boolean = false,
    onPlayPauseClick: () -> Unit,
    onNextClick: (() -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlayerMiniPlayer(
        track = track,
        isPlaying = isPlaying,
        progress = progress,
        isFavorite = isFavorite,
        onPlayPauseClick = onPlayPauseClick,
        onNextClick = onNextClick,
        onFavoriteToggle = onFavoriteToggle,
        onAddToPlaylist = onAddToPlaylist,
        onArtistClick = onArtistClick,
        onClick = onClick,
        modifier = modifier
    )
}
