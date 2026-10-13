package com.auralis.music.ui.lyrics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.lyrics.wavy.WavyProgressIndicator
import kotlin.math.pow

// Shared by the classic lyrics view (phone) and the MetroLyrics view (phone and desktop).

fun computeLyricsProgressiveBlur(
    standardBlur: Boolean,
    isSynced: Boolean,
    isPlain: Boolean,
    isSelected: Boolean,
    isCurrent: Boolean,
    isUserInteracting: Boolean,
    lineCenterPx: Float,
    activeLineCenterPx: Float,
    viewportStartPx: Float,
    viewportEndPx: Float
): Float {
    if (!standardBlur || !isSynced || isPlain || isSelected || isCurrent) {
        return 0f
    }
    // Manual reading mode stays sharp. Re-enabling sync restores the positional blur.
    if (isUserInteracting) {
        return 0f
    }
    if (!lineCenterPx.isFinite() || !activeLineCenterPx.isFinite() ||
        !viewportStartPx.isFinite() || !viewportEndPx.isFinite() ||
        viewportEndPx <= viewportStartPx
    ) {
        return 0f
    }

    val lineCenter = lineCenterPx.coerceIn(viewportStartPx, viewportEndPx)
    val activeCenter = activeLineCenterPx.coerceIn(viewportStartPx, viewportEndPx)
    // Use the same physical distance on both sides of the active line. Scaling each
    // side separately made past lines almost sharp while future lines blurred deeply.
    val falloffDistance = minOf(
        activeCenter - viewportStartPx,
        viewportEndPx - activeCenter
    ).coerceAtLeast(1f)
    val rawProgress = (kotlin.math.abs(lineCenter - activeCenter) / falloffDistance).coerceIn(0f, 1f)
    // Keep a small crisp band around the active line, then blur toward either edge.
    val blurProgress = ((rawProgress - 0.04f) / 0.96f).coerceIn(0f, 1f)
    return 24f * blurProgress.pow(1.35f)
}

data class LyricsBlurGeometry(
    val lineCenterPx: Float,
    val activeLineCenterPx: Float,
    val viewportStartPx: Float,
    val viewportEndPx: Float
)

fun resolveLyricsBlurGeometry(
    layoutInfo: LazyListLayoutInfo,
    lineIndex: Int,
    activeIndex: Int,
    fallbackActiveFraction: Float,
    fallbackViewportHeightPx: Float = 0f
): LyricsBlurGeometry {
    val measuredHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    val viewportHeight = if (measuredHeight > 0f) measuredHeight else fallbackViewportHeightPx.coerceAtLeast(0f)
    val viewportStart = layoutInfo.viewportStartOffset.toFloat()
    val viewportEnd = if (measuredHeight > 0f) layoutInfo.viewportEndOffset.toFloat() else (viewportStart + viewportHeight)
    val lineInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lineIndex }
    val activeInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
    val fallbackCenter = viewportStart + viewportHeight * fallbackActiveFraction.coerceIn(0f, 1f)
    val lineCenter = if (lineInfo != null) {
        lineInfo.offset + lineInfo.size / 2f
    } else if (viewportHeight > 0f && activeIndex >= 0) {
        // Fallback: estimate line position from active line index offset to prevent initial frame-0 unblurred flash
        val estimatedItemHeight = (viewportHeight * 0.08f).coerceIn(40f, 80f)
        fallbackCenter + (lineIndex - activeIndex) * estimatedItemHeight
    } else {
        Float.NaN
    }
    return LyricsBlurGeometry(
        lineCenterPx = lineCenter,
        activeLineCenterPx = activeInfo?.let { it.offset + it.size / 2f } ?: fallbackCenter,
        viewportStartPx = viewportStart,
        viewportEndPx = viewportEnd
    )
}

/**
 * Modern circular progress countdown during song instrumental intros.
 *
 * For MetroLyrics, displays the thicker 40dp / 5.0dp MetroLyrics style.
 * For all other animation modes (Auralis Default, Apple Music, Fade, Glow, etc.),
 * displays the classic thin 34dp / 3.0dp style.
 */
@Composable
fun InstrumentalIntroIndicator(
    currentTimeMsState: State<Long>,
    introDurationMs: Long,
    modifier: Modifier = Modifier,
    isMetroLyrics: Boolean = false,
    indicatorSize: Dp = if (isMetroLyrics) com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.MetroIndicatorSize else com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.StandardIndicatorSize,
    strokeWidth: Dp = if (isMetroLyrics) com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.MetroStrokeWidth else com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.StandardStrokeWidth,
    gapSize: Dp = if (isMetroLyrics) com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.MetroTrackGapSize else com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.StandardTrackGapSize,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    dotSize: Dp = if (isMetroLyrics) 18.dp else 16.dp,
    onSkipIntro: (() -> Unit)? = null,
    compact: Boolean = false
) {
    val currentTimeMs = currentTimeMsState.value

    val clickableSize = if (isMetroLyrics || indicatorSize.value >= 40f) 44.dp else 40.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (compact) 0.dp else 4.dp, bottom = if (compact) 1.dp else 12.dp),
        horizontalAlignment = horizontalAlignment
    ) {
        // Spicy Lyrics' interlude dots in place of the ring; tap to skip the intro.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .then(if (onSkipIntro != null) Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClick = onSkipIntro) else Modifier)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            LyricsDots(
                progress = (currentTimeMs.toFloat() / introDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
                elapsedMs = currentTimeMs,
                remainingMs = introDurationMs - currentTimeMs,
                dotSize = dotSize,
                compact = compact
            )
        }
    }
}
