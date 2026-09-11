/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * Adapted for Auralis under GNU General Public License v3.0
 */

package com.auralis.music.ui.lyrics.renderers

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import kotlinx.coroutines.isActive
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private data class MetroWordTimestamp(
    val text: String,
    val startTime: Double,
    val endTime: Double,
    val hasTrailingSpace: Boolean
)

private data class MetroHyphenGroup(
    val pos: Int,
    val groupSize: Int,
    val isLast: Boolean,
    val groupStartMs: Long,
    val groupEndMs: Long
)

private fun String.toGraphemeClusters(): List<String> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val it = BreakIterator.getCharacterInstance()
    it.setText(this)
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        result.add(substring(start, end))
        start = end
        end = it.next()
    }
    return result
}

/**
 * MetroLyrics Canvas-based Karaoke Renderer ported from VIVI's MetroLyrics.kt.
 * Features:
 * - Sub-frame 60/120fps position interpolation with withFrameMillis.
 * - Grapheme cluster measurement via BreakIterator for complex scripts.
 * - Character-by-character native canvas drawing with BlurMaskFilter glow.
 * - Wobble scale physics and hyphen-group crescendo dynamics.
 */
@Composable
fun MetroLyricsLine(
    line: LyricLine,
    words: List<LyricWord>?,
    isActive: Boolean,
    distanceFromCurrent: Int,
    effectivePlaybackPosition: Long,
    lineColor: Color,
    accentColor: Color,
    textAlign: TextAlign,
    alignment: Alignment.Horizontal,
    fontSizeSp: Float,
    lineSpacingMultiplier: Float,
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier,
    nextLineTime: Long? = null
) {
    val mainText = if (line.isBackground) line.text.removePrefix("(").removeSuffix(")") else line.text
    val focusedAlpha = if (line.isBackground) 0.5f else 0.35f

    // Calculate line-level active/highlight state using genuine line active interval and next-line boundary:
    // - line not started (effectivePlaybackPosition < line.time) -> inactive/gray
    // - line currently playing -> highlighted
    // - words have finished but next line has not started -> STILL highlighted
    // - next line starts -> previous line becomes inactive, next line becomes highlighted
    val nextBoundary = nextLineTime ?: line.endTime ?: (line.effectiveEndTime ?: (line.time + 10_000L))
    val isInActiveInterval = if (nextBoundary > line.time) {
        effectivePlaybackPosition in line.time until nextBoundary
    } else {
        effectivePlaybackPosition >= line.time && effectivePlaybackPosition <= nextBoundary
    }
    val isLineActive = isActive || isInActiveInterval

    val targetAlpha = when {
        line.isBackground || isLineActive -> 1f
        distanceFromCurrent == 1 -> 0.45f
        distanceFromCurrent == 2 -> 0.25f
        else -> 0.15f
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 300),
        label = "metroAlpha"
    )

    val lyricStyle = TextStyle(
        fontSize = if (line.isBackground) (fontSizeSp * 0.75f).sp else fontSizeSp.sp,
        fontWeight = if (isLineActive) FontWeight.ExtraBold else FontWeight.SemiBold,
        fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
        lineHeight = (fontSizeSp * lineSpacingMultiplier.coerceAtMost(1.3f)).sp,
        letterSpacing = (-0.5).sp,
        textAlign = textAlign,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both
        )
    )

    val effectiveWords: List<MetroWordTimestamp> = remember(words, line.text, line.time) {
        if (!words.isNullOrEmpty()) {
            words.mapIndexed { idx, w ->
                val startSec = w.time / 1000.0
                val durSec = (w.duration ?: 200L) / 1000.0
                MetroWordTimestamp(
                    text = w.word,
                    startTime = startSec,
                    endTime = startSec + durSec,
                    hasTrailingSpace = idx < words.size - 1
                )
            }
        } else {
            emptyList()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (effectiveWords.isNotEmpty() && (isLineActive || distanceFromCurrent <= 2) && mainText.isNotEmpty()) {
            MetroWordLevelCanvas(
                lineStartTime = line.time,
                mainText = mainText,
                words = effectiveWords,
                isActiveLine = isLineActive,
                effectivePlaybackPosition = effectivePlaybackPosition,
                lyricStyle = lyricStyle,
                lineColor = if (isLineActive && !line.isBackground) accentColor else accentColor.copy(alpha = animatedAlpha),
                accentColor = accentColor,
                isBackground = line.isBackground,
                focusedAlpha = focusedAlpha,
                alignment = textAlign,
                isPlaying = isPlaying
            )
        } else {
            Text(
                text = mainText,
                style = lyricStyle.copy(color = if (isLineActive && !line.isBackground) accentColor else accentColor.copy(alpha = animatedAlpha)),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


@Composable
private fun MetroWordLevelCanvas(
    lineStartTime: Long,
    mainText: String,
    words: List<MetroWordTimestamp>,
    isActiveLine: Boolean,
    effectivePlaybackPosition: Long,
    lyricStyle: TextStyle,
    lineColor: Color,
    accentColor: Color,
    isBackground: Boolean,
    focusedAlpha: Float,
    alignment: TextAlign,
    isPlaying: Boolean = true
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val glowPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
        }
    }

    var smoothPosition by remember(lineStartTime) { mutableLongStateOf(effectivePlaybackPosition) }
    var lastAnchorPos by remember(lineStartTime) { mutableLongStateOf(effectivePlaybackPosition) }
    var lastAnchorTime by remember(lineStartTime) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(effectivePlaybackPosition) {
        lastAnchorPos = effectivePlaybackPosition
        lastAnchorTime = System.currentTimeMillis()
        smoothPosition = effectivePlaybackPosition
    }

    LaunchedEffect(isActiveLine, isPlaying) {
        if (isActiveLine && isPlaying) {
            lastAnchorPos = effectivePlaybackPosition
            lastAnchorTime = System.currentTimeMillis()
            smoothPosition = effectivePlaybackPosition

            while (isActive) {
                withFrameMillis {
                    val now = System.currentTimeMillis()
                    // Clamp sub-frame carry to 100ms so interpolation never runs away from audio
                    val elapsed = (now - lastAnchorTime).coerceIn(0L, 100L)
                    smoothPosition = lastAnchorPos + elapsed
                }
            }
        } else {
            // When paused or stopped, freeze smoothPosition strictly to the current audio position
            smoothPosition = effectivePlaybackPosition
        }
    }

    val graphemeClusters = remember(mainText) { mainText.toGraphemeClusters() }
    val clusterCount = graphemeClusters.size

    val clusterCharOffsets = remember(mainText, graphemeClusters) {
        IntArray(clusterCount).also { offsets ->
            var charOffset = 0
            graphemeClusters.forEachIndexed { i, cluster ->
                offsets[i] = charOffset
                charOffset += cluster.length
            }
        }
    }

    val charToWordData = remember(mainText, words, isBackground, graphemeClusters, clusterCharOffsets) {
        val wordIdxMap = IntArray(clusterCount) { -1 }
        val charInWordMap = IntArray(clusterCount)
        val wordLenMap = IntArray(clusterCount) { 1 }

        var currentPos = 0
        var clCursor = 0
        words.forEachIndexed { wordIdx, word ->
            val rawWordText = word.text
            val indexInMain = mainText.indexOf(rawWordText, currentPos)
            if (indexInMain != -1) {
                val wordEndInMain = indexInMain + rawWordText.length
                while (clCursor < clusterCount && clusterCharOffsets[clCursor] < indexInMain) {
                    clCursor++
                }
                val wordClusterIndices = mutableListOf<Int>()
                while (clCursor < clusterCount && clusterCharOffsets[clCursor] < wordEndInMain) {
                    wordClusterIndices.add(clCursor)
                    clCursor++
                }
                val wordClusterLen = wordClusterIndices.size
                wordClusterIndices.forEachIndexed { posInWord, clIdx ->
                    wordIdxMap[clIdx] = wordIdx
                    charInWordMap[clIdx] = posInWord
                    wordLenMap[clIdx] = wordClusterLen
                }
                currentPos = wordEndInMain
            }
        }
        Triple(wordIdxMap, charInWordMap, wordLenMap)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val layoutResult = remember(mainText, maxWidthPx, lyricStyle) {
            textMeasurer.measure(
                text = mainText,
                style = lyricStyle,
                constraints = Constraints(minWidth = maxWidthPx, maxWidth = maxWidthPx),
                softWrap = true
            )
        }

        val letterLayouts = remember(mainText, lyricStyle) {
            graphemeClusters.map { cluster -> textMeasurer.measure(cluster, lyricStyle) }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { layoutResult.size.height.toDp() })
                .graphicsLayer(
                    clip = false,
                    compositingStrategy = CompositingStrategy.Offscreen
                )
        ) {
            if (mainText.isEmpty()) return@Canvas
            if (!isActiveLine) {
                drawText(layoutResult, color = lineColor)
            } else {
                val (wordIdxMap, charInWordMap, wordLenMap) = charToWordData
                val areAllWordsSung = words.isNotEmpty() && words.all { smoothPosition > (it.endTime * 1000).toLong() }
                val wordFactors = words.map { word ->
                    val wStartMs = (word.startTime * 1000).toLong()
                    val wEndMs = (word.endTime * 1000).toLong()
                    val isWordSung = smoothPosition > wEndMs
                    val isWordActive = smoothPosition in wStartMs..wEndMs
                    val sungFactor = if (isWordSung) 1f
                    else if (isWordActive) ((smoothPosition - wStartMs).toFloat() / (wEndMs - wStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                    else 0f
                    Triple(sungFactor, word, isWordSung)
                }

                val wordWobbles = FloatArray(words.size)
                words.forEachIndexed { wordIdx, word ->
                    val startMs = (word.startTime * 1000).toLong()
                    val timeSinceStart = (smoothPosition - startMs).toFloat()
                    val wobble = if (timeSinceStart in 0f..750f) {
                        if (timeSinceStart < 125f) timeSinceStart / 125f
                        else (1f - (timeSinceStart - 125f) / 625f).coerceAtLeast(0f)
                    } else 0f
                    wordWobbles[wordIdx] = wobble
                }

                val lineCurrentPushes = FloatArray(layoutResult.lineCount)
                val lineTotalPushes = FloatArray(layoutResult.lineCount)

                for (i in 0 until clusterCount) {
                    val charOffset = clusterCharOffsets[i]
                    val lineIdx = layoutResult.getLineForOffset(charOffset)
                    val wordIdx = wordIdxMap[i]
                    val wobble = if (wordIdx != -1) wordWobbles[wordIdx] else 0f
                    val charScaleX = 1f + (wobble * 0.025f)
                    val charBounds = layoutResult.getBoundingBox(charOffset)
                    lineTotalPushes[lineIdx] += charBounds.width * (charScaleX - 1f)
                }

                for (i in 0 until clusterCount) {
                    val charOffset = clusterCharOffsets[i]
                    val lineIdx = layoutResult.getLineForOffset(charOffset)
                    val charBounds = layoutResult.getBoundingBox(charOffset)
                    val wordIdx = wordIdxMap[i]

                    val alignShift = when (alignment) {
                        TextAlign.Center -> -lineTotalPushes[lineIdx] / 2f
                        TextAlign.End, TextAlign.Right -> -lineTotalPushes[lineIdx]
                        else -> 0f
                    }

                    val (sungFactor, wordItem, isWordSung) = if (wordIdx != -1) wordFactors[wordIdx] else Triple(0f, null, false)
                    val wobble = if (wordIdx != -1) wordWobbles[wordIdx] else 0f
                    val wobbleX = wobble * 0.025f
                    val wobbleY = wobble * 0.015f

                    val charLp = if (wordItem != null) {
                        val sMs = wordItem.startTime * 1000
                        val dur = (wordItem.endTime * 1000 - wordItem.startTime * 1000).coerceAtLeast(100.0)
                        val wProg = (smoothPosition.toDouble() - sMs) / dur
                        val cInW = charInWordMap[i].toDouble()
                        val wLen = wordLenMap[i].toDouble()
                        ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
                    } else 0f

                    val shouldGlow = wordItem != null && !isWordSung && sungFactor > 0.001f
                    val charScaleX = 1f + wobbleX
                    val charScaleY = 1f + wobbleY

                    withTransform({
                        translate(left = alignShift + lineCurrentPushes[lineIdx] + charBounds.left, top = charBounds.top)
                        if (wordIdx != -1) {
                            scale(
                                charScaleX,
                                charScaleY,
                                pivot = Offset(charBounds.width / 2f, charBounds.height)
                            )
                        }
                    }) {
                        if (shouldGlow) {
                            val sMs = wordItem!!.startTime * 1000
                            val eMs = wordItem.endTime * 1000
                            val dur = eMs - sMs
                            val wordLenText = wordItem.text.length.coerceAtLeast(1)
                            val impactRatio = dur.toFloat() / wordLenText
                            val fadeFactor = (sungFactor * 5f).coerceIn(0f, 1f) * ((1f - sungFactor) * 8f).coerceIn(0f, 1f)
                            val impactFactor = (((impactRatio - 100f) / 250f).coerceIn(0f, 1f) * 0.6f + ((dur.toFloat() - 300f) / 1500f).coerceIn(0f, 1f) * 0.4f).coerceIn(0f, 1f) * fadeFactor
                            if (impactFactor > 0.01f) {
                                val glowAlpha = (0.35f * impactFactor).coerceIn(0f, 0.4f)
                                val baseGlowRadius = 12.dp.toPx() * impactFactor
                                drawIntoCanvas { canvas ->
                                    glowPaint.maskFilter = BlurMaskFilter(baseGlowRadius, BlurMaskFilter.Blur.NORMAL)
                                    glowPaint.color = accentColor.copy(alpha = glowAlpha).toArgb()
                                    glowPaint.textSize = lyricStyle.fontSize.toPx()
                                    glowPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                    canvas.nativeCanvas.drawText(letterLayouts[i].layoutInput.text.text, 0f, letterLayouts[i].firstBaseline, glowPaint)
                                }
                            }
                        }

                        val baseAlpha = if (isWordSung || areAllWordsSung || charLp > 0.99f) 1f else (focusedAlpha + (1f - focusedAlpha) * sungFactor)
                        val charAlpha = if (wordIdx == -1) (if (areAllWordsSung) 1f else focusedAlpha) else baseAlpha
                        drawText(letterLayouts[i], color = accentColor.copy(alpha = charAlpha))

                        if (!isWordSung && charLp > 0f && charLp < 1f) {
                            val fXL = charBounds.width * charLp
                            val eW = (charBounds.width * 0.45f).coerceAtLeast(1f)
                            val sWL = (fXL - eW).coerceAtLeast(0f)
                            if (sWL > 0f) {
                                clipRect(left = 0f, top = 0f, right = sWL, bottom = charBounds.height) {
                                    drawText(letterLayouts[i], color = accentColor)
                                }
                            }
                            for (j in 0 until 12) {
                                val start = sWL + (j * eW / 12f)
                                val end = (sWL + ((j + 1) * eW / 12f) + 0.5f).coerceAtMost(fXL)
                                if (end > start) {
                                    clipRect(left = start, top = 0f, right = end, bottom = charBounds.height) {
                                        drawText(letterLayouts[i], color = accentColor.copy(alpha = 1f - (j + 0.5f) / 12f))
                                    }
                                }
                            }
                        }
                    }
                    lineCurrentPushes[lineIdx] += charBounds.width * (charScaleX - 1f)
                }
            }
        }
    }
}
