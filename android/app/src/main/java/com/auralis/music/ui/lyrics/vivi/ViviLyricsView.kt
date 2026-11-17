/**
 * Lyric line/word animations, scrolling, blur, line scaling and spacing ported from
 * Vivi Music's Lyrics.kt (https://github.com/vivizzz007/vivi-music, GPL-3.0).
 *
 * Every lyric style except Spicy Lyrics renders here. Auralis keeps its own loading and
 * empty states (handled by the caller), selection + share sheet, re-sync button,
 * interlude indicator and credits.
 */
package com.auralis.music.ui.lyrics.vivi

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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.auralis.music.R
import com.auralis.music.data.datastore.ContentSettingsStore
import com.auralis.music.domain.lyrics.LyricsRomanizer
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.lyrics.LocalJapaneseRomanizationContext
import com.auralis.music.ui.lyrics.LyricsCredits
import com.auralis.music.ui.lyrics.LyricsIntervalIndicator
import com.auralis.music.ui.lyrics.LyricsResyncButton
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val LYRICS_ANCHOR_RATIO = 0.35f
private val LYRICS_ITEM_FALLBACK_HEIGHT_DP = 68.dp
private val LYRICS_ITEM_GAP_DP = 16.dp
private val LYRICS_FADE_TOP_DP = 130.dp
private val LYRICS_FADE_BOTTOM_DP = 160.dp
private const val LYRICS_STAGGER_DELAY_PER_DISTANCE = 20
private const val LYRICS_STAGGER_DELAY_MAX_MS = 200
private const val LYRICS_PREVIEW_TIME_MS = 2000L
private const val MAX_SELECTION = 5

