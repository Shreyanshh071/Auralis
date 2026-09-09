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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay

/**
 * Flagship VIVI Ocean Wave Fluid Lyrics Renderer — Vivimusic (Fluid).
 * Features:
 * - Continuous global wave progress (0->1) sweeping from first word to last.
 * - Trailing-feather gradient brush for soft oceanic edge.
 * - Sentence linger: holds active state for 180ms after line change for seamless handoff.
 * - Progressive distance blur (0, 0, 2dp, 4dp, 6dp) when standardLyricsBlur is active.
 * - Smooth spring-like line scale (1.05f on active).
 * - Space glyphs animated in sync with preceding word wave front.
 * - Graceful fallback: whole sentence sweeps if no genuine word timing exists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViviMusicLyricsLine(
    line: LyricLine,
    nextLineTime: Long?,
    words: List<LyricWord>?,
    isActive: Boolean,
    distanceFromCurrent: Int,
    effectivePlaybackPosition: Long,
    textColor: Color,
    accentColor: Color,
    textAlign: TextAlign,
    alignment: Alignment.Horizontal,
    fontSizeSp: Float,
    lineSpacingMultiplier: Float,
    enableStandardBlur: Boolean,
    isAutoScrollActive: Boolean,
    modifier: Modifier = Modifier
) {
    // ── Sentence linger ──
    val lingeredIsActive = remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (isActive) {
            lingeredIsActive.value = true
        } else {
            delay(180L)
            lingeredIsActive.value = false
        }
    }

    // ── Distance Blur ──
    val targetBlur = if (!enableStandardBlur || !isAutoScrollActive || isActive) {
        0f
    } else {
        when (distanceFromCurrent) {
            1, 2 -> 0f
            3 -> 2f
            4 -> 4f
            else -> 6f
        }
    }

    val animatedBlur by animateFloatAsState(
        targetValue = targetBlur,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "viviBlur"
    )

    // ── Line duration for sentence-level timing ──
    val duration = remember(line.time, nextLineTime) {
        if (nextLineTime != null) nextLineTime - line.time else 4000L
    }
    val activeDuration = remember(duration) {
        (duration * 0.95).toLong().coerceAtLeast(300L)
    }

    // ── Word data ──
    val hasWordTimestamps = !words.isNullOrEmpty()
    val wordData = remember(line.text, words, activeDuration) {
        if (hasWordTimestamps) {
            words!!.mapIndexed { _, word ->
                val wordStart = (word.time - line.time).coerceAtLeast(0L)
                val wordEnd = if (word.duration != null && word.duration > 0L) {
                    (word.time + word.duration - line.time).coerceAtLeast(wordStart + 50L)
                } else {
                    (wordStart + 300L).coerceAtLeast(wordStart + 50L)
                }
                Triple(word.word, wordStart, wordEnd)
            }
        } else null
    }

    // ── Alpha falloff ──
    val targetAlpha = when {
        isActive || lingeredIsActive.value -> 1f
        distanceFromCurrent == 1 -> 0.75f
        distanceFromCurrent == 2 -> 0.50f
        distanceFromCurrent == 3 -> 0.30f
        else -> 0.20f
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "viviAlpha"
    )

    // ── Scale ──
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "viviScale"
    )

    val itemModifier = modifier
        .fillMaxWidth()
        .graphicsLayer {
            this.alpha = animatedAlpha
            this.scaleX = scale
            this.scaleY = scale
        }
        .padding(vertical = (4 * lineSpacingMultiplier).dp)
        .then(if (animatedBlur > 0.1f) Modifier.blur(animatedBlur.dp) else Modifier)

    Column(
        modifier = itemModifier,
        horizontalAlignment = alignment
    ) {
        if (!isActive && !lingeredIsActive.value) {
            // Inactive line: clean un-highlighted presentation with SemiBold weight
            Text(
                text = line.text,
                fontSize = fontSizeSp.sp,
                color = accentColor.copy(alpha = 0.35f),
                fontWeight = FontWeight.SemiBold,
                fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                textAlign = textAlign,
                lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp
            )
            return@Column
        }

        if (wordData != null) {
            // ── WORD-BY-WORD mode: Ocean Wave ──
            val globalEnd = remember(wordData) {
                wordData.last().third.coerceAtLeast(1L)
            }
            val lineRelTime = (effectivePlaybackPosition - line.time).coerceAtLeast(0L)
            val rawGlobalWave = (lineRelTime.toFloat() / globalEnd.toFloat()).coerceIn(0f, 1f)

            val globalWave by animateFloatAsState(
                targetValue = rawGlobalWave,
                animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
                label = "viviGlobalWaveProgress"
            )

            val waveFeather = 0.12f

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = when (textAlign) {
                    TextAlign.Center -> Arrangement.Center
                    TextAlign.End, TextAlign.Right -> Arrangement.End
                    else -> Arrangement.Start
                },
                verticalArrangement = Arrangement.spacedBy(
                    with(LocalDensity.current) {
                        (fontSizeSp * (lineSpacingMultiplier.coerceAtMost(1.3f) - 1f)).sp.toDp()
                    }
                )
            ) {
                wordData.forEachIndexed { index, (wordText, startRelative, endRelative) ->
                    val wordStartFrac = startRelative.toFloat() / globalEnd
                    val wordEndFrac = endRelative.toFloat() / globalEnd
                    val wordSpan = (wordEndFrac - wordStartFrac).coerceAtLeast(0.001f)

                    val wordLocalProgress = ((globalWave - wordStartFrac) / wordSpan).coerceIn(0f, 1f)

                    val glowAlpha = 0.6f * wordLocalProgress
                    val glowRadius = (12f * wordLocalProgress).coerceAtLeast(0.1f)

                    val finalFontWeight = FontWeight.ExtraBold

                    val waveFront = wordLocalProgress
                    val waveTail = (wordLocalProgress + waveFeather).coerceAtMost(1f)

                    val wordBrush = when {
                        wordLocalProgress <= 0f -> Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.35f),
                                accentColor.copy(alpha = 0.35f)
                            )
                        )
                        wordLocalProgress >= 1f -> Brush.horizontalGradient(
                            colors = listOf(accentColor, accentColor)
                        )
                        else -> Brush.horizontalGradient(
                            0f to accentColor,
                            waveFront to accentColor,
                            waveTail to accentColor.copy(alpha = 0.35f),
                            1f to accentColor.copy(alpha = 0.35f)
                        )
                    }

                    Text(
                        text = wordText,
                        fontSize = fontSizeSp.sp,
                        style = TextStyle(
                            brush = wordBrush,
                            fontWeight = finalFontWeight,
                            fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                            lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp,
                            textAlign = textAlign,
                            shadow = Shadow(
                                color = accentColor.copy(alpha = glowAlpha),
                                offset = Offset.Zero,
                                blurRadius = glowRadius
                            )
                        )
                    )

                    if (index < wordData.size - 1) {
                        val spaceAlpha = if (wordLocalProgress >= 1f) 1f else 0.35f
                        Text(
                            text = " ",
                            fontSize = fontSizeSp.sp,
                            style = TextStyle(
                                color = accentColor.copy(alpha = spaceAlpha),
                                lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp,
                                shadow = if (wordLocalProgress >= 1f) {
                                    Shadow(
                                        color = accentColor.copy(alpha = 0.3f),
                                        offset = Offset.Zero,
                                        blurRadius = 6f
                                    )
                                } else null
                            )
                        )
                    }
                }
            }
        } else {
            // ── SENTENCE-LEVEL fallback: honest highlight without fabricating word timing ──
            val targetSentenceAlpha = if (isActive || lingeredIsActive.value) 1f else 0.35f
            val sentenceAlpha by animateFloatAsState(
                targetValue = targetSentenceAlpha,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "viviSentenceAlpha"
            )

            Text(
                text = line.text,
                fontSize = fontSizeSp.sp,
                color = accentColor.copy(alpha = sentenceAlpha),
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                textAlign = textAlign,
                lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp
            )
        }
    }
}

