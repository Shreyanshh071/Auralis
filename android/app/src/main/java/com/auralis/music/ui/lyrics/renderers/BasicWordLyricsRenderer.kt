/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * Adapted for Auralis under GNU General Public License v3.0
 */

package com.auralis.music.ui.lyrics.renderers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import kotlin.math.PI
import kotlin.math.sin

/**
 * Renderer for the word-based animation modes:
 * - [LyricsAnimationMode.FADE]: Smooth cubic-eased alpha fade (0.40f..1.0f) with subtle soft shadow.
 */
@Composable
fun BasicWordLyricsLine(
    mode: LyricsAnimationMode,
    line: LyricLine,
    words: List<LyricWord>?,
    isActive: Boolean,
    effectivePlaybackPosition: Long,
    lineColor: Color,
    accentColor: Color,
    textAlign: TextAlign,
    alignment: Alignment.Horizontal,
    fontSizeSp: Float,
    lineSpacingMultiplier: Float,
    enableGlowEffect: Boolean,
    modifier: Modifier = Modifier
) {
    val hasWordTimings = !words.isNullOrEmpty()
    val wordRanges = remember(line.text, words) {
        if (hasWordTimings) LyricsEngine.mapWordsToLineSpans(line.text, words) else emptyList()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isActive) {
            // Inactive line: clean un-highlighted presentation with SemiBold weight
            Text(
                text = line.text,
                fontSize = fontSizeSp.sp,
                color = lineColor,
                textAlign = textAlign,
                fontWeight = FontWeight.SemiBold,
                fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp
            )
        } else if (hasWordTimings) {
            val styledText = buildAnnotatedString {
                words!!.forEachIndexed { wordIndex, word ->
                    val wordStartMs = word.time
                    val wordDuration = (word.duration ?: 0L).coerceAtLeast(0L)
                    val wordEndMs = wordStartMs + wordDuration

                    val isWordActive = effectivePlaybackPosition in wordStartMs..wordEndMs
                    val hasWordPassed = effectivePlaybackPosition > wordEndMs

                    val fadeProgress = if (isWordActive && wordDuration > 0) {
                        val timeElapsed = effectivePlaybackPosition - wordStartMs
                        val linear = (timeElapsed.toFloat() / wordDuration.toFloat()).coerceIn(0f, 1f)
                        linear * linear * (3f - 2f * linear)
                    } else if (hasWordPassed) 1f else 0f

                    val wordAlpha = when {
                        hasWordPassed -> 1f
                        isWordActive -> 0.35f + (0.65f * fadeProgress)
                        else -> 0.35f
                    }
                    val wordColor = accentColor.copy(alpha = wordAlpha)
                    val wordWeight = FontWeight.ExtraBold
                    val wordShadow = when {
                        isWordActive && fadeProgress > 0.2f -> Shadow(
                            color = accentColor.copy(alpha = 0.40f * fadeProgress),
                            offset = Offset.Zero,
                            blurRadius = 10f * fadeProgress
                        )
                        hasWordPassed -> Shadow(
                            color = accentColor.copy(alpha = 0.15f),
                            offset = Offset.Zero,
                            blurRadius = 6f
                        )
                        else -> null
                    }

                    withStyle(style = SpanStyle(color = wordColor, fontWeight = wordWeight, shadow = wordShadow)) {
                        append(word.word)
                    }
                    appendWordSeparator(this, wordIndex, words!!, wordRanges, line.text)
                }
            }
            Text(
                text = styledText,
                fontSize = fontSizeSp.sp,
                textAlign = textAlign,
                fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp
            )
        } else {
            // ── Line-synced fallback (timing honesty preserved: no synthetic word timings) ──
            if (isActive && enableGlowEffect) {
                val fillProgress = remember { Animatable(0f) }
                val pulseProgress = remember { Animatable(0f) }

                LaunchedEffect(line.time) {
                    fillProgress.snapTo(0f)
                    fillProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
                    )
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        pulseProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 3000, easing = LinearEasing)
                        )
                        pulseProgress.snapTo(0f)
                    }
                }

                val fill = fillProgress.value
                val pulse = pulseProgress.value
                val pulseEffect = (sin(pulse * PI.toFloat()) * 0.15f).coerceIn(0f, 0.15f)
                val glowIntensity = (fill + pulseEffect).coerceIn(0f, 1.2f)

                val glowBrush = Brush.horizontalGradient(
                    0.0f to accentColor.copy(alpha = 0.3f),
                    (fill * 0.7f).coerceIn(0f, 1f) to accentColor.copy(alpha = 0.9f),
                    fill to accentColor,
                    (fill + 0.1f).coerceIn(0f, 1f) to accentColor.copy(alpha = 0.7f),
                    1.0f to accentColor.copy(alpha = if (fill >= 1f) 1f else 0.3f)
                )

                val styledText = buildAnnotatedString {
                    withStyle(
                        style = SpanStyle(
                            shadow = Shadow(
                                color = accentColor.copy(alpha = 0.8f * glowIntensity),
                                offset = Offset.Zero,
                                blurRadius = 28f * (1f + pulseEffect)
                            ),
                            brush = glowBrush
                        )
                    ) {
                        append(line.text)
                    }
                }

                val bounceScale = if (fill < 0.3f) {
                    1f + (sin(fill * 3.33f * PI.toFloat()) * 0.03f)
                } else 1f

                Text(
                    text = styledText,
                    fontSize = fontSizeSp.sp,
                    textAlign = textAlign,
                    fontWeight = FontWeight.ExtraBold,
                    fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                    lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = bounceScale
                        scaleY = bounceScale
                    }
                )
            } else {
                Text(
                    text = line.text,
                    fontSize = fontSizeSp.sp,
                    color = if (isActive) accentColor else lineColor,
                    textAlign = textAlign,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                    lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp
                )
            }
        }
    }
}

private fun appendWordSeparator(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    wordIndex: Int,
    words: List<LyricWord>,
    wordRanges: List<LyricsEngine.WordRange>,
    lineText: String
) {
    if (wordIndex >= words.size - 1) return
    if (wordRanges.size == words.size && wordIndex < wordRanges.size - 1) {
        val currEnd = wordRanges[wordIndex].endIndex
        val nextStart = wordRanges[wordIndex + 1].startIndex
        if (nextStart > currEnd && nextStart <= lineText.length) {
            builder.append(lineText.substring(currEnd, nextStart))
            return
        } else if (nextStart == currEnd) {
            // Contiguous syllables of same word (e.g. "toge" and "ther") — NO separator
            return
        }
    }
    // Fallback: only append space if word doesn't already end in whitespace
    if (!words[wordIndex].word.endsWith(" ")) {
        builder.append(" ")
    }
}


