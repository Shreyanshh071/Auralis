package com.auralis.music.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.auralis.music.domain.model.LyricsData

/**
 * The current-line preview in the artwork-to-title gap; tap for full lyrics. Draws the
 * immersive player's preview line, so every player shows the same animated lyric.
 *
 * The slot is a fixed two lines tall (one on the tightest [PlayerFit] step), so a line
 * wrapping or unwrapping never moves the title, controls or artwork below and above it.
 */
/**
 * Height of the lyric preview slot at the current [PlayerFit] step. The players reserve it even
 * while the preview is hidden, so the cover, title and controls never move when it is toggled.
 */
@Composable
internal fun inlineLyricsSlotHeight(): Dp {
    val fit = LocalPlayerFit.current
    return with(LocalDensity.current) {
        (BasePreviewLyricStyle.lineHeight * fit.lyricsScale * fit.lyricsMaxLines).toDp() + 8.dp
    }
}

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
    val fit = LocalPlayerFit.current
    val scale = fit.lyricsScale
    val spec = remember(scale, fit.lyricsMaxLines) { PreviewLyricSpec(scale = scale, maxLines = fit.lyricsMaxLines) }
    val slotHeight = inlineLyricsSlotHeight()
    Box(
        modifier.fillMaxWidth().height(slotHeight).clipToBounds(),
        contentAlignment = Alignment.CenterStart
    ) {
        CompositionLocalProvider(LocalPreviewLyricSpec provides spec) {
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
}
