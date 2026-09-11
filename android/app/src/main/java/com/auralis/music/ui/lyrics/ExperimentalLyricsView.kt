package com.auralis.music.ui.lyrics

import android.annotation.SuppressLint
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import com.auralis.music.domain.model.Track
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

private const val LYRICS_ANCHOR_RATIO = 0.35f
private val LYRICS_ITEM_FALLBACK_HEIGHT_DP = 68.dp
private val LYRICS_ITEM_GAP_DP = 16.dp
private val LYRICS_FADE_TOP_DP = 130.dp
private val LYRICS_FADE_BOTTOM_DP = 160.dp
private const val LYRICS_STAGGER_DELAY_PER_DISTANCE = 20
private const val LYRICS_STAGGER_DELAY_MAX_MS = 200
private const val LYRICS_PREVIEW_TIME = 8000L

/**
 * Top and bottom fading edge modifier to give the Metrolist floating depth aesthetic.
 */
fun Modifier.fadingEdge(
    top: Dp = LYRICS_FADE_TOP_DP,
    bottom: Dp = LYRICS_FADE_BOTTOM_DP
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val topPx = top.toPx()
        val bottomPx = bottom.toPx()
        if (topPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black,
                    startY = 0f,
                    endY = topPx
                ),
                blendMode = BlendMode.DstIn
            )
        }
        if (bottomPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = size.height - bottomPx,
                    endY = size.height
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }

/**
 * Timestamp container for genuine word-level karaoke timing.
 */
internal data class ExperimentalWordTimestamp(
    val text: String,
    val startTime: Double,
    val endTime: Double,
    val hasTrailingSpace: Boolean = true
)

private data class HyphenGroupWord(
    val pos: Int,
    val size: Int,
    val isLast: Boolean,
    val groupStartMs: Long,
    val groupEndMs: Long
)

private fun String.containsRtl(): Boolean {
    for (c in this) {
        val directionality = Character.getDirectionality(c).toInt()
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt() ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt()
        ) {
            return true
        }
    }
    return false
}

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

sealed class ExperimentalLyricsListItem {
    data class Line(val index: Int, val line: LyricLine) : ExperimentalLyricsListItem()
    data class Indicator(
        val afterLineIndex: Int,
        val gapStartMs: Long,
        val gapEndMs: Long
    ) : ExperimentalLyricsListItem()
}

/**
 * Identifies active line indices supporting simultaneous singers and overlapping lines.
 */
internal fun findExperimentalActiveLineIndices(
    lines: List<LyricLine>,
    position: Long
): Set<Int> {
    val active = mutableSetOf<Int>()
    val hasWordTimings = lines.any { it.hasWordTiming }

    for (index in lines.indices) {
        val line = lines[index]
        if (line.time > position) break

        val nextStart: Long? = if (line.isBackground) {
            (index + 1 until lines.size).firstOrNull { lines[it].time > line.time }?.let { lines[it].time }
        } else {
            (index + 1 until lines.size).firstOrNull { lines[it].time > line.time && !lines[it].isBackground }?.let { lines[it].time }
                ?: (index + 1 until lines.size).firstOrNull { lines[it].time > line.time }?.let { lines[it].time }
        }

        val effEnd = line.effectiveEndTime
        val expEnd = line.endTime
        val lineEndMs: Long = when {
            effEnd != null && effEnd > line.time -> effEnd
            expEnd != null && expEnd > line.time -> expEnd
            nextStart != null -> nextStart
            else -> line.time + 12_000L
        }

        if (position in line.time until lineEndMs) {
            active.add(index)
        }
    }

    if (!hasWordTimings && active.size > 1) {
        val mainActive = active.filter { !lines[it].isBackground }
        if (mainActive.size > 1) {
            val maxTime = mainActive.maxOf { lines[it].time }
            active.removeAll { it in mainActive && lines[it].time < maxTime }
        }
    }

    return active
}

