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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
private val LYRICS_FADE_TOP_DP = 44.dp
private val LYRICS_FADE_BOTTOM_DP = 120.dp
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
 * Delegates to [LyricsEngine.findVisualActiveLineIndices] to guarantee that lines remain
 * highlighted across gaps until the next subsequent lyric begins.
 */
internal fun findExperimentalActiveLineIndices(
    lines: List<LyricLine>,
    position: Long
): Set<Int> = LyricsEngine.findVisualActiveLineIndices(lines, position, 0L)

internal data class ExperimentalPendingSeekTarget(
    val lineIndex: Int,
    val targetTimeMs: Long,
    val requestId: Long,
    val timestamp: Long = System.currentTimeMillis()
)

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
        rawLines
            .map { com.auralis.music.data.parser.WordTiming.splitMergedWordsInLine(it) }
            .filter { it.text.isNotBlank() || it.words?.any { w -> w.word.isNotBlank() } == true }
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
                val effEnd = line.effectiveEndTime
                val gapStart = when {
                    effEnd != null -> effEnd
                    line.text.isBlank() -> line.time
                    else -> {
                        val estimatedDuration = (line.text.length * 100L).coerceIn(2500L, 5000L)
                        (line.time + estimatedDuration).coerceAtMost(nextStart - 2000L)
                    }
                }
                val gapDuration = nextStart - gapStart
                if (gapDuration >= 3500L) {
                    val indicatorStart = if (gapDuration > 35_000L) {
                        nextStart - 8_000L
                    } else {
                        gapStart
                    }
                    list.add(ExperimentalLyricsListItem.Indicator(index, indicatorStart, nextStart))
                }
            }
        }
        list
    }

    // Interactive & selection state
    var activeLineIndices by remember { mutableStateOf(emptySet<Int>()) }
    var authoritativeTargetIndex by rememberSaveable { mutableIntStateOf(-1) }
    var scrollRequestId by remember { mutableLongStateOf(0L) }
    var pendingSeekTarget by remember { mutableStateOf<ExperimentalPendingSeekTarget?>(null) }

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

    // Continuous playback clock interpolator
    LaunchedEffect(lyrics, effectiveLines, isPlaying, lyricsClockSource) {
        if (effectiveLines.isEmpty()) {
            activeLineIndices = emptySet()
            return@LaunchedEffect
        }

        var lastBasePos = positionState.value
        var lastUpdateTime = System.currentTimeMillis()

        while (isActive) {
            delay(25)
            val now = System.currentTimeMillis()
            val basePos = if (lyricsClockSource != null) {
                lyricsClockSource.rawPositionMs()
            } else {
                positionState.value
            }

            if (basePos != lastBasePos) {
                // If base position jumped significantly (e.g. user scrubbed the progress bar)
                // and it doesn't match our pending seek target, clear the pending seek target.
                val pending = pendingSeekTarget
                if (pending != null && kotlin.math.abs(basePos - pending.targetTimeMs) > 1000L) {
                    pendingSeekTarget = null
                }
                lastBasePos = basePos
                lastUpdateTime = now
            }

            val elapsed = now - lastUpdateTime
            val currentPos = lastBasePos + (if (isPlaying) elapsed else 0L) + offsetMs
            currentPositionState = currentPos

            // Check pending seek target convergence/timeout
            val pending = pendingSeekTarget
            val isSeekingInFlight = if (pending != null) {
                val hasConverged = kotlin.math.abs(currentPos - pending.targetTimeMs) <= 350L
                val isTimedOut = (now - pending.timestamp) > 650L
                if (hasConverged || isTimedOut) {
                    pendingSeekTarget = null
                    false
                } else {
                    true
                }
            } else false

            if (isSeekingInFlight && pending != null) {
                val target = pending.lineIndex
                if (authoritativeTargetIndex != target) {
                    authoritativeTargetIndex = target
                }
                if (deferredCurrentLineIndex != target) {
                    deferredCurrentLineIndex = target
                }
                val forcedActive = setOf(target)
                if (activeLineIndices != forcedActive) {
                    activeLineIndices = forcedActive
                }
            } else {
                val initialActiveIndices = findExperimentalActiveLineIndices(effectiveLines, currentPos)

                // Expand to include paired lead/background lines
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

                if (newActiveIndices != activeLineIndices) {
                    activeLineIndices = newActiveIndices
                }

                val primaryLine = LyricsEngine.findActiveLyricIndex(effectiveLines, currentPos, offsetMs)
                if (primaryLine != -1 && primaryLine != authoritativeTargetIndex) {
                    authoritativeTargetIndex = primaryLine
                    deferredCurrentLineIndex = primaryLine
                }
            }
        }
    }

    // Auto-scroll resume after preview period
    LaunchedEffect(lastPreviewTime) {
        if (lastPreviewTime != 0L) {
            delay(LYRICS_PREVIEW_TIME)
            lastPreviewTime = 0L
        }
    }

    val listState = rememberLazyListState()
    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var hasInitialCentered by remember { mutableStateOf(false) }
    var lastCenteredIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(lyrics, effectiveLines) {
        isAutoScrollEnabled = appearance.autoScrollLyrics
        authoritativeTargetIndex = -1
        deferredCurrentLineIndex = 0
        isSelectionModeActive = false
        selectedIndices.clear()
        hasInitialCentered = false
        lastCenteredIndex = -1
        pendingSeekTarget = null
    }

    val activeListIndexState = remember(mergedLyricsList) {
        derivedStateOf {
            val curPos = currentPositionState
            val curIdx = authoritativeTargetIndex
            val isSeeking = pendingSeekTarget != null
            val activeIndicatorIndex = if (!isSeeking) {
                mergedLyricsList.indexOfFirst {
                    it is ExperimentalLyricsListItem.Indicator &&
                    curPos >= it.gapStartMs && curPos <= it.gapEndMs
                }
            } else -1

            if (activeIndicatorIndex >= 0) {
                activeIndicatorIndex
            } else {
                mergedLyricsList.indexOfFirst {
                    it is ExperimentalLyricsListItem.Line && it.index == curIdx
                }.coerceAtLeast(0)
            }
        }
    }

    val centerActiveLine: suspend (targetIndex: Int, animate: Boolean) -> Unit = { targetIndex, animate ->
        if (targetIndex in mergedLyricsList.indices) {
            isProgrammaticScroll = true
            try {
                var layoutInfo = listState.layoutInfo
                var viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                if (viewportHeight <= 0) {
                    try {
                        withFrameMillis { }
                    } catch (_: Exception) {
                        delay(16L)
                    }
                    layoutInfo = listState.layoutInfo
                    viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                }

                if (viewportHeight > 0) {
                    val targetCenterY = layoutInfo.viewportStartOffset + (viewportHeight * LYRICS_ANCHOR_RATIO)
                    var itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                    if (itemInfo == null) {
                        val currentFirst = listState.firstVisibleItemIndex
                        val distance = kotlin.math.abs(targetIndex - currentFirst)
                        if (distance > 8) {
                            val preIndex = if (targetIndex > currentFirst) {
                                (targetIndex - 2).coerceAtLeast(0)
                            } else {
                                (targetIndex + 2).coerceAtMost(mergedLyricsList.size - 1)
                            }
                            listState.scrollToItem(preIndex)
                        } else {
                            listState.scrollToItem(targetIndex)
                        }
                        try {
                            withFrameMillis { }
                        } catch (_: Exception) {
                            delay(16L)
                        }
                        layoutInfo = listState.layoutInfo
                        itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                    }

                    if (itemInfo != null) {
                        val itemCenterY = itemInfo.offset + (itemInfo.size / 2f)
                        val scrollDelta = itemCenterY - targetCenterY
                        if (kotlin.math.abs(scrollDelta) > 1.5f) {
                            try {
                                if (animate) {
                                    listState.animateScrollBy(
                                        value = scrollDelta,
                                        animationSpec = tween(
                                            durationMillis = 350,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                } else {
                                    listState.scrollBy(scrollDelta)
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }
                        }
                    }
                }
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    val resyncLyrics: () -> Unit = {
        isAutoScrollEnabled = true
        lastPreviewTime = 0L
        isUserInteracting = false
        pendingSeekTarget = null
        val target = LyricsEngine.findActiveLyricIndex(effectiveLines, currentPositionState, offsetMs)
        if (target != -1) {
            authoritativeTargetIndex = target
            deferredCurrentLineIndex = target
        }
        val activeIndex = activeListIndexState.value
        if (activeIndex in mergedLyricsList.indices) {
            scope.launch {
                centerActiveLine(activeIndex, true)
                lastCenteredIndex = activeIndex
            }
        }
    }

    val lyricsNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!isProgrammaticScroll && source == NestedScrollSource.UserInput && kotlin.math.abs(available.y) > 0.5f) {
                    if (!isSelectionModeActive) {
                        isAutoScrollEnabled = false
                        isUserInteracting = true
                        lastPreviewTime = System.currentTimeMillis()
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!isProgrammaticScroll && source == NestedScrollSource.UserInput &&
                    (kotlin.math.abs(consumed.y) > 0.5f || kotlin.math.abs(available.y) > 0.5f)
                ) {
                    if (!isSelectionModeActive) {
                        isAutoScrollEnabled = false
                        lastPreviewTime = System.currentTimeMillis()
                    }
                }
                return super.onPostScroll(consumed, available, source)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                isUserInteracting = false
                return super.onPostFling(consumed, available)
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            isUserInteracting = false
        }
    }

    LaunchedEffect(activeListIndexState, isSynced, isAutoScrollEnabled, mergedLyricsList) {
        if (!isSynced || !isAutoScrollEnabled) return@LaunchedEffect
        snapshotFlow {
            Triple(activeListIndexState.value, isUserInteracting, isSelectionModeActive)
        }
            .distinctUntilChanged()
            .collectLatest { (activeIndex, interacting, selecting) ->
                if (interacting || selecting) return@collectLatest
                if (activeIndex !in mergedLyricsList.indices) return@collectLatest

                val shouldAnimate = hasInitialCentered
                centerActiveLine(activeIndex, shouldAnimate)
                lastCenteredIndex = activeIndex
                hasInitialCentered = true
            }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 12.dp)
    ) {
        val viewportHeight = maxHeight
        val topPadding = (viewportHeight * LYRICS_ANCHOR_RATIO - 40.dp).coerceAtLeast(32.dp)
        val bottomPadding = (viewportHeight * (1f - LYRICS_ANCHOR_RATIO) + 60.dp).coerceAtLeast(180.dp)

        if (effectiveLines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No lyrics available",
                    fontSize = 18.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdge(top = 44.dp, bottom = LYRICS_FADE_BOTTOM_DP)
                    .nestedScroll(lyricsNestedScrollConnection),
                contentPadding = PaddingValues(
                    top = topPadding,
                    bottom = bottomPadding,
                    start = 0.dp,
                    end = 0.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(
                    items = mergedLyricsList,
                    key = { listIndex, listItem ->
                        when (listItem) {
                            is ExperimentalLyricsListItem.Line -> "exp_line_${listItem.line.time}_${listItem.index}"
                            is ExperimentalLyricsListItem.Indicator -> "exp_indicator_${listItem.gapStartMs}_${listItem.gapEndMs}_$listIndex"
                        }
                    }
                ) { listIndex, listItem ->
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
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (index == 0 && isIntroActiveState.value) {
                                    InstrumentalIntroIndicator(
                                        currentTimeMsState = introTimeState,
                                        introDurationMs = introDurationMs,
                                        onSkipIntro = { onSeekTo(introDurationMs) }
                                    )
                                }
                                if (index == 0 && !isSynced) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color.White.copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                                        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.70f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Unsynced Lyrics",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White.copy(alpha = 0.85f)
                                            )
                                        }
                                    }
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
                                            val reqId = ++scrollRequestId
                                            pendingSeekTarget = ExperimentalPendingSeekTarget(
                                                lineIndex = index,
                                                targetTimeMs = line.time,
                                                requestId = reqId
                                            )
                                            authoritativeTargetIndex = index
                                            deferredCurrentLineIndex = index
                                            activeLineIndices = setOf(index)
                                            if (appearance.autoScrollLyrics) {
                                                isAutoScrollEnabled = true
                                            }
                                            isUserInteracting = false
                                            lastPreviewTime = 0L
                                            onSeekTo(line.time)
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
                                currentPositionState >= (listItem.gapStartMs - 300L) &&
                                currentPositionState <= listItem.gapEndMs
                            LyricsIntervalIndicator(
                                gapStartMs = listItem.gapStartMs,
                                gapEndMs = listItem.gapEndMs,
                                currentPositionMs = currentPositionState,
                                visible = indicatorVisible,
                                color = Color.White,
                                onSkip = { onSeekTo(listItem.gapEndMs) }
                            )
                        }
                    }
                }

                if (!isSynced && onSearchManually != null) {
                    item(key = "unsynced_manual_search") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            OutlinedButton(
                                onClick = onSearchManually,
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White.copy(alpha = 0.85f)
                                ),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Search Synced Lyrics",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
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
            top = if (line.isBackground) 0.dp else if (!isSynced) 4.dp else 12.dp,
            bottom = if (line.isBackground) 2.dp else if (!isSynced) 4.dp else 12.dp
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
                val targetAlpha = if (!isSynced) {
                    0.85f
                } else if (line.isBackground || isActiveLine) {
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

                val effectiveFontSize = when {
                    line.isBackground -> (lyricsTextSize * 0.70f).sp
                    !isSynced -> (22f * (lyricsTextSize / 34f)).sp
                    else -> lyricsTextSize.sp
                }
                val effectiveFontWeight = if (!isSynced) FontWeight.SemiBold else FontWeight.Bold
                val effectiveLineHeight = if (line.isBackground) {
                    (effectiveFontSize.value * lyricsLineSpacing).sp
                } else if (!isSynced) {
                    (effectiveFontSize.value * 1.35f).sp
                } else {
                    (effectiveFontSize.value * lyricsLineSpacing).sp
                }

                val lyricStyle = TextStyle(
                    fontSize = effectiveFontSize,
                    fontWeight = effectiveFontWeight,
                    fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                    lineHeight = effectiveLineHeight,
                    letterSpacing = if (!isSynced) (-0.3).sp else (-0.5).sp,
                    textAlign = agentTextAlign,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )

                // Genuine RichSync word timestamps or synthetic word pacing for line sync
                val effectiveWords = remember(line.words, syncType, mainText, line.time) {
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
                    } else if (isSynced && mainText.isNotBlank()) {
                        val wordTokens = mainText.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val wordDurationSec = 0.18
                        val wordStaggerSec = 0.03
                        val startTimeSec = line.time / 1000.0
                        wordTokens.mapIndexed { idx, wordText ->
                            ExperimentalWordTimestamp(
                                text = wordText,
                                startTime = startTimeSec + (idx * wordStaggerSec),
                                endTime = startTimeSec + (idx * wordStaggerSec) + wordDurationSec,
                                hasTrailingSpace = idx < wordTokens.size - 1
                            )
                        }
                    } else null
                }

                if (isSynced && effectiveWords != null && isActiveLine && mainText.isNotBlank()) {
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
                        } else if (wordItem != null && !isWordSung && sungFactor > 0f) {
                            val wallTime = System.currentTimeMillis()
                            val timeInWord = (smoothPosition - (wordItem.startTime * 1000)).toFloat()
                            val timeToWordEnd = ((wordItem.endTime * 1000) - smoothPosition).toFloat()
                            val waveFade = (timeInWord / 80f).coerceIn(0f, 1f) * (timeToWordEnd / 80f).coerceIn(0f, 1f)
                            if (waveFade > 0.01f) {
                                val waveSpeed = 0.006f
                                val waveHeight = 2.8f
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

