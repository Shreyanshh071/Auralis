/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * Adapted for Auralis under GNU General Public License v3.0
 */

package com.auralis.music.ui.lyrics.renderers

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import kotlin.math.PI
import kotlin.math.sin

/**
 * Lyrics V2 (Fluid) Renderer ported from VIVI's LyricsV2.kt.
 * Features:
 * - Liquid sweep mask: Two-layer rendering with Offscreen CompositingStrategy and DstIn horizontalGradient.
 * - Bounce and Float animation: subtle scale and floating Y-offset during singing (sin(progress * PI)).
 * - Active glow shadow dynamically tracking singing intensity.
 * - Honest fallback for line-synced lyrics without fabricating word timestamps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricsV2FluidLine(
    line: LyricLine,
    words: List<LyricWord>?,
    isActive: Boolean,
    isPast: Boolean,
    effectivePlaybackPosition: Long,
    accentColor: Color,
    inactiveAlpha: Float = 0.35f,
    fontSizeSp: Float,
    lineSpacingMultiplier: Float,
    textAlign: TextAlign,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier
) {
    val arrangement = when (textAlign) {
        TextAlign.Center -> Arrangement.Center
        TextAlign.End, TextAlign.Right -> Arrangement.End
        else -> Arrangement.Start
    }

    val lineHeightSp = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f))
    val hasWordTimings = !words.isNullOrEmpty()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isActive && !isPast) {
            // Inactive upcoming line: clean un-highlighted presentation with SemiBold weight
            Text(
                text = line.text,
                fontSize = fontSizeSp.sp,
                fontWeight = if (line.isBackground) FontWeight.Medium else FontWeight.SemiBold,
                fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                lineHeight = lineHeightSp.sp,
                color = accentColor.copy(alpha = if (line.isBackground) inactiveAlpha * 0.75f else inactiveAlpha),
                textAlign = textAlign,
                letterSpacing = (-0.4).sp,
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }

        if (hasWordTimings) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = arrangement,
                verticalArrangement = Arrangement.spacedBy(
                    with(LocalDensity.current) {
                        (fontSizeSp * (lineSpacingMultiplier.coerceAtMost(1.3f) - 1f)).sp.toDp()
                    }
                )
            ) {
                words!!.forEachIndexed { wordIndex, word ->
                    AnimatedWordV2(
                        word = word,
                        isLineActive = isActive,
                        isLinePast = isPast,
                        effectivePlaybackPosition = effectivePlaybackPosition,
                        accentColor = accentColor,
                        inactiveAlpha = inactiveAlpha,
                        fontSize = fontSizeSp,
                        lineHeight = lineHeightSp,
                        isBackground = line.isBackground
                    )
                    if (wordIndex < words.size - 1) {
                        Text(
                            text = " ",
                            fontSize = fontSizeSp.sp,
                            lineHeight = lineHeightSp.sp
                        )
                    }
                }
            }
        } else {
            // Honest fallback for lines without word timings
            Text(
                text = line.text,
                fontSize = fontSizeSp.sp,
                fontWeight = if (line.isBackground) FontWeight.Bold else (if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold),
                fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                lineHeight = lineHeightSp.sp,
                color = accentColor.copy(alpha = if (isActive) (if (line.isBackground) 0.85f else 1f) else (if (line.isBackground) inactiveAlpha * 0.75f else inactiveAlpha)),
                textAlign = textAlign,
                letterSpacing = (-0.4).sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


@Composable
private fun AnimatedWordV2(
    word: LyricWord,
    isLineActive: Boolean,
    isLinePast: Boolean,
    effectivePlaybackPosition: Long,
    accentColor: Color,
    inactiveAlpha: Float,
    fontSize: Float,
    lineHeight: Float,
    isBackground: Boolean
) {
    val wordStartMs = word.time
    val wordDuration = (word.duration ?: 200L).coerceAtLeast(1L)
    val wordEndMs = wordStartMs + wordDuration

    val isWordComplete = isLinePast || effectivePlaybackPosition >= wordEndMs
    val isWordActive = isLineActive && effectivePlaybackPosition in wordStartMs until wordEndMs

    val progress = when {
        isWordComplete -> 1f
        !isLineActive || effectivePlaybackPosition <= wordStartMs -> 0f
        else -> ((effectivePlaybackPosition - wordStartMs).toFloat() / wordDuration).coerceIn(0f, 1f)
    }

    // Bounce and Float animation
    val sinProgress = sin(progress * PI).toFloat()
    val wordScale = 1f + (0.015f * sinProgress)

    val targetFloat = if (isWordActive) -4f * sinProgress else 0f
    val floatOffset by animateFloatAsState(
        targetValue = targetFloat,
        animationSpec = tween(
            durationMillis = if (isWordActive) 50 else 350,
            easing = FastOutSlowInEasing
        ),
        label = "v2Float"
    )

    // Glow intensity
    val glowProgress = (progress * 2f).coerceAtMost(1f)
    val glowAlpha = if (isWordActive) glowProgress * 0.45f else 0f
    val glowRadius = if (isWordActive) glowProgress * 12f else 0f

    val density = LocalDensity.current
    val fontWeight = if (isLineActive) FontWeight.ExtraBold else FontWeight.SemiBold

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationY = floatOffset * density.density
                scaleX = wordScale
                scaleY = wordScale
            }
    ) {
        // Layer 1: Base text (always dimmed)
        Text(
            text = word.word,
            fontSize = fontSize.sp,
            fontWeight = fontWeight,
            fontStyle = if (isBackground) FontStyle.Italic else FontStyle.Normal,
            lineHeight = lineHeight.sp,
            color = accentColor.copy(alpha = if (isBackground) inactiveAlpha * 0.7f else inactiveAlpha)
        )

        // Layer 2: Filled overlay with liquid sweep mask + glow
        if (isWordComplete || isWordActive) {
            Text(
                text = word.word,
                fontSize = fontSize.sp,
                fontWeight = fontWeight,
                fontStyle = if (isBackground) FontStyle.Italic else FontStyle.Normal,
                lineHeight = lineHeight.sp,
                style = TextStyle(
                    shadow = if (glowAlpha > 0f) {
                        Shadow(
                            color = accentColor.copy(alpha = glowAlpha),
                            offset = Offset.Zero,
                            blurRadius = glowRadius.coerceAtLeast(1f)
                        )
                    } else null
                ),
                color = accentColor.copy(alpha = if (isBackground) 0.75f else 1f),
                modifier = if (isWordActive) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val edgeWidth = 8.dp.toPx()
                            val center = (size.width + edgeWidth * 2) * progress - edgeWidth
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startX = center - edgeWidth,
                                    endX = center + edgeWidth
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                } else Modifier
            )
        }
    }
}
