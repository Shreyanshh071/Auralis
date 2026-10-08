package com.auralis.music.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.LyricsData

/**
 * The current-line preview in the artwork-to-title gap; tap for full lyrics. Draws the
 * immersive player's preview line, so every player shows the same animated lyric.
 */
@Composable
internal fun InlinePlayerLyrics(
    lyrics: LyricsData?,
    positionState: State<Long>,
    offsetMs: Long,
    isLoading: Boolean,
    trackId: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onOpenFullLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth().heightIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
        LyricPreviewLine(
            lyrics = lyrics,
            isLoading = isLoading,
            trackId = trackId,
            positionState = positionState,
            offsetMs = offsetMs,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            onClick = onOpenFullLyrics
        )
    }
}