/**
 * Genuine Metrolist-style Experimental Lyrics presentation.
 *
 * Distinct from standard Auralis MetroLyrics:
 * - Employs a custom floating Box layout with independent staggered coordinate animations.
 * - Supports multiple simultaneous active lines.
 * - Respects vocal agent positioning: v1 -> Left, v2 -> Right, v1000 -> Center.
 * - Positions background vocals centered, italicized, and scaled to 70% of base typography.
 * - Applies soft progressive dimming falloff away from active lines.
 * - Uses Metrolist kinetic word typography on genuine RichSync tracks.
 * - Preserves strict line illumination on LINE_SYNC without synthetic word pacing.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExperimentalLyricsView(
    lyrics: LyricsData?,
    positionState: State<Long>,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    lyricsClockSource: PlaybackClockSource? = null,
    offsetMs: Long = 0L,
    onOffsetChange: ((Long) -> Unit)? = null,
    onSearchManually: (() -> Unit)? = null,
    headerContent: (@Composable () -> Unit)? = null,
    footerContent: (@Composable () -> Unit)? = null,
    track: Track? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val appearance = LocalAppearanceSettings.current

    val rawLines = lyrics?.lines.orEmpty()
    val effectiveLines = remember(rawLines) {
        rawLines.filter { it.text.isNotBlank() || it.words?.any { w -> w.word.isNotBlank() } == true }
    }

    val playbackDurationMs = (track?.duration ?: 0L) * 1000L
    val rawFirstLineTime = effectiveLines.firstOrNull { !it.isInstrumental }?.time ?: 0L
    val introDurationMs = remember(effectiveLines, playbackDurationMs) {
        val isValidIntro = rawFirstLineTime >= 1500L &&
            (playbackDurationMs <= 0L || rawFirstLineTime < playbackDurationMs - 5000L)
        if (isValidIntro) rawFirstLineTime else 0L
    }

    val isSynced = (lyrics?.syncType != SyncType.PLAIN || effectiveLines.any { it.time > 0L }) && effectiveLines.isNotEmpty()
    val hasWordTimings = remember(effectiveLines) { effectiveLines.any { it.hasWordTiming } }

    var currentPositionState by remember { mutableLongStateOf(positionState.value + offsetMs) }

    val isIntroActiveState = remember(isSynced, introDurationMs) {
        derivedStateOf {
            isSynced && introDurationMs >= 1500L &&
                currentPositionState < introDurationMs
        }
    }
    val introTimeState = remember {
        derivedStateOf {
            (currentPositionState.coerceAtLeast(0L) / 50L) * 50L
        }
    }

    val mergedLyricsList = remember(effectiveLines) {
        val list = mutableListOf<ExperimentalLyricsListItem>()
        effectiveLines.forEachIndexed { index, line ->
            list.add(ExperimentalLyricsListItem.Line(index, line))
            if (index < effectiveLines.size - 1) {
                val nextStart = effectiveLines[index + 1].time
                val currentEnd = line.effectiveEndTime ?: (if (line.text.isBlank()) line.time else null)
                if (currentEnd != null && currentEnd < nextStart) {
                    val gap = nextStart - currentEnd
                    if (gap > 4000L) {
                        list.add(ExperimentalLyricsListItem.Indicator(index, currentEnd, nextStart))
                    }
                }
            }
        }
        list
    }

    // Interactive & selection state
    var activeLineIndices by remember { mutableStateOf(emptySet<Int>()) }
    var scrollTargetIndex by rememberSaveable { mutableIntStateOf(-1) }
    var previousScrollActiveIndices by remember { mutableStateOf(emptySet<Int>()) }

    var deferredCurrentLineIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastPreviewTime by rememberSaveable { mutableLongStateOf(0L) }
    var isAutoScrollEnabled by rememberSaveable { mutableStateOf(appearance.autoScrollLyrics) }

    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var shareLyricsText by remember { mutableStateOf("") }
    var showShareSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    var lastMainMaxSeen by remember(lyrics, effectiveLines) { mutableIntStateOf(-1) }

    // Continuous playback clock interpolator
    LaunchedEffect(lyrics, effectiveLines, isPlaying, lyricsClockSource) {
        if (effectiveLines.isEmpty()) {
            activeLineIndices = emptySet()
            return@LaunchedEffect
        }

        var lastBasePos = positionState.value
        var lastUpdateTime = System.currentTimeMillis()

        while (isActive) {
            withFrameNanos { _ -> }
            val now = System.currentTimeMillis()
            val basePos = if (lyricsClockSource != null) {
                lyricsClockSource.rawPositionMs()
            } else {
                positionState.value
            }

            if (basePos != lastBasePos) {
                lastBasePos = basePos
                lastUpdateTime = now
            }

            val elapsed = now - lastUpdateTime
            val currentPos = lastBasePos + (if (isPlaying) elapsed else 0L) + offsetMs
            currentPositionState = currentPos

            val initialActiveIndices = findExperimentalActiveLineIndices(effectiveLines, currentPos)
            val scrollActiveIndicesRaw = findExperimentalActiveLineIndices(effectiveLines, currentPos + (if (hasWordTimings) 0L else 250L))

            // Expand to include paired lead/background lines
            val scrollActiveIndices = scrollActiveIndicesRaw.toMutableSet()
            for (i in scrollActiveIndicesRaw) {
                if (effectiveLines.getOrNull(i)?.isBackground == true) {
                    for (j in i - 1 downTo 0) {
                        if (effectiveLines.getOrNull(j)?.isBackground == false) {
                            scrollActiveIndices.add(j)
                            break
                        }
                    }
                }
            }

            val newActiveIndices = initialActiveIndices.toMutableSet()
            for (i in initialActiveIndices) {
                if (effectiveLines.getOrNull(i)?.isBackground == true) {
                    for (j in i - 1 downTo 0) {
                        if (effectiveLines.getOrNull(j)?.isBackground == false) {
                            newActiveIndices.add(j)
                            break
                        }
                    }
                }
            }

            val scrollMax = scrollActiveIndices
                .filter { effectiveLines.getOrNull(it)?.isBackground == false }
                .maxOrNull() ?: (scrollActiveIndices.maxOrNull() ?: -1)

            val isCurrentTargetStillActive = scrollTargetIndex in scrollActiveIndices
            val anyStillActive = scrollActiveIndices.isNotEmpty()

            val shouldScroll = when {
                !isCurrentTargetStillActive && anyStillActive && scrollMax > scrollTargetIndex -> true
                !isCurrentTargetStillActive && !anyStillActive && previousScrollActiveIndices.isNotEmpty() -> true
                scrollTargetIndex == -1 && anyStillActive -> true
                previousScrollActiveIndices.isEmpty() && anyStillActive && scrollMax > scrollTargetIndex -> true
                else -> false
            }

            if (shouldScroll) {
                val targetToScroll = when {
                    !isCurrentTargetStillActive && anyStillActive -> scrollMax
                    !isCurrentTargetStillActive && !anyStillActive -> {
                        (lastMainMaxSeen + 1 until effectiveLines.size).firstOrNull {
                            effectiveLines.getOrNull(it)?.isBackground == false
                        } ?: scrollTargetIndex
                    }
                    else -> scrollMax
                }
                if (targetToScroll != -1 && targetToScroll > scrollTargetIndex) {
                    scrollTargetIndex = targetToScroll
                }
            }

            if (scrollMax > lastMainMaxSeen && scrollMax != -1) {
                lastMainMaxSeen = scrollMax
            }

            previousScrollActiveIndices = scrollActiveIndices
            activeLineIndices = newActiveIndices
        }
    }

    LaunchedEffect(scrollTargetIndex, isAutoScrollEnabled) {
        if (scrollTargetIndex != -1 && isAutoScrollEnabled) {
            deferredCurrentLineIndex = scrollTargetIndex
        }
    }

    // Auto-scroll resume after preview period
    LaunchedEffect(lastPreviewTime) {
        if (lastPreviewTime != 0L) {
            delay(LYRICS_PREVIEW_TIME)
            lastPreviewTime = 0L
        }
    }

    var userManualOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(lyrics, effectiveLines) {
        isAutoScrollEnabled = appearance.autoScrollLyrics
        userManualOffset = 0f
        scrollTargetIndex = -1
        deferredCurrentLineIndex = 0
        isSelectionModeActive = false
        selectedIndices.clear()
        previousScrollActiveIndices = emptySet()
    }

    var flingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val velocityTracker = remember { VelocityTracker() }
    val decayAnimSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }
    val itemHeights = remember(lyrics, mergedLyricsList) { mutableStateMapOf<Int, Int>() }
    var isInitialLayout by remember(lyrics, mergedLyricsList) { mutableStateOf(true) }

    val activeListIndex by remember(mergedLyricsList, deferredCurrentLineIndex) {
        derivedStateOf {
            mergedLyricsList.indexOfFirst {
                it is ExperimentalLyricsListItem.Line && it.index == deferredCurrentLineIndex
            }.coerceAtLeast(0)
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 12.dp)
    ) {
        val maxHeightPx = constraints.maxHeight.toFloat()
        val anchorY = maxHeightPx * LYRICS_ANCHOR_RATIO
        val lineHeightPx = with(density) { LYRICS_ITEM_FALLBACK_HEIGHT_DP.toPx() }
        val constraintLineHeightPx = with(density) { 120.dp.toPx() }

        val positions = remember(itemHeights.toMap(), activeListIndex, mergedLyricsList) {
            val map = mutableMapOf<Int, Float>()
            if (activeListIndex == -1 || mergedLyricsList.isEmpty()) return@remember map

            map[activeListIndex] = 0f
            var currentY = 0f
            val indicatorHeightPx = with(density) { 72.dp.toPx() }
            for (i in activeListIndex - 1 downTo 0) {
                val item = mergedLyricsList[i]
                val height = itemHeights[i]?.toFloat() ?: (if (item is ExperimentalLyricsListItem.Indicator) indicatorHeightPx else lineHeightPx)
                val noGap = (item as? ExperimentalLyricsListItem.Line)?.line?.isBackground == true || item is ExperimentalLyricsListItem.Indicator
                currentY -= (height + if (noGap) 0f else with(density) { LYRICS_ITEM_GAP_DP.toPx() })
                map[i] = currentY
            }
            currentY = 0f
            for (i in activeListIndex until mergedLyricsList.size - 1) {
                val currentItem = mergedLyricsList[i]
                val nextItem = mergedLyricsList[i + 1]
                val height = itemHeights[i]?.toFloat() ?: (if (currentItem is ExperimentalLyricsListItem.Indicator) indicatorHeightPx else lineHeightPx)
                val nextNoGap = (nextItem as? ExperimentalLyricsListItem.Line)?.line?.isBackground == true || nextItem is ExperimentalLyricsListItem.Indicator
                currentY += (height + if (nextNoGap) 0f else with(density) { LYRICS_ITEM_GAP_DP.toPx() })
                map[i + 1] = currentY
            }
            map
        }

        val minOffset = remember(itemHeights.toMap(), mergedLyricsList, activeListIndex, anchorY) {
            if (mergedLyricsList.isEmpty() || activeListIndex == -1) return@remember 0f
            val indicatorHeightPx = with(density) { 72.dp.toPx() }
            val totalBelow = (activeListIndex until mergedLyricsList.size - 1).sumOf { i ->
                val nextItem = mergedLyricsList[i + 1]
                val height = itemHeights[i]?.toFloat() ?: (if (mergedLyricsList[i] is ExperimentalLyricsListItem.Indicator) indicatorHeightPx else constraintLineHeightPx)
                val nextNoGap = (nextItem as? ExperimentalLyricsListItem.Line)?.line?.isBackground == true || nextItem is ExperimentalLyricsListItem.Indicator
                (height + if (nextNoGap) 0f else with(density) { LYRICS_ITEM_GAP_DP.toPx() }).toDouble()
            }.toFloat()
            val lastHeight = itemHeights[mergedLyricsList.size - 1]?.toFloat() ?: constraintLineHeightPx
            with(density) { 100.dp.toPx() } - anchorY - totalBelow - lastHeight
        }

        val maxOffset = remember(itemHeights.toMap(), mergedLyricsList, activeListIndex, maxHeightPx, anchorY) {
            if (mergedLyricsList.isEmpty() || activeListIndex == -1) return@remember 0f
            val indicatorHeightPx = with(density) { 72.dp.toPx() }
            val totalAbove = (0 until activeListIndex).sumOf { i ->
                val item = mergedLyricsList[i]
                val height = itemHeights[i]?.toFloat() ?: (if (item is ExperimentalLyricsListItem.Indicator) indicatorHeightPx else constraintLineHeightPx)
                val noGap = (item as? ExperimentalLyricsListItem.Line)?.line?.isBackground == true || item is ExperimentalLyricsListItem.Indicator
                (height + if (noGap) 0f else with(density) { LYRICS_ITEM_GAP_DP.toPx() }).toDouble()
            }.toFloat()
            maxHeightPx - with(density) { 150.dp.toPx() } - anchorY + totalAbove
        }

        val scrollClampMin = minOf(minOffset, maxOffset)
        val scrollClampMax = maxOf(minOffset, maxOffset)

        LaunchedEffect(scrollClampMin, scrollClampMax) {
            if (userManualOffset < scrollClampMin || userManualOffset > scrollClampMax) {
                userManualOffset = userManualOffset.coerceIn(scrollClampMin, scrollClampMax)
            }
        }

        LaunchedEffect(isAutoScrollEnabled, effectiveLines) {
            if (isAutoScrollEnabled) {
                val start = userManualOffset
                if (abs(start) < 1f) {
                    userManualOffset = 0f
                    return@LaunchedEffect
                }
                val anim = Animatable(start)
                var lastValue = start
                anim.animateTo(0f, tween((abs(start) / 4f).toInt().coerceIn(200, 600), easing = FastOutSlowInEasing)) {
                    userManualOffset += (value - lastValue)
                    lastValue = value
                }
                userManualOffset = 0f
            }
        }

        LaunchedEffect(mergedLyricsList.size) {
            if (mergedLyricsList.isNotEmpty()) {
                isInitialLayout = true
                try {
                    kotlinx.coroutines.withTimeout(600L) {
                        snapshotFlow {
                            val h = itemHeights.toMap()
                            val windowStart = (activeListIndex - 8).coerceAtLeast(0)
                            val windowEnd = (activeListIndex + 12).coerceAtMost(mergedLyricsList.size - 1)
                            (windowStart..windowEnd).all { h.containsKey(it) }
                        }.first { it }
                    }
                } catch (_: Exception) {
                } finally {
                    isInitialLayout = false
                }
            }
        }

        val resyncLyrics by rememberUpdatedState {
            flingJob?.cancel()
            var target = scrollTargetIndex
            if (target == -1) {
                target = findExperimentalActiveLineIndices(effectiveLines, currentPositionState).maxOrNull() ?: -1
            }
            if (target != -1) {
                val listIdx = mergedLyricsList.indexOfFirst {
                    it is ExperimentalLyricsListItem.Line && it.index == target
                }.coerceAtLeast(0)
                userManualOffset += positions[listIdx] ?: 0f
                deferredCurrentLineIndex = target
                scrollTargetIndex = target
            }
            isAutoScrollEnabled = true
        }

        if (effectiveLines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No lyrics available",
                    fontSize = 18.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdge(top = LYRICS_FADE_TOP_DP, bottom = LYRICS_FADE_BOTTOM_DP)
                    .clipToBounds()
                    .nestedScroll(remember {
                        object : NestedScrollConnection {
                            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                if (source == NestedScrollSource.UserInput) isAutoScrollEnabled = false
                                if (!isSelectionModeActive) lastPreviewTime = System.currentTimeMillis()
                                return super.onPostScroll(consumed, available, source)
                            }
                            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                isAutoScrollEnabled = false
                                if (!isSelectionModeActive) lastPreviewTime = System.currentTimeMillis()
                                return super.onPostFling(consumed, available)
                            }
                        }
                    })
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (isInitialLayout) continue
                                flingJob?.cancel()
                                velocityTracker.resetTracking()
                                isAutoScrollEnabled = false
                                lastPreviewTime = System.currentTimeMillis()
                                velocityTracker.addPosition(down.uptimeMillis, down.position)
                                verticalDrag(down.id) { change ->
                                    userManualOffset = (userManualOffset + change.positionChange().y).coerceIn(scrollClampMin, scrollClampMax)
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    change.consume()
                                }
                                val velocity = velocityTracker.calculateVelocity().y
                                flingJob = scope.launch {
                                    AnimationState(initialValue = userManualOffset, initialVelocity = velocity).animateDecay(decayAnimSpec) {
                                        val clamped = value.coerceIn(scrollClampMin, scrollClampMax)
                                        userManualOffset = clamped
                                        if (value != clamped) cancelAnimation()
                                    }
                                }
                            }
                        }
                    }
            ) {
                mergedLyricsList.forEachIndexed { listIndex, listItem ->
                    key(listItem) {
                        val distance = abs(listIndex - activeListIndex)
                        val targetOffset = anchorY + positions.getOrDefault(listIndex, (listIndex - activeListIndex) * lineHeightPx)
                        val frozenOffset = remember { mutableFloatStateOf(targetOffset) }
                        LaunchedEffect(isAutoScrollEnabled, targetOffset, isInitialLayout) {
                            if (isAutoScrollEnabled || isInitialLayout) frozenOffset.floatValue = targetOffset
                        }
                        val animatedOffset by animateFloatAsState(
                            targetValue = if (isAutoScrollEnabled) targetOffset else frozenOffset.floatValue,
                            animationSpec = if (isInitialLayout || !isAutoScrollEnabled) snap()
                            else {
                                tween(
                                    750,
                                    (distance * LYRICS_STAGGER_DELAY_PER_DISTANCE).coerceAtMost(LYRICS_STAGGER_DELAY_MAX_MS),
                                    FastOutSlowInEasing
                                )
                            },
                            label = "expLyricOffset_$listIndex"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .layout { m, c ->
                                    val p = m.measure(c.copy(maxHeight = Constraints.Infinity))
                                    layout(p.width, 0) { p.place(0, 0) }
                                }
                                .offset { IntOffset(0, (animatedOffset + userManualOffset).roundToInt()) }
                        ) {
                            when (listItem) {
                                is ExperimentalLyricsListItem.Line -> {
                                    val index = listItem.index
                                    val line = listItem.line
                                    val isActiveLine = activeLineIndices.contains(index)
                                    val pairedMainLineIndex = if (line.isBackground) {
                                        (index - 1 downTo 0).firstOrNull { effectiveLines.getOrNull(it)?.isBackground == false } ?: -1
                                    } else -1

                                    val isInGapWithMain = if (line.isBackground && pairedMainLineIndex != -1) {
                                        val paired = effectiveLines[pairedMainLineIndex]
                                        currentPositionState >= paired.time && currentPositionState <= line.time
                                    } else false

                                    val bgVisible = !isAutoScrollEnabled || (line.isBackground && (activeLineIndices.contains(pairedMainLineIndex) || activeLineIndices.contains(index) || isInGapWithMain))

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onSizeChanged { itemHeights[listIndex] = it.height },
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (index == 0 && isIntroActiveState.value) {
                                            InstrumentalIntroIndicator(
                                                currentTimeMsState = introTimeState,
                                                introDurationMs = introDurationMs,
                                                onSkipIntro = { onSeekTo(introDurationMs) },
                                                modifier = Modifier.padding(bottom = 16.dp)
                                            )
                                        }

                                        ExperimentalLyricsLine(
                                            index = index,
                                            line = line,
                                            isSynced = isSynced,
                                            isActiveLine = isActiveLine,
                                            bgVisible = bgVisible,
                                            isSelected = selectedIndices.contains(index),
                                            isSelectionModeActive = isSelectionModeActive,
                                            currentPositionState = currentPositionState,
                                            lyricsTextSize = 34f * (appearance.lyricsTextSize / 22f).coerceIn(0.8f, 1.4f),
                                            lyricsLineSpacing = appearance.lyricsLineSpacing.coerceIn(1.1f, 1.6f),
                                            expressiveAccent = Color.White,
                                            lyricsTextPosition = appearance.lyricsTextPosition,
                                            isAutoScrollEnabled = isAutoScrollEnabled,
                                            displayedCurrentLineIndex = deferredCurrentLineIndex,
                                            syncType = lyrics?.syncType ?: SyncType.PLAIN,
                                            isPlaying = isPlaying,
                                            onSizeChanged = { },
                                            onClick = {
                                                if (isSelectionModeActive) {
                                                    if (selectedIndices.contains(index)) {
                                                        selectedIndices.remove(index)
                                                        if (selectedIndices.isEmpty()) isSelectionModeActive = false
                                                    } else if (selectedIndices.size < 5) {
                                                        selectedIndices.add(index)
                                                    } else {
                                                        Toast.makeText(context, "Select up to 5 lines for showoff", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else if (appearance.changeLyricsOnTap) {
                                                    onSeekTo(line.time)
                                                    isAutoScrollEnabled = true
                                                    lastPreviewTime = 0L
                                                }
                                            },
                                            onLongClick = {
                                                if (!isSelectionModeActive) {
                                                    isSelectionModeActive = true
                                                    selectedIndices.add(index)
                                                }
                                            }
                                        )
                                    }
                                }
                                is ExperimentalLyricsListItem.Indicator -> {
                                    val indicatorVisible = isAutoScrollEnabled &&
                                        currentPositionState >= listItem.gapStartMs &&
                                        currentPositionState <= listItem.gapEndMs - 500L
                                    IntervalIndicator(
                                        gapStartMs = listItem.gapStartMs,
                                        gapEndMs = listItem.gapEndMs - 500L,
                                        currentPositionMs = currentPositionState,
                                        visible = indicatorVisible,
                                        color = Color.White,
                                        modifier = Modifier.onSizeChanged { itemHeights[listIndex] = it.height }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating sync button when auto-scroll is disabled by manual scrolling
        AnimatedVisibility(
            visible = !isAutoScrollEnabled && isSynced && !isSelectionModeActive,
            enter = fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF1E1B18).copy(alpha = 0.92f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(32.dp))
                    .clickable(onClick = resyncLyrics)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Re-sync",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Re-sync",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Floating Action Bar for Selected Lyrics
        AnimatedVisibility(
            visible = isSelectionModeActive,
            enter = fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF2C251F).copy(alpha = 0.95f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(32.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                IconButton(
                    onClick = {
                        isSelectionModeActive = false
                        selectedIndices.clear()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel Selection",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Button(
                    onClick = {
                        val selectedText = selectedIndices.sorted()
                            .take(5)
                            .mapNotNull { effectiveLines.getOrNull(it)?.text }
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                        if (selectedText.isNotBlank()) {
                            shareLyricsText = selectedText
                            showShareSheet = true
                        }
                        isSelectionModeActive = false
                        selectedIndices.clear()
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4A3E33),
                        contentColor = Color(0xFFE5C89C)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }

    if (showShareSheet && track != null) {
        com.auralis.music.ui.lyrics.share.LyricShareBottomSheet(
            track = track,
            initialLyricsText = shareLyricsText,
            onDismissRequest = {
                showShareSheet = false
                selectedIndices.clear()
            }
        )
    }
}

/**
 * Individual line presentation adhering to Metrolist vocal positioning & hierarchy.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExperimentalLyricsLine(
    index: Int,
    line: LyricLine,
    isSynced: Boolean,
    isActiveLine: Boolean,
    bgVisible: Boolean,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    currentPositionState: Long,
    lyricsTextSize: Float,
    lyricsLineSpacing: Float,
    expressiveAccent: Color,
    lyricsTextPosition: String,
    isAutoScrollEnabled: Boolean,
    displayedCurrentLineIndex: Int,
    syncType: SyncType,
    isPlaying: Boolean,
    onSizeChanged: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemModifier = modifier
        .fillMaxWidth()
        .onSizeChanged { onSizeChanged(it.height) }
        .clip(RoundedCornerShape(8.dp))
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
        .background(
            if (isSelected && isSelectionModeActive) Color.White.copy(alpha = 0.22f) else Color.Transparent
        )
        .padding(
            start = when {
                line.agent == "v1" -> 16.dp
                line.agent == "v2" -> 16.dp
                lyricsTextPosition.lowercase() in listOf("left", "right") -> 16.dp
                else -> 24.dp
            },
            end = when {
                line.agent == "v1" -> 16.dp
                line.agent == "v2" -> 16.dp
                lyricsTextPosition.lowercase() in listOf("left", "right") -> 16.dp
                else -> 24.dp
            },
            top = if (line.isBackground) 0.dp else 12.dp,
            bottom = if (line.isBackground) 2.dp else 12.dp
        )

    val agentAlignment = when {
        line.agent == "v1" -> Alignment.Start
        line.agent == "v2" -> Alignment.End
        line.agent == "v1000" -> Alignment.CenterHorizontally
        line.isBackground -> Alignment.CenterHorizontally
        else -> when (lyricsTextPosition.lowercase()) {
            "left" -> Alignment.Start
            "right" -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
    }

    val agentTextAlign = when {
        line.agent == "v1" -> TextAlign.Left
        line.agent == "v2" -> TextAlign.Right
        line.agent == "v1000" -> TextAlign.Center
        line.isBackground -> TextAlign.Center
        else -> when (lyricsTextPosition.lowercase()) {
            "left" -> TextAlign.Left
            "right" -> TextAlign.Right
            else -> TextAlign.Center
        }
    }

    Box(
        modifier = itemModifier,
        contentAlignment = when {
            line.agent == "v1" -> Alignment.CenterStart
            line.agent == "v2" -> Alignment.CenterEnd
            line.agent == "v1000" -> Alignment.Center
            line.isBackground -> Alignment.Center
            else -> when (lyricsTextPosition.lowercase()) {
                "left" -> Alignment.CenterStart
                "right" -> Alignment.CenterEnd
                else -> Alignment.Center
            }
        }
    ) {
        @Composable
        fun LineContent() {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = agentAlignment
            ) {
                val inactiveAlpha = if (line.isBackground) 0.08f else 0.20f
                val activeAlpha = 1.0f
                val focusedAlpha = if (line.isBackground) 0.50f else 0.30f
                val targetAlpha = if (!isSynced || line.isBackground || isActiveLine) {
                    activeAlpha
                } else if (isAutoScrollEnabled && displayedCurrentLineIndex >= 0) {
                    when (abs(index - displayedCurrentLineIndex)) {
                        0 -> focusedAlpha
                        1 -> 0.20f
                        2 -> 0.20f
                        3 -> 0.15f
                        4 -> 0.10f
                        else -> 0.08f
                    }
                } else inactiveAlpha

                val animatedAlpha by animateFloatAsState(targetAlpha, tween(250), label = "expLineAlpha")
                val lineColor = expressiveAccent.copy(alpha = if (line.isBackground) focusedAlpha else animatedAlpha)

                val resolvedLineText = remember(line.text, line.words) {
                    val raw = if (line.isBackground) line.text.removePrefix("(").removeSuffix(")") else line.text
                    val words = line.words
                    if (!words.isNullOrEmpty()) {
                        val wordConcat = buildString {
                            for (i in words.indices) {
                                val w = words[i]
                                append(w.word)
                                if (i < words.size - 1 && !w.word.endsWith(" ") && !w.word.endsWith("-")) {
                                    val nextW = words[i + 1]
                                    if (!nextW.word.startsWith(" ") &&
                                        !com.auralis.music.data.parser.WordTiming.shouldMergeSyllables(w.word, nextW.word) &&
                                        !com.auralis.music.data.parser.WordTiming.isCjk(w.word) &&
                                        !com.auralis.music.data.parser.WordTiming.isCjk(nextW.word)
                                    ) {
                                        append(" ")
                                    }
                                }
                            }
                        }.trim().let { if (line.isBackground) it.removePrefix("(").removeSuffix(")") else it }

                        if (wordConcat.isNotBlank() && (raw.isBlank() || (wordConcat.contains(" ") && !raw.contains(" ")) || wordConcat.count { it == ' ' } > raw.count { it == ' ' })) {
                            wordConcat
                        } else {
                            raw
                        }
                    } else {
                        raw
                    }
                }
                val mainText = resolvedLineText

                val lyricStyle = TextStyle(
                    fontSize = if (line.isBackground) (lyricsTextSize * 0.70f).sp else lyricsTextSize.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                    lineHeight = if (line.isBackground) (lyricsTextSize * 0.70f * lyricsLineSpacing).sp else (lyricsTextSize * lyricsLineSpacing).sp,
                    letterSpacing = (-0.5).sp,
                    textAlign = agentTextAlign,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )

                // Genuine RichSync word timestamps
                val effectiveWords = remember(line.words, syncType) {
                    if (syncType == SyncType.RICHSYNC && !line.words.isNullOrEmpty()) {
                        line.words.mapIndexed { idx, w ->
                            val sSec = w.time / 1000.0
                            val eSec = (w.endTime ?: (w.time + 300L)) / 1000.0
                            ExperimentalWordTimestamp(
                                text = w.word,
                                startTime = sSec,
                                endTime = eSec.coerceAtLeast(sSec + 0.05),
                                hasTrailingSpace = idx < line.words.size - 1
                            )
                        }
                    } else null
                }

                if (isSynced && effectiveWords != null && (isActiveLine || abs(index - displayedCurrentLineIndex) <= 3) && mainText.isNotBlank()) {
                    ExperimentalWordLevelLyrics(
                        mainText = mainText,
                        words = effectiveWords,
                        isActiveLine = isActiveLine,
                        currentPositionState = currentPositionState,
                        lyricStyle = lyricStyle,
                        lineColor = lineColor,
                        expressiveAccent = expressiveAccent,
                        isBackground = line.isBackground,
                        focusedAlpha = focusedAlpha,
                        alignment = agentTextAlign,
                        isPlaying = isPlaying
                    )
                } else {
                    Text(
                        text = mainText,
                        style = lyricStyle.copy(color = if (isActiveLine) expressiveAccent else lineColor),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                line.translatedText?.let { trans ->
                    Text(
                        text = trans,
                        fontSize = 16.sp,
                        color = expressiveAccent.copy(alpha = 0.5f),
                        textAlign = agentTextAlign,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (line.isBackground) {
            AnimatedVisibility(
                visible = bgVisible,
                enter = fadeIn(tween(durationMillis = 250, delayMillis = 100)),
                exit = fadeOut(tween(250))
            ) {
                LineContent()
            }
        } else {
            LineContent()
        }
    }
}

/**
 * Word-level kinetic typography canvas with character letter-progress, wobble, crescendo & glow.
 */
