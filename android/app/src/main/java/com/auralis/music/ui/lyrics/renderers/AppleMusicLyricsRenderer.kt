/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * Adapted for Auralis under GNU General Public License v3.0
 */

package com.auralis.music.ui.lyrics.renderers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
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
import com.auralis.music.ui.screens.lyrics.LyricsEngine

/**
 * Renders:
 * 1) [Apple Music]: Word-by-word with cubic easing, dynamic typography weights
 *    (Normal -> ExtraBold -> Bold), smooth alpha (0.4f..1.0f), and natural shadow.
 * 2) [Apple Music V2 / Letter by Letter]: Character-by-character layout with
 *    interpolated glyph timings, negative letter spacing (-0.5.sp), and per-character alpha.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppleMusicLyricsLine(
    isV2: Boolean,
    line: LyricLine,
    nextLineTime: Long?,
    words: List<LyricWord>?,
    isActive: Boolean,
    effectivePlaybackPosition: Long,
    lineColor: Color,
    accentColor: Color,
    textAlign: TextAlign,
    alignment: Alignment.Horizontal,
    fontSizeSp: Float,
    lineSpacingMultiplier: Float,
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
                color = if (line.isBackground) lineColor.copy(alpha = lineColor.alpha * 0.75f) else lineColor,
                textAlign = textAlign,
                fontWeight = if (line.isBackground) FontWeight.Medium else FontWeight.SemiBold,
                fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                letterSpacing = (-0.4).sp,
                lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp
            )
            return@Column
        }

        if (isV2) {
            // ── Apple Music V2 (Letter by Letter) ──
            val duration = remember(line.time, nextLineTime) {
                if (nextLineTime != null) nextLineTime - line.time else 4000L
            }
            val activeDuration = remember(duration) {
                (duration * 0.95).toLong().coerceAtLeast(300L)
            }

            val wordData = remember(line.text, words, activeDuration) {
                if (hasWordTimings) {
                    words!!.mapIndexed { _, word ->
                        val wordStart = (word.time - line.time).coerceAtLeast(0L)
                        val wordEnd = if (word.duration != null && word.duration > 0L) {
                            (word.time + word.duration - line.time).coerceAtLeast(wordStart + 50L)
                        } else {
                            (wordStart + 300L).coerceAtLeast(wordStart + 50L)
                        }
                        Triple(word.word, wordStart, wordEnd)
                    }
                } else {
                    val splitWords = line.text.split(" ").filter { it.isNotEmpty() }
                    if (splitWords.isEmpty()) {
                        listOf(Triple(line.text, 0L, activeDuration))
                    } else {
                        val totalChars = line.text.length
                        var accumulatedTime = 0L
                        splitWords.mapIndexed { wordIndex, word ->
                            val wordLength = word.length
                            val includeSpace = wordIndex < splitWords.lastIndex
                            val charCount = if (includeSpace) wordLength + 1 else wordLength
                            val wordStart = accumulatedTime
                            val wordDur = if (totalChars > 0) (activeDuration * charCount.toFloat() / totalChars).toLong() else activeDuration
                            val wordEnd = wordStart + wordDur
                            accumulatedTime += wordDur
                            Triple(word, wordStart, wordEnd)
                        }
                    }
                }
            }

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
                wordData.forEachIndexed { wordIndex, (wordText, startRelative, endRelative) ->
                    val lineRelTime = (effectivePlaybackPosition - line.time).coerceAtLeast(0L)
                    val wordDuration = endRelative - startRelative

                    Row {
                        wordText.forEachIndexed { charIndex, char ->
                            val charDuration = if (wordText.isNotEmpty()) wordDuration / wordText.length else 0L
                            val charStart = startRelative + (charIndex * charDuration)
                            val charEnd = charStart + charDuration

                            val charProgress = when {
                                lineRelTime >= charEnd -> 1f
                                lineRelTime < charStart -> 0f
                                else -> {
                                    if (charDuration <= 0L) 1f
                                    else (lineRelTime - charStart).toFloat() / charDuration
                                }
                            }

                            Text(
                                text = char.toString(),
                                fontSize = fontSizeSp.sp,
                                color = accentColor.copy(alpha = if (charProgress >= 1f) 1f else 0.35f + (0.65f * charProgress)),
                                fontWeight = FontWeight.ExtraBold,
                                fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                                letterSpacing = (-0.5).sp
                            )
                        }
                        val shouldAddSpace = if (wordRanges.size == wordData.size && wordIndex < wordRanges.size - 1) {
                            val currEnd = wordRanges[wordIndex].endIndex
                            val nextStart = wordRanges[wordIndex + 1].startIndex
                            nextStart > currEnd
                        } else {
                            !wordText.endsWith(" ")
                        }
                        if (wordIndex < wordData.size - 1 && shouldAddSpace) {
                            Text(
                                text = " ",
                                fontSize = fontSizeSp.sp,
                                letterSpacing = (-0.5).sp
                            )
                        }
                    }
                }
            }
        } else {
            // ── Apple Music V1 (Standard Word-by-Word) ──
            if (hasWordTimings) {
                val styledText = buildAnnotatedString {
                    words!!.forEachIndexed { wordIndex, word ->
                        val wordStartMs = word.time
                        val wordDuration = (word.duration ?: 0L).coerceAtLeast(0L)
                        val wordEndMs = wordStartMs + wordDuration

                        val isWordActive = effectivePlaybackPosition in wordStartMs until wordEndMs
                        val hasWordPassed = effectivePlaybackPosition >= wordEndMs

                        val rawProgress = if (isWordActive && wordDuration > 0) {
                            val elapsed = effectivePlaybackPosition - wordStartMs
                            (elapsed.toFloat() / wordDuration).coerceIn(0f, 1f)
                        } else if (hasWordPassed) 1f else 0f

                        val smoothProgress = rawProgress * rawProgress * (3f - 2f * rawProgress)

                        val wordAlpha = when {
                            hasWordPassed -> if (line.isBackground) 0.85f else 1f
                            isWordActive -> if (line.isBackground) 0.35f + (0.50f * smoothProgress) else 0.35f + (0.65f * smoothProgress)
                            else -> if (line.isBackground) 0.28f else 0.35f
                        }
                        val wordColor = accentColor.copy(alpha = wordAlpha)
                        val wordWeight = if (line.isBackground) FontWeight.Bold else FontWeight.ExtraBold
                        val glowIntensity = smoothProgress * smoothProgress
                        val wordShadow = when {
                            isWordActive -> Shadow(
                                color = accentColor.copy(alpha = 0.2f + (0.4f * glowIntensity)),
                                offset = Offset.Zero,
                                blurRadius = 10f + (12f * glowIntensity)
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
                    letterSpacing = (-0.4).sp,
                    lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp
                )
            } else {
                // Line-sync fallback for Apple Music V1
                Text(
                    text = line.text,
                    fontSize = fontSizeSp.sp,
                    color = if (line.isBackground) accentColor.copy(alpha = 0.85f) else accentColor,
                    textAlign = textAlign,
                    fontWeight = if (line.isBackground) FontWeight.Bold else FontWeight.ExtraBold,
                    fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                    letterSpacing = (-0.4).sp,
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