/** A position jump this large between two frames is a seek, not playback. */
private const val SEEK_JUMP_MS = 1500L

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ViviLyricsView(
    lyrics: LyricsData,
    sourceLines: List<LyricLine>,
    isSynced: Boolean,
    positionState: State<Long>,
    offsetMs: Long,
    onSeekTo: (Long) -> Unit,
    track: Track?,
    lyricsStandardBlur: Boolean,
    modifier: Modifier = Modifier
) {
    val appearance = LocalAppearanceSettings.current
    val lyricsAnimationStyle = LyricsAnimationMode.fromDisplayName(appearance.lyricsAnimation)
    val isMetro = lyricsAnimationStyle == LyricsAnimationMode.METRO_LYRICS
    val lyricsTextPosition = LyricsPosition.fromSetting(appearance.lyricsTextPosition)
    val changeLyrics = appearance.changeLyricsOnTap
    val scrollLyrics = appearance.autoScrollLyrics
    val lyricsGlowEffect = appearance.enableGlowingLyricsEffect
    val lyricsTextSize = appearance.lyricsTextSize
    val lyricsLineSpacing = appearance.lyricsLineSpacing
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Lyrics sit on the player's artwork-derived background: always light, like Vivi's animated backgrounds.
    val expressiveAccent = Color.White
    val textColor = expressiveAccent

    val wordSynced = lyrics.syncType == SyncType.RICHSYNC
    val lines = remember(sourceLines, isSynced, wordSynced) { toViviEntries(sourceLines, isSynced, wordSynced) }

    // Settings → Content → Romanize lyrics feeds Vivi's romanized second line.
    val contentSettings by ContentSettingsStore.current.collectAsState()
    val showRomanized = contentSettings.romanizationEnabled
    val japaneseContext = LocalJapaneseRomanizationContext.current
    LaunchedEffect(lines, showRomanized, contentSettings.romanizedScripts, japaneseContext) {
        lines.forEach { it.romanizedTextFlow.value = null }
        if (!showRomanized) return@LaunchedEffect
        lines.forEach { entry ->
            if (entry.text.isNotBlank()) {
                entry.romanizedTextFlow.value = withContext(Dispatchers.Default) {
                    LyricsRomanizer.romanize(entry.text, contentSettings.romanizedScripts, japaneseContext)
                }
            }
        }
    }
    val hasActiveTranslations = remember(sourceLines) { sourceLines.any { !it.translatedText.isNullOrBlank() } }

    var currentLineIndex by remember { mutableIntStateOf(-1) }
    var currentPlaybackPosition by remember { mutableLongStateOf(0L) }
    var deferredCurrentLineIndex by rememberSaveable { mutableIntStateOf(0) }
    var previousLineIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastPreviewTime by rememberSaveable { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var initialScrollDone by rememberSaveable { mutableStateOf(false) }
    var shouldScrollToFirstLine by rememberSaveable { mutableStateOf(true) }
    var isAppMinimized by rememberSaveable { mutableStateOf(false) }

    // Auralis selection: long-press picks up to five lines for the share sheet.
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val isSelectionModeActive = selectedIndices.isNotEmpty()

    val lazyListState = rememberLazyListState()
    var isAutoScrollEnabled by rememberSaveable { mutableStateOf(true) }

    var activeLineIndices by remember { mutableStateOf(emptySet<Int>()) }
    var scrollTargetIndex by rememberSaveable { mutableIntStateOf(-1) }
    var previousScrollActiveIndices by remember { mutableStateOf(emptySet<Int>()) }
    var lastMainMaxSeen by remember(lines) { mutableIntStateOf(-1) }

    // Lines plus interlude indicators for gaps over four seconds, as Vivi builds them.
    val mergedLyricsList = remember(lines) {
        val result = mutableListOf<LyricsListItem>()
        lines.forEachIndexed { i, entry ->
            if (entry.text.isNotBlank()) result.add(LyricsListItem.Line(i, entry))
            if (isSynced && i < lines.size - 1) {
                val nextStart = lines[i + 1].time
                val currentEnd = when {
                    !entry.words.isNullOrEmpty() -> (entry.words.last().endTime * 1000).toLong()
                    entry.text.isBlank() -> entry.time
                    else -> null
                }
                if (currentEnd != null && currentEnd < nextStart && nextStart - currentEnd > 4000L) {
                    result.add(LyricsListItem.Indicator(i, nextStart - currentEnd, currentEnd, nextStart, lines[i + 1].agent))
                }
            }
        }
        result
    }

    // The list styles keep every entry (Vivi's layout) with Auralis's interlude indicator after the gap's line.
    val lazyItems = remember(lines, mergedLyricsList) {
        val indicators = mergedLyricsList.filterIsInstance<LyricsListItem.Indicator>().associateBy { it.afterLineIndex }
        buildList {
            lines.forEachIndexed { i, entry ->
                add(LyricsListItem.Line(i, entry))
                indicators[i]?.let { add(it) }
            }
        }
    }
    val lazyIndexOfLine = remember(lazyItems) {
        IntArray(lines.size).also { map ->
            lazyItems.forEachIndexed { lazyIndex, item -> if (item is LyricsListItem.Line) map[item.index] = lazyIndex }
        }
    }

    val activeListIndex by remember(mergedLyricsList, deferredCurrentLineIndex) {
        derivedStateOf {
            mergedLyricsList.indexOfFirst {
                (it is LyricsListItem.Line && it.index == deferredCurrentLineIndex) ||
                    (it is LyricsListItem.Indicator && it.afterLineIndex == deferredCurrentLineIndex)
            }.coerceAtLeast(0)
        }
    }

    var userManualOffset by remember { mutableFloatStateOf(0f) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val velocityTracker = remember { VelocityTracker() }
    val decayAnimSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }
    val itemHeights = remember(lines, mergedLyricsList) { mutableStateMapOf<Int, Int>() }
    var isInitialLayout by remember(lines, mergedLyricsList) { mutableStateOf(true) }

    LaunchedEffect(lines, mergedLyricsList.size, isMetro) {
        if (isMetro && mergedLyricsList.isNotEmpty()) {
            isInitialLayout = true
            snapshotFlow {
                val h = itemHeights.toMap()
                val windowStart = (activeListIndex - 8).coerceAtLeast(0)
                val windowEnd = (activeListIndex + 12).coerceAtMost(mergedLyricsList.size - 1)
                (windowStart..windowEnd).all { h.containsKey(it) }
            }.first { it }
            delay(150)
            isInitialLayout = false
        }
    }

    LaunchedEffect(activeListIndex, isMetro) {
        if (isMetro && mergedLyricsList.isNotEmpty()) {
            val h = itemHeights.toMap()
            val windowStart = (activeListIndex - 3).coerceAtLeast(0)
            val windowEnd = (activeListIndex + 3).coerceAtMost(mergedLyricsList.size - 1)
            if ((windowStart..windowEnd).any { !h.containsKey(it) }) {
                isInitialLayout = true
                snapshotFlow {
                    val hh = itemHeights.toMap()
                    (windowStart..windowEnd).all { hh.containsKey(it) }
                }.first { it }
                isInitialLayout = false
            }
        }
    }

    // The tab stays composed while hidden, so leaving it has to end a selection itself.
    val lyricsOnScreen = com.auralis.music.ui.theme.LocalPreviewMotionActive.current != false
    LaunchedEffect(lyricsOnScreen) { if (!lyricsOnScreen) selectedIndices = emptySet() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val visible = lazyListState.layoutInfo.visibleItemsInfo
                if (visible.any { it.index == currentLineIndex.coerceAtLeast(0).let { i -> lazyIndexOfLine.getOrElse(i) { 0 } } }) {
                    initialScrollDone = false
                }
                isAppMinimized = true
            } else if (event == Lifecycle.Event.ON_START) {
                isAppMinimized = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(lines) {
        isAutoScrollEnabled = true
        scrollTargetIndex = -1
        deferredCurrentLineIndex = 0
        selectedIndices = emptySet()
        previousScrollActiveIndices = emptySet()
        lastMainMaxSeen = -1
    }

    val offsetState = rememberUpdatedState(offsetMs)

    // Clock loop: Vivi's 8 ms poll, driven per frame from Auralis's smoothed lyric clock.
    LaunchedEffect(lines, isMetro, isSynced) {
        if (!isSynced) {
            currentLineIndex = -1
            activeLineIndices = emptySet()
            return@LaunchedEffect
        }
        var lastPosition = positionState.value
        var seekUntil = 0L
        val hasWordTimings = lines.any { !it.words.isNullOrEmpty() }
        while (isActive) {
            withFrameNanos { }
            val position = positionState.value
            val now = System.currentTimeMillis()
            if (abs(position - lastPosition) > SEEK_JUMP_MS) seekUntil = now + 300L
            lastPosition = position
            isSeeking = now < seekUntil
            currentPlaybackPosition = position
            val effectivePosition = position + offsetState.value

            if (!isMetro) {
                currentLineIndex = findCurrentLineIndex(lines, effectivePosition)
                continue
            }

            val initialActiveIndices = findActiveLineIndices(lines, effectivePosition)
            val scrollActiveIndicesRaw = findActiveLineIndices(lines, effectivePosition + (if (hasWordTimings) 0L else 250L))
            fun withPairedMain(indices: Set<Int>): Set<Int> {
                val out = indices.toMutableSet()
                for (i in indices) {
                    if (lines.getOrNull(i)?.isBackground == true) {
                        for (j in i - 1 downTo 0) {
                            if (lines.getOrNull(j)?.isBackground == false) { out.add(j); break }
                        }
                    }
                }
                return out
            }
            val scrollActiveIndices = withPairedMain(scrollActiveIndicesRaw)
            val newActiveIndices = withPairedMain(initialActiveIndices)

            val scrollMax = scrollActiveIndices.filter { !lines[it].isBackground }.maxOrNull()
                ?: (scrollActiveIndices.maxOrNull() ?: -1)
            val isCurrentTargetStillActive = scrollTargetIndex in scrollActiveIndices
            val anyStillActive = scrollActiveIndices.isNotEmpty()
            val shouldScroll = when {
                isSeeking -> true
                !isCurrentTargetStillActive && anyStillActive && scrollMax > scrollTargetIndex -> true
                !isCurrentTargetStillActive && !anyStillActive && previousScrollActiveIndices.isNotEmpty() -> true
                scrollTargetIndex == -1 && anyStillActive -> true
                previousScrollActiveIndices.isEmpty() && anyStillActive && scrollMax > scrollTargetIndex -> true
                else -> false
            }
            if (shouldScroll) {
                val targetToScroll = when {
                    isSeeking -> scrollMax
                    !isCurrentTargetStillActive && anyStillActive -> scrollMax
                    !isCurrentTargetStillActive && !anyStillActive ->
                        (lastMainMaxSeen + 1 until lines.size).firstOrNull { !lines[it].isBackground } ?: scrollTargetIndex
                    else -> scrollMax
                }
                if (targetToScroll != -1 && (isSeeking || targetToScroll > scrollTargetIndex)) {
                    scrollTargetIndex = targetToScroll
                }
            }
            if (scrollMax > lastMainMaxSeen && scrollMax != -1) lastMainMaxSeen = scrollMax
            previousScrollActiveIndices = scrollActiveIndices
            activeLineIndices = newActiveIndices
            currentLineIndex = scrollMax
        }
    }

    LaunchedEffect(isSeeking, lastPreviewTime) {
        if (isSeeking) {
            lastPreviewTime = 0L
        } else if (lastPreviewTime != 0L) {
            delay(LYRICS_PREVIEW_TIME_MS)
            lastPreviewTime = 0L
        }
    }

    suspend fun performSmoothPageScroll(targetLineIndex: Int, duration: Int = 700) {
        val targetIndex = lazyIndexOfLine.getOrElse(targetLineIndex.coerceAtLeast(0)) { 0 }
        val itemInfo = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        if (itemInfo != null) {
            val viewportHeight = lazyListState.layoutInfo.viewportEndOffset - lazyListState.layoutInfo.viewportStartOffset
            val center = lazyListState.layoutInfo.viewportStartOffset + (viewportHeight / 2)
            val itemCenter = itemInfo.offset + itemInfo.size / 2
            val offset = itemCenter - center
            if (abs(offset) > 10) {
                lazyListState.animateScrollBy(
                    value = offset.toFloat(),
                    animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing)
                )
            }
        } else {
            lazyListState.scrollToItem(targetIndex)
        }
    }

    suspend fun scrollToLineSlowly(lineIndex: Int) {
        val index = lazyIndexOfLine.getOrElse(lineIndex) { 0 }
        lazyListState.scrollToItem(index = index)
        val itemInfo = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (itemInfo != null) {
            val viewportHeight = lazyListState.layoutInfo.viewportEndOffset - lazyListState.layoutInfo.viewportStartOffset
            val center = lazyListState.layoutInfo.viewportStartOffset + (viewportHeight / 2)
            val offset = itemInfo.offset + itemInfo.size / 2 - center
            if (abs(offset) > 10) {
                lazyListState.animateScrollBy(value = offset.toFloat(), animationSpec = tween(durationMillis = 1500))
            }
        }
    }

    LaunchedEffect(scrollTargetIndex, isAutoScrollEnabled, isMetro) {
        if (isMetro && scrollTargetIndex != -1 && isAutoScrollEnabled) {
            deferredCurrentLineIndex = scrollTargetIndex
        }
    }

    LaunchedEffect(currentLineIndex, lastPreviewTime, initialScrollDone, isAutoScrollEnabled, isMetro) {
        if (isMetro || !isSynced) return@LaunchedEffect
        if (isAutoScrollEnabled) {
            if ((currentLineIndex == 0 && shouldScrollToFirstLine) || !initialScrollDone) {
                shouldScrollToFirstLine = false
                performSmoothPageScroll(kotlin.math.max(0, currentLineIndex), 800)
                if (!isAppMinimized) initialScrollDone = true
            } else if (currentLineIndex != -1) {
                deferredCurrentLineIndex = currentLineIndex
                if (isSeeking) {
                    performSmoothPageScroll(kotlin.math.max(0, currentLineIndex), 500)
                } else if ((lastPreviewTime == 0L || currentLineIndex != previousLineIndex) && scrollLyrics) {
                    if (currentLineIndex != previousLineIndex) {
                        performSmoothPageScroll(currentLineIndex, 700)
                    }
                }
            }
        }
        if (currentLineIndex > 0) shouldScrollToFirstLine = true
        previousLineIndex = currentLineIndex
    }

    val toggleSelection: (Int) -> Unit = { index ->
        selectedIndices = if (index in selectedIndices) selectedIndices - index
        else if (selectedIndices.size < MAX_SELECTION) selectedIndices + index
        else selectedIndices
    }
    val onLineClick: (Int, LyricsEntry) -> Unit = { index, item ->
        if (isSelectionModeActive) {
            toggleSelection(index)
        } else if (isSynced && changeLyrics) {
            onSeekTo(item.time.coerceAtLeast(0L))
            if (isMetro) {
                scrollTargetIndex = index
                isAutoScrollEnabled = true
            } else {
                scope.launch { scrollToLineSlowly(index) }
            }
            lastPreviewTime = 0L
        }
    }
    val onLineLongClick: (Int) -> Unit = { index -> toggleSelection(index) }

    val shouldShowResyncButton = !isAutoScrollEnabled && isSynced && !isSelectionModeActive
    val resyncBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val resyncGlass = com.auralis.music.ui.components.rememberPageHeaderGlass(resyncBackdrop)

    val displayedCurrentLineIndex = if (!isAutoScrollEnabled) currentLineIndex
    else if (isSeeking || isSelectionModeActive) deferredCurrentLineIndex else currentLineIndex

    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize().padding(bottom = 12.dp)
    ) {
        val maxHeightDp = maxHeight
        val playbackClock = remember(positionState) { { positionState.value } }
        Box(
            Modifier
                .fillMaxSize()
                .then(if (resyncGlass != null && shouldShowResyncButton) Modifier.layerBackdrop(resyncBackdrop) else Modifier)
        ) {
            CompositionLocalProvider(LocalViviPlaybackClock provides playbackClock) {
                if (isMetro) {
                    MetroLyricsLayout(
                        lines = lines,
                        mergedLyricsList = mergedLyricsList,
                        activeListIndex = activeListIndex,
                        itemHeights = itemHeights,
                        isInitialLayout = isInitialLayout,
                        isAutoScrollEnabled = isAutoScrollEnabled,
                        onUserScroll = {
                            isAutoScrollEnabled = false
                            lastPreviewTime = System.currentTimeMillis()
                        },
                        userManualOffset = { userManualOffset },
                        setUserManualOffset = { userManualOffset = it },
                        flingJob = { flingJob },
                        setFlingJob = { flingJob = it },
                        velocityTracker = velocityTracker,
                        decayAnimSpec = decayAnimSpec,
                        line = { index, item ->
                            val effectivePlaybackPosition = currentPlaybackPosition + offsetMs
                            val pairedMainLineIndex = if (item.isBackground) (index - 1 downTo 0).firstOrNull { lines.getOrNull(it)?.isBackground == false } ?: -1 else -1
                            val isInGapWithMain = if (item.isBackground && pairedMainLineIndex != -1) {
                                effectivePlaybackPosition >= lines[pairedMainLineIndex].time && effectivePlaybackPosition <= item.time
                            } else false
                            val bgVisible = !item.isBackground || activeLineIndices.contains(pairedMainLineIndex) ||
                                activeLineIndices.contains(index) || isInGapWithMain
                            MetroLyricsLine(
                                entry = item,
                                nextEntryTime = lines.getOrNull(index + 1)?.time,
                                effectivePlaybackPosition = effectivePlaybackPosition,
                                lyricsOffset = offsetMs,
                                isSynced = isSynced,
                                isActive = activeLineIndices.contains(index),
                                distanceFromCurrent = abs(index - deferredCurrentLineIndex),
                                lyricsTextPosition = lyricsTextPosition,
                                textColor = textColor,
                                showRomanized = showRomanized,
                                showTranslated = hasActiveTranslations,
                                isAutoScrollActive = isAutoScrollEnabled,
                                isSelectionModeActive = isSelectionModeActive,
                                isSelected = index in selectedIndices,
                                expressiveAccent = expressiveAccent,
                                bgVisible = bgVisible,
                                onClick = { onLineClick(index, item) },
                                onLongClick = { onLineLongClick(index) }
                            )
                        },
                        indicator = { item ->
                            val pos = currentPlaybackPosition + offsetMs
                            LyricsIntervalIndicator(
                                gapStartMs = item.gapStartMs,
                                gapEndMs = item.gapEndMs - 650L,
                                currentPositionMs = pos,
                                visible = isAutoScrollEnabled && pos >= item.gapStartMs && pos <= item.gapEndMs - 650L,
                                color = expressiveAccent,
                                isMetroLyrics = true,
                                onSkip = { onSeekTo(item.gapEndMs) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).wrapContentWidth(Alignment.CenterHorizontally)
                            )
                        },
                        footer = { LyricsCredits(lyrics, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp)) }
                    )
                } else {
                    LazyColumn(
                        state = lazyListState,
                        contentPadding = PaddingValues(top = maxHeightDp / 3, bottom = maxHeightDp / 2),
                        modifier = Modifier
                            .fillMaxSize()
                            .viviFadingEdge(top = 64.dp, bottom = 64.dp)
                            .nestedScroll(remember {
                                object : NestedScrollConnection {
                                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                        if (source == NestedScrollSource.UserInput) isAutoScrollEnabled = false
                                        if (selectedIndices.isEmpty()) lastPreviewTime = System.currentTimeMillis()
                                        return super.onPostScroll(consumed, available, source)
                                    }

                                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                        isAutoScrollEnabled = false
                                        if (selectedIndices.isEmpty()) lastPreviewTime = System.currentTimeMillis()
                                        return super.onPostFling(consumed, available)
                                    }
                                }
                            })
                    ) {
                        val effectivePlaybackPosition = currentPlaybackPosition + offsetMs
                        itemsIndexed(
                            items = lazyItems,
                            key = { lazyIndex, listItem ->
                                when (listItem) {
                                    is LyricsListItem.Line -> "line-${listItem.index}-${listItem.entry.time}"
                                    is LyricsListItem.Indicator -> "gap-${listItem.afterLineIndex}-$lazyIndex"
                                }
                            }
                        ) { _, listItem ->
                            when (listItem) {
                                is LyricsListItem.Indicator -> {
                                    LyricsIntervalIndicator(
                                        gapStartMs = listItem.gapStartMs,
                                        gapEndMs = listItem.gapEndMs,
                                        currentPositionMs = effectivePlaybackPosition,
                                        visible = isSynced && effectivePlaybackPosition in (listItem.gapStartMs - 300L)..listItem.gapEndMs,
                                        color = expressiveAccent,
                                        isMetroLyrics = false,
                                        onSkip = { onSeekTo(listItem.gapEndMs) },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                                    )
                                }
                                is LyricsListItem.Line -> ViviListLine(
                                    index = listItem.index,
                                    item = listItem.entry,
                                    lines = lines,
                                    style = lyricsAnimationStyle,
                                    displayedCurrentLineIndex = displayedCurrentLineIndex,
                                    currentLineIndex = currentLineIndex,
                                    currentPlaybackPosition = currentPlaybackPosition,
                                    effectivePlaybackPosition = effectivePlaybackPosition,
                                    isSynced = isSynced,
                                    isSelected = listItem.index in selectedIndices,
                                    isSelectionModeActive = isSelectionModeActive,
                                    isAutoScrollEnabled = isAutoScrollEnabled,
                                    lyricsTextPosition = lyricsTextPosition,
                                    lyricsTextSize = lyricsTextSize,
                                    lyricsLineSpacing = lyricsLineSpacing,
                                    lyricsStandardBlur = lyricsStandardBlur,
                                    lyricsGlowEffect = lyricsGlowEffect,
                                    showRomanized = showRomanized,
                                    hasActiveTranslations = hasActiveTranslations,
                                    expressiveAccent = expressiveAccent,
                                    textColor = textColor,
                                    onClick = { onLineClick(listItem.index, listItem.entry) },
                                    onLongClick = { onLineLongClick(listItem.index) }
                                )
                            }
                        }
                        item(key = "lyrics_credits") {
                            LyricsCredits(lyrics, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = shouldShowResyncButton,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        ) {
            LyricsResyncButton(resyncGlass) {
                if (isMetro) {
                    scrollTargetIndex = currentLineIndex
                } else {
                    scope.launch { performSmoothPageScroll(currentLineIndex, 1500) }
                }
                isAutoScrollEnabled = true
            }
        }
    }

    // Long-pressed lines open the docked share panel at once, as in the rest of the app.
    com.auralis.music.ui.lyrics.share.LyricSharePanel(
        visible = isSelectionModeActive,
        track = track,
        lines = selectedIndices.sorted().mapNotNull { lines.getOrNull(it)?.text?.takeIf(String::isNotBlank) },
        onDismiss = { selectedIndices = emptySet() }
    )
}

/** Vivi's MetroLyrics layout: absolutely placed lines with a staggered glide and a hand-rolled drag/fling. */
@Composable
private fun MetroLyricsLayout(
    lines: List<LyricsEntry>,
    mergedLyricsList: List<LyricsListItem>,
    activeListIndex: Int,
    itemHeights: MutableMap<Int, Int>,
    isInitialLayout: Boolean,
    isAutoScrollEnabled: Boolean,
    onUserScroll: () -> Unit,
    userManualOffset: () -> Float,
    setUserManualOffset: (Float) -> Unit,
    flingJob: () -> Job?,
    setFlingJob: (Job?) -> Unit,
    velocityTracker: VelocityTracker,
    decayAnimSpec: androidx.compose.animation.core.DecayAnimationSpec<Float>,
    line: @Composable (Int, LyricsEntry) -> Unit,
    indicator: @Composable (LyricsListItem.Indicator) -> Unit,
    footer: @Composable () -> Unit
) {
    val density = LocalDensity.current
    BoxWithConstraints(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()) {
        val maxHeightPx = constraints.maxHeight.toFloat()
        val anchorY = maxHeightPx * LYRICS_ANCHOR_RATIO
        val lineHeightPx = with(density) { LYRICS_ITEM_FALLBACK_HEIGHT_DP.toPx() }
        val constraintLineHeightPx = with(density) { 120.dp.toPx() }
        val gapPx = with(density) { LYRICS_ITEM_GAP_DP.toPx() }
        val indicatorHeightPx = with(density) { 72.dp.toPx() }
        // The footer (credits) rides after the last line like one more item.
        val footerIndex = mergedLyricsList.size
        val heightsSnapshot = itemHeights.toMap()

        fun heightOf(i: Int, fallback: Float): Float {
            val item = mergedLyricsList.getOrNull(i)
            return heightsSnapshot[i]?.toFloat() ?: (if (item is LyricsListItem.Indicator) indicatorHeightPx else fallback)
        }
        fun noGap(item: LyricsListItem?) = (item as? LyricsListItem.Line)?.entry?.isBackground == true || item is LyricsListItem.Indicator

        val positions = remember(heightsSnapshot, activeListIndex, mergedLyricsList) {
            val map = mutableMapOf<Int, Float>()
            if (activeListIndex == -1 || mergedLyricsList.isEmpty()) return@remember map
            map[activeListIndex] = 0f
            var currentY = 0f
            for (i in activeListIndex - 1 downTo 0) {
                currentY -= (heightOf(i, lineHeightPx) + if (noGap(mergedLyricsList[i])) 0f else gapPx)
                map[i] = currentY
            }
            currentY = 0f
            for (i in activeListIndex until footerIndex) {
                val next = mergedLyricsList.getOrNull(i + 1)
                currentY += (heightOf(i, lineHeightPx) + if (noGap(next)) 0f else gapPx)
                map[i + 1] = currentY
            }
            map
        }

        val minOffset = remember(heightsSnapshot, mergedLyricsList, activeListIndex, anchorY) {
            if (mergedLyricsList.isEmpty() || activeListIndex == -1) return@remember 0f
            val totalBelow = (activeListIndex until mergedLyricsList.size - 1).sumOf { i ->
                (heightOf(i, constraintLineHeightPx) + if (noGap(mergedLyricsList[i + 1])) 0f else gapPx).toDouble()
            }.toFloat()
            val lastHeight = heightOf(mergedLyricsList.size - 1, constraintLineHeightPx)
            with(density) { 100.dp.toPx() } - anchorY - totalBelow - lastHeight
        }
        val maxOffset = remember(heightsSnapshot, mergedLyricsList, activeListIndex, maxHeightPx, anchorY) {
            if (mergedLyricsList.isEmpty() || activeListIndex == -1) return@remember 0f
            val totalAbove = (0 until activeListIndex).sumOf { i ->
                (heightOf(i, constraintLineHeightPx) + if (noGap(mergedLyricsList[i])) 0f else gapPx).toDouble()
            }.toFloat()
            maxHeightPx - with(density) { 150.dp.toPx() } - anchorY + totalAbove
        }
        val scrollClampMin = minOf(minOffset, maxOffset)
        val scrollClampMax = maxOf(minOffset, maxOffset)

        LaunchedEffect(scrollClampMin, scrollClampMax) {
            val current = userManualOffset()
            if (current < scrollClampMin || current > scrollClampMax) setUserManualOffset(current.coerceIn(scrollClampMin, scrollClampMax))
        }

        LaunchedEffect(isAutoScrollEnabled, mergedLyricsList.size) {
            if (isAutoScrollEnabled) {
                val start = userManualOffset()
                if (abs(start) < 1f) {
                    setUserManualOffset(0f)
                    return@LaunchedEffect
                }
                val anim = Animatable(start)
                var lastValue = start
                anim.animateTo(0f, tween((abs(start) / 4f).toInt().coerceIn(200, 600), easing = FastOutSlowInEasing)) {
                    setUserManualOffset(userManualOffset() + (value - lastValue))
                    lastValue = value
                }
                setUserManualOffset(0f)
            }
        }

        val currentClampMin by rememberUpdatedState(scrollClampMin)
        val currentClampMax by rememberUpdatedState(scrollClampMax)
        val autoScrollNow by rememberUpdatedState(isAutoScrollEnabled)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .viviFadingEdge(top = LYRICS_FADE_TOP_DP, bottom = LYRICS_FADE_BOTTOM_DP)
                .clipToBounds()
                .pointerInput(Unit) {
                    coroutineScope {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            flingJob()?.cancel()
                            velocityTracker.resetTracking()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            verticalDrag(down.id) { change ->
                                val dragAmount = change.positionChange().y
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val newOffset = userManualOffset() + dragAmount
                                if (newOffset in currentClampMin..currentClampMax) {
                                    setUserManualOffset(newOffset)
                                } else {
                                    val overscroll = if (newOffset < currentClampMin) newOffset - currentClampMin else newOffset - currentClampMax
                                    val friction = 0.35f / (1f + abs(overscroll) / 300f)
                                    setUserManualOffset(
                                        (userManualOffset() + dragAmount * friction).coerceIn(currentClampMin - 150f, currentClampMax + 150f)
                                    )
                                }
                                change.consume()
                                onUserScroll()
                            }
                            if (autoScrollNow) return@awaitEachGesture
                            val velocity = velocityTracker.calculateVelocity().y
                            if (abs(velocity) > 100f) {
                                setFlingJob(launch {
                                    val animState = AnimationState(initialValue = userManualOffset(), initialVelocity = velocity)
                                    animState.animateDecay(decayAnimSpec) {
                                        if (value in currentClampMin..currentClampMax) {
                                            setUserManualOffset(value)
                                        } else {
                                            val target = value.coerceIn(currentClampMin, currentClampMax)
                                            cancelAnimation()
                                            launch {
                                                Animatable(userManualOffset()).animateTo(
                                                    targetValue = target,
                                                    animationSpec = androidx.compose.animation.core.spring(
                                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                                                    )
                                                ) { setUserManualOffset(value) }
                                            }
                                        }
                                    }
                                })
                            } else {
                                val current = userManualOffset()
                                if (current < currentClampMin || current > currentClampMax) {
                                    val target = current.coerceIn(currentClampMin, currentClampMax)
                                    setFlingJob(launch {
                                        Animatable(current).animateTo(target, tween(300, easing = FastOutSlowInEasing)) {
                                            setUserManualOffset(value)
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
        ) {
            (0..footerIndex).forEach { listIndex ->
                val listItem = mergedLyricsList.getOrNull(listIndex)
                key(listItem ?: "footer") {
                    val distance = abs(listIndex - activeListIndex)
                    val targetOffset = anchorY + (positions[listIndex] ?: ((listIndex - activeListIndex) * lineHeightPx))
                    val frozenOffset = remember { mutableFloatStateOf(targetOffset) }
                    LaunchedEffect(isAutoScrollEnabled, targetOffset, isInitialLayout) {
                        if (isAutoScrollEnabled || isInitialLayout) frozenOffset.floatValue = targetOffset
                    }
                    val animatedOffset by animateFloatAsState(
                        targetValue = if (isAutoScrollEnabled) targetOffset else frozenOffset.floatValue,
                        animationSpec = if (isInitialLayout || !isAutoScrollEnabled) snap()
                        else tween(750, (distance * LYRICS_STAGGER_DELAY_PER_DISTANCE).coerceAtMost(LYRICS_STAGGER_DELAY_MAX_MS), FastOutSlowInEasing),
                        label = "lyricStaggeredOffset"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = if (isInitialLayout) 0f else 1f }
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
                                if (listItem != null) itemHeights[listIndex] = placeable.height
                                layout(placeable.width, 0) { placeable.place(0, 0) }
                            }
                            .offset { IntOffset(0, (animatedOffset + userManualOffset()).roundToInt()) }
                    ) {
                        when (listItem) {
                            is LyricsListItem.Line -> line(listItem.index, listItem.entry)
                            is LyricsListItem.Indicator -> indicator(listItem)
                            null -> footer()
                        }
                    }
                }
            }
        }
    }
}

/** One entry of Vivi's list styles (every style but Metro), with its blur, scale, spacing and word animation. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ViviListLine(
    index: Int,
    item: LyricsEntry,
    lines: List<LyricsEntry>,
    style: LyricsAnimationMode,
    displayedCurrentLineIndex: Int,
    currentLineIndex: Int,
    currentPlaybackPosition: Long,
    effectivePlaybackPosition: Long,
    isSynced: Boolean,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    isAutoScrollEnabled: Boolean,
    lyricsTextPosition: LyricsPosition,
    lyricsTextSize: Float,
    lyricsLineSpacing: Float,
    lyricsStandardBlur: Boolean,
    lyricsGlowEffect: Boolean,
    showRomanized: Boolean,
    hasActiveTranslations: Boolean,
    expressiveAccent: Color,
    textColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val currentLineTime = if (displayedCurrentLineIndex in lines.indices) lines[displayedCurrentLineIndex].time else -1L
    val isActiveByIndex = index == displayedCurrentLineIndex
    val isActiveByTime = item.time == currentLineTime && displayedCurrentLineIndex >= 0

    if (style == LyricsAnimationMode.VIVIMUSIC) {
        ViviMusicLyricsLine(
            entry = item,
            nextEntryTime = lines.getOrNull(index + 1)?.time,
            effectivePlaybackPosition = effectivePlaybackPosition,
            isSynced = isSynced,
            isActive = isActiveByIndex || isActiveByTime,
            distanceFromCurrent = abs(index - displayedCurrentLineIndex),
            lyricsTextPosition = lyricsTextPosition,
            textColor = textColor,
            showRomanized = showRomanized,
            textSize = lyricsTextSize,
            lineSpacing = lyricsLineSpacing,
            showTranslated = hasActiveTranslations,
            isAutoScrollActive = isAutoScrollEnabled,
            isSelectionModeActive = isSelectionModeActive,
            isSelected = isSelected,
            expressiveAccent = expressiveAccent,
            onClick = onClick,
            onLongClick = onLongClick
        )
        return
    }

    val itemModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .background(if (isSelected && isSelectionModeActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
        .padding(horizontal = 24.dp, vertical = (8 * lyricsLineSpacing).dp)

    val alpha by animateFloatAsState(
        targetValue = when {
            !isSynced || (isSelectionModeActive && isSelected) -> 1f
            isActiveByIndex || isActiveByTime -> 1f
            else -> 0.5f
        },
        animationSpec = tween(durationMillis = 400)
    )
    val scale by animateFloatAsState(
        targetValue = if (isActiveByIndex || isActiveByTime) 1.05f else 1f,
        animationSpec = tween(durationMillis = 400)
    )
    val targetBlur = if (!lyricsStandardBlur || !isAutoScrollEnabled || (isSelectionModeActive && isSelected) || isActiveByIndex || isActiveByTime) {
        0f
    } else {
        when (abs(index - (if (displayedCurrentLineIndex >= 0) displayedCurrentLineIndex else currentLineIndex))) {
            1, 2 -> 0f
            3 -> 2f
            4 -> 4f
            else -> 6f
        }
    }
    val blurRadius by animateFloatAsState(targetValue = targetBlur, animationSpec = tween(durationMillis = 1000), label = "standard_blur")

    val agentAlignment = when {
        item.isBackground -> Alignment.CenterHorizontally
        item.agent == "v1" -> Alignment.Start
        item.agent == "v2" -> Alignment.End
        item.agent == "v1000" -> Alignment.CenterHorizontally
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.Start
            LyricsPosition.CENTER -> Alignment.CenterHorizontally
            LyricsPosition.RIGHT -> Alignment.End
        }
    }
    val agentTextAlign = when {
        item.isBackground -> TextAlign.Center
        item.agent == "v1" -> TextAlign.Left
        item.agent == "v2" -> TextAlign.Right
        item.agent == "v1000" -> TextAlign.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> TextAlign.Left
            LyricsPosition.CENTER -> TextAlign.Center
            LyricsPosition.RIGHT -> TextAlign.Right
        }
    }
    val positionTextAlign = when (lyricsTextPosition) {
        LyricsPosition.LEFT -> TextAlign.Left
        LyricsPosition.CENTER -> TextAlign.Center
        LyricsPosition.RIGHT -> TextAlign.Right
    }
    val bgScale = if (item.isBackground) 0.85f else 1f
    val cappedLineHeight = (lyricsTextSize * lyricsLineSpacing.coerceAtMost(1.3f)).sp

    Column(
        modifier = itemModifier
            .graphicsLayer {
                this.alpha = if (item.isBackground) alpha * 0.8f else alpha
                this.scaleX = scale * bgScale
                this.scaleY = scale * bgScale
            }
            .blur(blurRadius.dp),
        horizontalAlignment = agentAlignment
    ) {
        val isActiveLine = (isActiveByIndex || isActiveByTime) && isSynced
        val lineColor = if (isActiveLine) {
            if (item.isBackground) expressiveAccent.copy(alpha = 0.85f) else expressiveAccent
        } else {
            expressiveAccent.copy(alpha = if (item.isBackground) 0.5f else 0.7f)
        }
        val romanizedText by item.romanizedTextFlow.collectAsState()
        val mainText = item.text
        val subText = romanizedText
        val hasWordTimings = item.words?.isNotEmpty() == true
        val words = item.words.orEmpty()

        fun wordState(start: Double, end: Double, endExclusive: Boolean): Triple<Long, Long, Pair<Boolean, Boolean>> {
            val s = (start * 1000).toLong()
            val e = (end * 1000).toLong()
            val active = isActiveLine && effectivePlaybackPosition >= s && (if (endExclusive) effectivePlaybackPosition < e else effectivePlaybackPosition <= e)
            return Triple(s, e, active to false)
        }

        when {
            hasWordTimings && style == LyricsAnimationMode.NONE -> {
                val styledText = buildAnnotatedString {
                    words.forEachIndexed { wordIndex, word ->
                        val (s, e, flags) = wordState(word.startTime, word.endTime, false)
                        val isWordActive = flags.first
                        val hasWordPassed = isActiveLine && effectivePlaybackPosition > e
                        val d = e - s
                        val transitionProgress = when {
                            !isActiveLine -> 0f
                            hasWordPassed -> 1f
                            isWordActive && d > 0 -> {
                                val linear = ((effectivePlaybackPosition - s).toFloat() / d).coerceIn(0f, 1f)
                                linear * linear * (3f - 2f * linear)
                            }
                            else -> 0f
                        }
                        val wordAlpha = when {
                            !isActiveLine -> 0.7f
                            hasWordPassed -> 1f
                            isWordActive -> 0.5f + (0.5f * transitionProgress)
                            else -> 0.35f
                        }
                        val wordWeight = when {
                            !isActiveLine || hasWordPassed -> FontWeight.Bold
                            isWordActive -> FontWeight.ExtraBold
                            else -> FontWeight.Medium
                        }
                        withStyle(SpanStyle(color = expressiveAccent.copy(alpha = wordAlpha), fontWeight = wordWeight)) { append(word.text) }
                        if (wordIndex < words.size - 1) append(" ")
                    }
                }
                Text(text = styledText, fontSize = lyricsTextSize.sp, textAlign = agentTextAlign, lineHeight = cappedLineHeight)
            }
            hasWordTimings && style == LyricsAnimationMode.FADE -> {
                val styledText = buildAnnotatedString {
                    words.forEachIndexed { wordIndex, word ->
                        val (s, e, flags) = wordState(word.startTime, word.endTime, false)
                        val isWordActive = flags.first
                        val hasWordPassed = isActiveLine && effectivePlaybackPosition > e
                        val d = e - s
                        val fadeProgress = if (isWordActive && d > 0) {
                            val linear = ((effectivePlaybackPosition - s).toFloat() / d.toFloat()).coerceIn(0f, 1f)
                            linear * linear * (3f - 2f * linear)
                        } else if (hasWordPassed) 1f else 0f
                        val wordAlpha = when {
                            !isActiveLine -> 0.55f
                            hasWordPassed -> 1f
                            isWordActive -> 0.4f + (0.6f * fadeProgress)
                            else -> 0.4f
                        }
                        val wordWeight = when {
                            !isActiveLine || hasWordPassed -> FontWeight.Bold
                            isWordActive -> FontWeight.ExtraBold
                            else -> FontWeight.Medium
                        }
                        val wordShadow = when {
                            isWordActive && fadeProgress > 0.2f -> Shadow(expressiveAccent.copy(alpha = 0.35f * fadeProgress), Offset.Zero, 10f * fadeProgress)
                            hasWordPassed -> Shadow(expressiveAccent.copy(alpha = 0.15f), Offset.Zero, 6f)
                            else -> null
                        }
                        withStyle(SpanStyle(color = expressiveAccent.copy(alpha = wordAlpha), fontWeight = wordWeight, shadow = wordShadow)) { append(word.text) }
                        if (wordIndex < words.size - 1) append(" ")
                    }
                }
                Text(text = styledText, fontSize = lyricsTextSize.sp, textAlign = agentTextAlign, lineHeight = cappedLineHeight)
            }
            hasWordTimings && style == LyricsAnimationMode.GLOW -> {
                val styledText = buildAnnotatedString {
                    words.forEachIndexed { wordIndex, word ->
                        val (s, e, flags) = wordState(word.startTime, word.endTime, false)
                        val isWordActive = flags.first
                        val hasWordPassed = isActiveLine && effectivePlaybackPosition > e
                        val d = e - s
                        val fillProgress = if (isWordActive && d > 0) {
                            val linear = ((effectivePlaybackPosition - s).toFloat() / d).coerceIn(0f, 1f)
                            linear * linear * (3f - 2f * linear)
                        } else if (hasWordPassed) 1f else 0f
                        val glowIntensity = fillProgress * fillProgress
                        val brightness = 0.45f + (0.55f * fillProgress)
                        val wordColor = when {
                            !isActiveLine -> expressiveAccent.copy(alpha = 0.5f)
                            isWordActive || hasWordPassed -> expressiveAccent.copy(alpha = brightness)
                            else -> expressiveAccent.copy(alpha = 0.35f)
                        }
                        val wordWeight = when {
                            !isActiveLine -> FontWeight.Bold
                            isWordActive -> FontWeight.ExtraBold
                            hasWordPassed -> FontWeight.Bold
                            else -> FontWeight.Medium
                        }
                        val wordShadow = if (isWordActive && glowIntensity > 0.05f) {
                            Shadow(expressiveAccent.copy(alpha = 0.5f + (0.3f * glowIntensity)), Offset.Zero, 16f + (12f * glowIntensity))
                        } else if (hasWordPassed) {
                            Shadow(expressiveAccent.copy(alpha = 0.25f), Offset.Zero, 8f)
                        } else null
                        withStyle(SpanStyle(color = wordColor, fontWeight = wordWeight, shadow = wordShadow)) { append(word.text) }
                        if (wordIndex < words.size - 1) append(" ")
                    }
                }
                Text(text = styledText, fontSize = lyricsTextSize.sp, textAlign = agentTextAlign, lineHeight = cappedLineHeight)
            }
            hasWordTimings && style == LyricsAnimationMode.SLIDE -> {
                val styledText = buildAnnotatedString {
                    words.forEachIndexed { wordIndex, word ->
                        val (s, e, flags) = wordState(word.startTime, word.endTime, true)
                        val isWordActive = flags.first
                        val hasWordPassed = (isActiveLine && effectivePlaybackPosition >= e) || (!isActiveLine && item.time < currentLineTime)
                        val d = e - s
                        if (isWordActive && d > 0) {
                            val timeElapsed = effectivePlaybackPosition - s
                            val fillProgress = (timeElapsed.toFloat() / d.toFloat()).coerceIn(0f, 1f)
                            val breatheValue = (timeElapsed % 3000) / 3000f
                            val breatheEffect = (kotlin.math.sin(breatheValue * Math.PI.toFloat() * 2f) * 0.03f).coerceIn(0f, 0.03f)
                            val glowIntensity = (0.3f + fillProgress * 0.7f + breatheEffect).coerceIn(0f, 1.1f)
                            val slideBrush = Brush.horizontalGradient(
                                0.0f to expressiveAccent,
                                (fillProgress * 0.95f).coerceIn(0f, 1f) to expressiveAccent,
                                fillProgress to expressiveAccent.copy(alpha = 0.9f),
                                (fillProgress + 0.02f).coerceIn(0f, 1f) to expressiveAccent.copy(alpha = 0.5f),
                                (fillProgress + 0.08f).coerceIn(0f, 1f) to expressiveAccent.copy(alpha = 0.35f),
                                1.0f to expressiveAccent.copy(alpha = 0.35f)
                            )
                            withStyle(SpanStyle(
                                brush = slideBrush,
                                fontWeight = FontWeight.ExtraBold,
                                shadow = Shadow(expressiveAccent.copy(alpha = 0.4f * glowIntensity), Offset.Zero, 14f + (4f * fillProgress))
                            )) { append(word.text) }
                        } else if (hasWordPassed && isActiveLine) {
                            withStyle(SpanStyle(color = expressiveAccent, fontWeight = FontWeight.Bold, shadow = Shadow(expressiveAccent.copy(alpha = 0.4f), Offset.Zero, 12f))) {
                                append(word.text)
                            }
                        } else {
                            val wordColor = if (!isActiveLine) lineColor else expressiveAccent.copy(alpha = 0.35f)
                            withStyle(SpanStyle(color = wordColor, fontWeight = FontWeight.Medium)) { append(word.text) }
                        }
                        if (wordIndex < words.size - 1) append(" ")
                    }
                }
                Text(text = styledText, fontSize = lyricsTextSize.sp, textAlign = agentTextAlign, lineHeight = (lyricsTextSize * lyricsLineSpacing).sp)
            }
            hasWordTimings && style == LyricsAnimationMode.KARAOKE -> {
                val styledText = buildAnnotatedString {
                    words.forEachIndexed { wordIndex, word ->
                        val (s, e, flags) = wordState(word.startTime, word.endTime, true)
                        val isWordActive = flags.first
                        val hasWordPassed = (isActiveLine && effectivePlaybackPosition >= e) || (!isActiveLine && item.time < currentLineTime)
                        val d = e - s
                        if (isWordActive && d > 0) {
                            val linearProgress = ((effectivePlaybackPosition - s).toFloat() / d.toFloat()).coerceIn(0f, 1f)
                            val fillProgress = linearProgress * linearProgress * (3f - 2f * linearProgress)
                            val glowIntensity = fillProgress * fillProgress
                            val wordBrush = Brush.horizontalGradient(
                                0.0f to expressiveAccent.copy(alpha = 0.4f),
                                (fillProgress * 0.6f).coerceIn(0f, 1f) to expressiveAccent.copy(alpha = 0.75f),
                                (fillProgress * 0.85f).coerceIn(0f, 1f) to expressiveAccent.copy(alpha = 0.95f),
                                fillProgress to expressiveAccent,
                                (fillProgress + 0.03f).coerceIn(0f, 1f) to expressiveAccent.copy(alpha = 0.85f),
                                (fillProgress + 0.1f).coerceIn(0f, 1f) to expressiveAccent.copy(alpha = 0.5f),
                                1.0f to expressiveAccent.copy(alpha = if (fillProgress >= 0.9f) 0.95f else 0.4f)
                            )
                            withStyle(SpanStyle(
                                brush = wordBrush,
                                fontWeight = FontWeight.ExtraBold,
                                shadow = Shadow(expressiveAccent.copy(alpha = 0.5f + (0.3f * glowIntensity)), Offset.Zero, 16f + (12f * glowIntensity))
                            )) { append(word.text) }
                        } else if (hasWordPassed && isActiveLine) {
                            withStyle(SpanStyle(color = expressiveAccent, fontWeight = FontWeight.Bold, shadow = Shadow(expressiveAccent.copy(alpha = 0.25f), Offset.Zero, 8f))) {
                                append(word.text)
                            }
                        } else {
                            val wordColor = if (!isActiveLine) lineColor else expressiveAccent.copy(alpha = 0.4f)
                            withStyle(SpanStyle(color = wordColor, fontWeight = FontWeight.Medium)) { append(word.text) }
                        }
                        if (wordIndex < words.size - 1) append(" ")
                    }
                }
                Text(text = styledText, fontSize = lyricsTextSize.sp, textAlign = agentTextAlign, lineHeight = cappedLineHeight)
            }
            hasWordTimings && style == LyricsAnimationMode.APPLE_MUSIC -> {
                val styledText = buildAnnotatedString {
                    words.forEachIndexed { wordIndex, word ->
                        val (s, e, flags) = wordState(word.startTime, word.endTime, true)
                        val isWordActive = flags.first
                        val hasWordPassed = (isActiveLine && effectivePlaybackPosition >= e) || (!isActiveLine && item.time < currentLineTime)
                        val d = e - s
                        val rawProgress = if (isWordActive && d > 0) ((effectivePlaybackPosition - s).toFloat() / d).coerceIn(0f, 1f)
                        else if (hasWordPassed) 1f else 0f
                        val smoothProgress = rawProgress * rawProgress * (3f - 2f * rawProgress)
                        val wordAlpha = when {
                            !isActiveLine -> 0.55f
                            hasWordPassed -> 1f
                            isWordActive -> 0.55f + (0.45f * smoothProgress)
                            else -> 0.4f
                        }
                        val wordWeight = when {
                            !isActiveLine -> FontWeight.SemiBold
                            hasWordPassed -> FontWeight.Bold
                            isWordActive -> FontWeight.ExtraBold
                            else -> FontWeight.Normal
                        }
                        val glowIntensity = smoothProgress * smoothProgress
                        val wordShadow = when {
                            isWordActive -> Shadow(expressiveAccent.copy(alpha = 0.2f + (0.4f * glowIntensity)), Offset.Zero, 10f + (12f * glowIntensity))
                            hasWordPassed && isActiveLine -> Shadow(expressiveAccent.copy(alpha = 0.2f), Offset.Zero, 8f)
                            else -> null
                        }
                        withStyle(SpanStyle(color = expressiveAccent.copy(alpha = wordAlpha), fontWeight = wordWeight, shadow = wordShadow)) { append(word.text) }
                        if (wordIndex < words.size - 1) append(" ")
                    }
                }
                Text(text = styledText, fontSize = lyricsTextSize.sp, textAlign = agentTextAlign, lineHeight = cappedLineHeight)
            }
            style == LyricsAnimationMode.APPLE_MUSIC_V2 -> {
                val nextEntryTime = lines.getOrNull(index + 1)?.time
                val duration = remember(item.time, nextEntryTime) { if (nextEntryTime != null) nextEntryTime - item.time else 4000L }
                val activeDuration = (duration * 0.95).toLong().coerceAtLeast(300L)
                val wordData = remember(item.text, item.words, activeDuration) {
                    if (item.words?.isNotEmpty() == true) {
                        item.words.map { word ->
                            val wordStart = ((word.startTime * 1000).toLong() - item.time).coerceAtLeast(0L)
                            val wordEnd = ((word.endTime * 1000).toLong() - item.time).coerceAtLeast(wordStart + 50L)
                            Triple(word.text, wordStart, wordEnd)
                        }
                    } else {
                        val split = item.text.split(" ").filter { it.isNotEmpty() }
                        if (split.isEmpty()) {
                            listOf(Triple(item.text, 0L, activeDuration))
                        } else {
                            val totalChars = item.text.length
                            var accumulatedTime = 0L
                            split.mapIndexed { wordIndex, word ->
                                val charCount = if (wordIndex < split.lastIndex) word.length + 1 else word.length
                                val wordStart = accumulatedTime
                                val wordDur = if (totalChars > 0) (activeDuration * charCount.toFloat() / totalChars).toLong() else activeDuration
                                accumulatedTime += wordDur
                                Triple(word, wordStart, wordStart + wordDur)
                            }
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = when (agentAlignment) {
                        Alignment.CenterHorizontally -> Arrangement.Center
                        Alignment.End -> Arrangement.End
                        else -> Arrangement.Start
                    },
                    verticalArrangement = Arrangement.spacedBy(
                        with(LocalDensity.current) { (lyricsTextSize * (lyricsLineSpacing.coerceAtMost(1.3f) - 1f)).sp.toDp() }
                    )
                ) {
                    wordData.forEachIndexed { wordIndex, (wordText, startRelative, endRelative) ->
                        val lineRelTime = (effectivePlaybackPosition - item.time).coerceAtLeast(0L)
                        val wordDuration = endRelative - startRelative
                        Row {
                            wordText.forEachIndexed { charIndex, char ->
                                val charDuration = if (wordText.isNotEmpty()) wordDuration / wordText.length else 0L
                                val charStart = startRelative + (charIndex * charDuration)
                                val charEnd = charStart + charDuration
                                val charProgress = when {
                                    !isActiveLine -> 0f
                                    lineRelTime >= charEnd -> 1f
                                    lineRelTime < charStart -> 0f
                                    else -> if (charDuration <= 0L) 1f else (lineRelTime - charStart).toFloat() / charDuration
                                }
                                Text(
                                    text = char.toString(),
                                    fontSize = lyricsTextSize.sp,
                                    color = expressiveAccent.copy(alpha = if (!isActiveLine) 1f else if (charProgress >= 1f) 1f else 0.3f + (0.7f * charProgress)),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                )
                            }
                            if (wordIndex < wordData.size - 1) {
                                Text(text = " ", fontSize = lyricsTextSize.sp, letterSpacing = (-0.5).sp)
                            }
                        }
                    }
                    if (hasActiveTranslations) {
                        val translatedText by item.translatedTextFlow.collectAsState()
                        translatedText?.let { translated ->
                            Text(
                                text = translated,
                                fontSize = (lyricsTextSize * 0.7f).sp,
                                color = expressiveAccent.copy(alpha = if (isActiveLine) 0.8f else 0.3f),
                                textAlign = agentTextAlign,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                lineHeight = (lyricsTextSize * 0.7f * lyricsLineSpacing.coerceAtMost(1.3f)).sp
                            )
                        }
                    }
                }
            }
            style == LyricsAnimationMode.LYRICS_V2_FLUID -> {
                LyricsLineV2(
                    entry = item,
                    isActive = isActiveLine,
                    isPast = !isActiveLine && item.time < currentPlaybackPosition,
                    effectivePlaybackPosition = effectivePlaybackPosition + 150L,
                    expressiveAccent = expressiveAccent,
                    inactiveAlpha = 0.35f,
                    baseFontSize = lyricsTextSize,
                    lineHeight = lyricsTextSize * lyricsLineSpacing.coerceAtMost(1.3f),
                    showTranslated = hasActiveTranslations,
                    agentAlignment = agentAlignment,
                    agentTextAlign = agentTextAlign
                )
            }
            isActiveLine && lyricsGlowEffect -> {
                val fillProgress = remember { Animatable(0f) }
                val pulseProgress = remember { Animatable(0f) }
                LaunchedEffect(index) {
                    fillProgress.snapTo(0f)
                    fillProgress.animateTo(1f, tween(durationMillis = 1200, easing = FastOutSlowInEasing))
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        pulseProgress.animateTo(1f, tween(durationMillis = 3000, easing = LinearEasing))
                        pulseProgress.snapTo(0f)
                    }
                }
                val fill = fillProgress.value
                val pulse = pulseProgress.value
                val pulseEffect = (kotlin.math.sin(pulse * Math.PI.toFloat()) * 0.15f).coerceIn(0f, 0.15f)
                val glowIntensity = (fill + pulseEffect).coerceIn(0f, 1.2f)
                val glowBrush = Brush.horizontalGradient(
                    0.0f to expressiveAccent.copy(alpha = 0.3f),
                    (fill * 0.7f).coerceIn(0f, 1f) to expressiveAccent.copy(alpha = 0.9f),
                    fill to expressiveAccent,
                    (fill + 0.1f).coerceIn(0f, 1f) to expressiveAccent.copy(alpha = 0.7f),
                    1.0f to expressiveAccent.copy(alpha = if (fill >= 1f) 1f else 0.3f)
                )
                val styledText = buildAnnotatedString {
                    withStyle(SpanStyle(
                        shadow = Shadow(expressiveAccent.copy(alpha = 0.8f * glowIntensity), Offset.Zero, 28f * (1f + pulseEffect)),
                        brush = glowBrush
                    )) { append(mainText) }
                }
                val bounceScale = if (fill < 0.3f) 1f + (kotlin.math.sin(fill * 3.33f * Math.PI.toFloat()) * 0.03f) else 1f
                Text(
                    text = styledText,
                    fontSize = lyricsTextSize.sp,
                    textAlign = agentTextAlign,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = cappedLineHeight,
                    modifier = Modifier.graphicsLayer { scaleX = bounceScale; scaleY = bounceScale }
                )
            }
            isActiveLine -> {
                Text(text = mainText, fontSize = lyricsTextSize.sp, color = expressiveAccent, textAlign = agentTextAlign, fontWeight = FontWeight.ExtraBold, lineHeight = cappedLineHeight)
            }
            else -> {
                Text(text = mainText, fontSize = lyricsTextSize.sp, color = lineColor, textAlign = agentTextAlign, fontWeight = FontWeight.Bold, lineHeight = cappedLineHeight)
            }
        }

        if (showRomanized) {
            subText?.let { text ->
                Text(
                    text = text,
                    fontSize = 18.sp,
                    color = expressiveAccent.copy(alpha = 0.6f),
                    textAlign = positionTextAlign,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (hasActiveTranslations && style != LyricsAnimationMode.LYRICS_V2_FLUID && style != LyricsAnimationMode.APPLE_MUSIC_V2) {
            val translatedText by item.translatedTextFlow.collectAsState()
            translatedText?.let { translated ->
                Text(
                    text = translated,
                    fontSize = (lyricsTextSize * 0.7f).sp,
                    color = expressiveAccent.copy(alpha = 0.8f),
                    textAlign = positionTextAlign,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                    lineHeight = (lyricsTextSize * 0.7f * lyricsLineSpacing.coerceAtMost(1.3f)).sp
                )
            }
        }
    }
}