@Composable
private fun ExperimentalWordLevelLyrics(
    mainText: String,
    words: List<ExperimentalWordTimestamp>,
    isActiveLine: Boolean,
    currentPositionState: Long,
    lyricStyle: TextStyle,
    lineColor: Color,
    expressiveAccent: Color,
    isBackground: Boolean,
    focusedAlpha: Float,
    alignment: TextAlign,
    isPlaying: Boolean
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val glowPaint = remember {
        Paint().apply {
            isAntiAlias = true
        }
    }

    var smoothPosition by remember { mutableLongStateOf(currentPositionState) }

    LaunchedEffect(isActiveLine, isPlaying) {
        if (isActiveLine && isPlaying) {
            var lastPos = currentPositionState
            var lastUpdate = System.currentTimeMillis()
            while (isActive) {
                withFrameMillis {
                    val now = System.currentTimeMillis()
                    val pos = currentPositionState
                    if (pos != lastPos) {
                        lastPos = pos
                        lastUpdate = now
                    }
                    val elapsed = now - lastUpdate
                    smoothPosition = lastPos + elapsed
                }
            }
        } else {
            smoothPosition = currentPositionState
        }
    }

    LaunchedEffect(currentPositionState) {
        if (!isActiveLine) {
            smoothPosition = currentPositionState
        }
    }

    val (effectiveWords, effectiveToOriginalIdx) = remember(words, isBackground) {
        words.flatMapIndexed { originalIdx, word ->
            val shouldSplit = word.text.contains('-') && word.text.length > 1 &&
                (!word.hasTrailingSpace || words.size == 1)
            if (shouldSplit) {
                val segments = mutableListOf<String>()
                var start = 0
                for (i in word.text.indices) {
                    if (word.text[i] == '-') {
                        segments.add(word.text.substring(start, i + 1))
                        start = i + 1
                    }
                }
                if (start < word.text.length) {
                    segments.add(word.text.substring(start))
                }

                if (segments.size > 1) {
                    val totalDuration = word.endTime - word.startTime
                    val segmentDuration = totalDuration / segments.size
                    segments.mapIndexed { index, segmentText ->
                        ExperimentalWordTimestamp(
                            text = segmentText,
                            startTime = word.startTime + index * segmentDuration,
                            endTime = word.startTime + (index + 1) * segmentDuration,
                            hasTrailingSpace = if (index == segments.size - 1) word.hasTrailingSpace else false
                        ) to originalIdx
                    }
                } else listOf(word to originalIdx)
            } else listOf(word to originalIdx)
        }.let { data -> data.map { it.first } to data.map { it.second } }
    }

    val graphemeClusters = remember(mainText) { mainText.toGraphemeClusters() }
    val clusterCount = graphemeClusters.size
    val clusterCharOffsets = remember(mainText) {
        IntArray(clusterCount).also { offsets ->
            var charOffset = 0
            graphemeClusters.forEachIndexed { i, cluster ->
                offsets[i] = charOffset
                charOffset += cluster.length
            }
        }
    }

    val charToWordData = remember(mainText, effectiveWords, isBackground, graphemeClusters, clusterCharOffsets) {
        val wordIdxMap = IntArray(clusterCount) { -1 }
        val charInWordMap = IntArray(clusterCount)
        val wordLenMap = IntArray(clusterCount) { 1 }
        var currentPos = 0
        var clCursor = 0
        effectiveWords.forEachIndexed { wordIdx, word ->
            val rawWordText = word.text.let {
                if (isBackground) {
                    var t = it
                    if (wordIdx == 0) t = t.removePrefix("(")
                    if (wordIdx == effectiveWords.size - 1) t = t.removeSuffix(")")
                    t
                } else it
            }
            val trimmedWord = rawWordText.trim()
            val indexInMain = mainText.indexOf(trimmedWord, currentPos).takeIf { it != -1 }
                ?: mainText.indexOf(rawWordText, currentPos)
            if (indexInMain != -1) {
                val matchLen = if (mainText.startsWith(rawWordText, indexInMain)) rawWordText.length else trimmedWord.length
                val wordEndInMain = indexInMain + matchLen
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
                if (clCursor < clusterCount && clusterCharOffsets[clCursor] == wordEndInMain &&
                    wordEndInMain < mainText.length && mainText[wordEndInMain] == ' '
                ) {
                    val spaceClIdx = clCursor
                    wordIdxMap[spaceClIdx] = wordIdx
                    charInWordMap[spaceClIdx] = wordClusterLen
                    wordLenMap[spaceClIdx] = wordClusterLen + 1
                    clCursor++
                }
                currentPos = if (clCursor < clusterCount) clusterCharOffsets[clCursor] else wordEndInMain
            }
        }
        Triple(wordIdxMap, charInWordMap, wordLenMap)
    }

    val hyphenGroupData = remember(effectiveWords) {
        val map = mutableMapOf<Int, HyphenGroupWord>()
        var currentGroup = mutableListOf<Int>()
        effectiveWords.forEachIndexed { wordIdx, word ->
            currentGroup.add(wordIdx)
            if (!word.text.endsWith("-")) {
                if (currentGroup.size > 1) {
                    val groupSize = currentGroup.size
                    val groupStartMs = (effectiveWords[currentGroup.first()].startTime * 1000).toLong()
                    val groupEndMs = (word.endTime * 1000).toLong()
                    currentGroup.forEachIndexed { pos, idx ->
                        map[idx] = HyphenGroupWord(pos, groupSize, pos == groupSize - 1, groupStartMs, groupEndMs)
                    }
                }
                currentGroup = mutableListOf()
            }
        }
        map
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

        val isRtlText = remember(mainText) { mainText.containsRtl() }

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
                if (isRtlText) {
                    val (wordIdxMap, _, _) = charToWordData
                    val wordFactors = effectiveWords.map { word ->
                        val wStartMs = (word.startTime * 1000).toLong()
                        val wEndMs = (word.endTime * 1000).toLong()
                        val isWordSung = smoothPosition > wEndMs
                        val isWordActive = smoothPosition in wStartMs..wEndMs
                        val sungFactor = if (isWordSung) 1f
                        else if (isWordActive) ((smoothPosition - wStartMs).toFloat() / (wEndMs - wStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                        else 0f
                        Triple(sungFactor, isWordSung, isWordActive)
                    }

                    drawText(layoutResult, color = lineColor.copy(alpha = focusedAlpha))

                    effectiveWords.indices.forEach { wIdx ->
                        val (sungFactor, isWordSung, isWordActive) = wordFactors[wIdx]
                        var left = Float.MAX_VALUE
                        var right = Float.MIN_VALUE
                        var top = Float.MAX_VALUE
                        var bottom = Float.MIN_VALUE
                        var found = false

                        for (i in 0 until clusterCount) {
                            if (wordIdxMap[i] == wIdx) {
                                val charOffset = clusterCharOffsets[i]
                                val bounds = layoutResult.getBoundingBox(charOffset)
                                left = minOf(left, bounds.left)
                                right = maxOf(right, bounds.right)
                                top = minOf(top, bounds.top)
                                bottom = maxOf(bottom, bounds.bottom)
                                found = true
                            }
                        }

                        if (found) {
                            if (isWordSung) {
                                clipRect(left = left, top = top, right = right, bottom = bottom) {
                                    drawText(layoutResult, color = expressiveAccent)
                                }
                            } else if (isWordActive && sungFactor > 0f) {
                                clipRect(left = left, top = top, right = right, bottom = bottom) {
                                    drawText(layoutResult, color = expressiveAccent.copy(alpha = focusedAlpha + (1f - focusedAlpha) * sungFactor))
                                }
                            }
                        }
                    }
                    return@Canvas
                }

                val (wordIdxMap, charInWordMap, wordLenMap) = charToWordData
                val wordFactors = effectiveWords.map { word ->
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
                    val originalWordIdx = if (wordIdx != -1) effectiveToOriginalIdx[wordIdx] else -1

                    val (sungFactor, wordItem, isWordSung) = if (wordIdx != -1) wordFactors[wordIdx] else Triple(0f, null, false)
                    val wobble = if (originalWordIdx != -1) wordWobbles[originalWordIdx] else 0f

                    var crescendoDeltaX = 0f
                    val groupWord = if (wordIdx != -1) hyphenGroupData[wordIdx] else null
                    if (groupWord != null) {
                        val p = sungFactor
                        val timeSinceEnd = (smoothPosition - groupWord.groupEndMs).toFloat()
                        val exitDuration = 600f
                        val pOut = (timeSinceEnd / exitDuration).coerceIn(0f, 1f)
                        val peakScale = 0.06f
                        val decay = 2.5f
                        val freq = 10.0f
                        val baseScalePerSegment = 0.012f
                        if (pOut > 0f) {
                            val baseAtEnd = groupWord.pos * baseScalePerSegment
                            val totalAtEnd = baseAtEnd + peakScale
                            crescendoDeltaX = totalAtEnd * exp(-decay * pOut) * cos(freq * pOut * PI.toFloat()) * (1f - pOut)
                        } else if (groupWord.isLast) {
                            val base = groupWord.pos * baseScalePerSegment
                            val springPart = peakScale * (1f - exp(-decay * p) * cos(freq * p * PI.toFloat()) * (1f - p))
                            crescendoDeltaX = base + springPart
                        } else {
                            val boost = if (p > 0f) 0.02f * (1f - p) else 0f
                            crescendoDeltaX = (groupWord.pos * baseScalePerSegment) + boost
                        }
                    }

                    val charLp = if (wordItem != null) {
                        val sMs = wordItem.startTime * 1000
                        val dur = (wordItem.endTime * 1000 - sMs).coerceAtLeast(100.0)
                        val wProg = (smoothPosition.toDouble() - sMs) / dur
                        val cInW = charInWordMap[i].toDouble()
                        val wLen = wordLenMap[i].toDouble()
                        ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
                    } else 0f

                    val nudgeScale = if (wordItem != null && !isWordSung && sungFactor > 0f) {
                        0.038f * sin(charLp * PI.toFloat()) * exp(-3f * charLp)
                    } else 0f

                    val charScaleX = 1f + (wobble * 0.025f) + crescendoDeltaX + (nudgeScale * 0.3f)
                    val charBounds = layoutResult.getBoundingBox(charOffset)
                    lineTotalPushes[lineIdx] += charBounds.width * (charScaleX - 1f)
                }

                for (i in 0 until clusterCount) {
                    val charOffset = clusterCharOffsets[i]
                    val lineIdx = layoutResult.getLineForOffset(charOffset)
                    val charBounds = layoutResult.getBoundingBox(charOffset)
                    val wordIdx = wordIdxMap[i]
                    val originalWordIdx = if (wordIdx != -1) effectiveToOriginalIdx[wordIdx] else -1

                    val alignShift = when (alignment) {
                        TextAlign.Center -> -lineTotalPushes[lineIdx] / 2f
                        TextAlign.Right -> -lineTotalPushes[lineIdx]
                        else -> 0f
                    }

                    val (sungFactor, wordItem, isWordSung) = if (wordIdx != -1) wordFactors[wordIdx] else Triple(0f, null, false)
                    val wobble = if (originalWordIdx != -1) wordWobbles[originalWordIdx] else 0f
                    val wobbleX = wobble * 0.025f
                    val wobbleY = wobble * 0.015f

                    val charLp = if (wordItem != null) {
                        val sMs = wordItem.startTime * 1000
                        val dur = (wordItem.endTime * 1000 - sMs).coerceAtLeast(100.0)
                        val wProg = (smoothPosition.toDouble() - sMs) / dur
                        val cInW = charInWordMap[i].toDouble()
                        val wLen = wordLenMap[i].toDouble()
                        ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
                    } else 0f

                    val shouldGlow = wordItem != null && !isWordSung && sungFactor > 0.001f

                    var crescendoDeltaX = 0f
                    var crescendoDeltaY = 0f
                    val groupWord = if (wordIdx != -1) hyphenGroupData[wordIdx] else null
                    if (groupWord != null) {
                        val p = sungFactor
                        val timeSinceEnd = (smoothPosition - groupWord.groupEndMs).toFloat()
                        val exitDuration = 600f
                        val pOut = (timeSinceEnd / exitDuration).coerceIn(0f, 1f)
                        val peakScale = 0.06f
                        val decay = 3.5f
                        val freq = 5.0f
                        val baseScalePerSegment = 0.012f
                        if (pOut > 0f) {
                            val baseAtEnd = groupWord.pos * baseScalePerSegment
                            val totalAtEnd = baseAtEnd + peakScale
                            val springOut = totalAtEnd * exp(-decay * pOut) * cos(freq * pOut * PI.toFloat()) * (1f - pOut)
                            crescendoDeltaX = springOut
                            crescendoDeltaY = springOut
                        } else if (groupWord.isLast) {
                            val base = groupWord.pos * baseScalePerSegment
                            val springPart = peakScale * (1f - exp(-decay * p) * cos(freq * p * PI.toFloat()) * (1f - p))
                            crescendoDeltaX = base + springPart
                            crescendoDeltaY = base + springPart
                        } else {
                            val boost = if (p > 0f) 0.02f * (1f - p) else 0f
                            val base = (groupWord.pos * baseScalePerSegment) + boost
                            crescendoDeltaX = base
                            crescendoDeltaY = base
                        }
                    }

                    val nudgeStrength = 0.038f
                    val nudgeScale = if (wordItem != null && !isWordSung && sungFactor > 0f) {
                        nudgeStrength * sin(charLp * PI.toFloat()) * exp(-3f * charLp)
                    } else 0f

                    val charScaleX = 1f + wobbleX + crescendoDeltaX + nudgeScale * 0.3f
                    val charScaleY = 1f + wobbleY + crescendoDeltaY + nudgeScale

                    withTransform({
                        var waveOffset = 0f
                        if (groupWord != null) {
                            val wallTime = System.currentTimeMillis()
                            val adjSmoothPos = smoothPosition
                            val timeInGroup = (adjSmoothPos - groupWord.groupStartMs).toFloat()
                            val timeToGroupEnd = (groupWord.groupEndMs - adjSmoothPos).toFloat()
                            val waveFade = (timeInGroup / 200f).coerceIn(0f, 1f) * (timeToGroupEnd / 200f).coerceIn(0f, 1f)
                            if (waveFade > 0.01f) {
                                val waveSpeed = 0.006f
                                val waveHeight = 3.24f
                                val phaseOffset = i * 0.4f
                                waveOffset = sin(wallTime * waveSpeed + phaseOffset) * waveHeight * waveFade
                            }
                        }

                        translate(left = alignShift + lineCurrentPushes[lineIdx] + charBounds.left, top = charBounds.top + waveOffset)
                        if (wordIdx != -1) {
                            scale(
                                charScaleX,
                                charScaleY,
                                pivot = Offset(charBounds.width / 2f, charBounds.height)
                            )
                        }
                    }) {
                        if (shouldGlow) {
                            val sMs = wordItem.startTime * 1000
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
                                    glowPaint.color = expressiveAccent.copy(alpha = glowAlpha).toArgb()
                                    glowPaint.textSize = lyricStyle.fontSize.toPx()
                                    glowPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                                    canvas.nativeCanvas.drawText(
                                        letterLayouts[i].layoutInput.text.text,
                                        0f,
                                        letterLayouts[i].firstBaseline,
                                        glowPaint
                                    )
                                }
                            }
                        }
                        val allWordsSung = effectiveWords.all { smoothPosition > it.endTime * 1000 }
                        val baseAlpha = if (isWordSung || charLp > 0.99f) 1f else (focusedAlpha + (1f - focusedAlpha) * sungFactor)
                        val charAlpha = if (wordIdx == -1) (if (allWordsSung) 1f else focusedAlpha) else baseAlpha
                        drawText(letterLayouts[i], color = expressiveAccent.copy(alpha = charAlpha))
                        if (!isWordSung && charLp > 0f && charLp < 1f) {
                            val fXL = charBounds.width * charLp
                            val eW = (charBounds.width * 0.45f).coerceAtLeast(1f)
                            val sWL = (fXL - eW).coerceAtLeast(0f)
                            if (sWL > 0f) {
                                clipRect(left = 0f, top = 0f, right = sWL, bottom = charBounds.height) {
                                    drawText(letterLayouts[i], color = expressiveAccent)
                                }
                            }
                            for (j in 0 until 12) {
                                val start = sWL + (j * eW / 12f)
                                val end = (sWL + ((j + 1) * eW / 12f) + 0.5f).coerceAtMost(fXL)
                                if (end > start) {
                                    clipRect(left = start, top = 0f, right = end, bottom = charBounds.height) {
                                        drawText(letterLayouts[i], color = expressiveAccent.copy(alpha = 1f - (j + 0.5f) / 12f))
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

/**
 * Visual indicator shown during instrumental intervals/gaps between lyric lines.
 * Features an organic wavy circular progress ring that fills as the interval progresses.
 */
@Composable
internal fun IntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionMs: Long,
    visible: Boolean,
    color: Color = Color.White,
    modifier: Modifier = Modifier
) {
    val progress = if (gapEndMs > gapStartMs) {
        ((currentPositionMs - gapStartMs).toFloat() / (gapEndMs - gapStartMs).toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "intervalProgress"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0.35f,
        animationSpec = tween(durationMillis = 250),
        label = "intervalAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            com.auralis.music.ui.components.SquirlyProgressRing(
                progress = animatedProgress,
                strokeWidth = 3.5.dp,
                trackColor = color.copy(alpha = 0.2f),
                progressColor = color.copy(alpha = 0.95f),
                waveCount = 6,
                waveAmplitudeRatio = 0.12f,
                modifier = Modifier.size(36.dp)
            )

            // 3 Apple Music style rhythm dots during interval
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dotCount = 3
                val activeDotIdx = if (visible && gapEndMs > gapStartMs) {
                    ((currentPositionMs / 380L) % dotCount).toInt()
                } else -1
                repeat(dotCount) { i ->
                    val isDotActive = i == activeDotIdx
                    val dotAlpha = if (isDotActive) 0.95f else 0.35f
                    val dotScale = if (isDotActive) 1.2f else 1.0f

                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .graphicsLayer {
                                scaleX = dotScale
                                scaleY = dotScale
                            }
                            .background(color.copy(alpha = dotAlpha), CircleShape)
                    )
                }
            }
        }
    }
}

