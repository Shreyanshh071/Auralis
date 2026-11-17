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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import com.auralis.music.ui.lyrics.LyricsScrollGlide
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    modifier: Modifier = Modifier,
    /** Where the active line rests, as a fraction of the height (Ambient mode uses the middle). */
    readingFocusFraction: Float = LYRICS_ANCHOR_RATIO
) {
    val appearance = LocalAppearanceSettings.current
    val lyricsAnimationStyle = LyricsAnimationMode.fromDisplayName(appearance.lyricsAnimation)
    val isMetro = lyricsAnimationStyle == LyricsAnimationMode.METRO_LYRICS
    val lyricsTextPosition = LyricsPosition.fromSetting(appearance.lyricsTextPosition)
    val changeLyrics = appearance.changeLyricsOnTap
    val lyricsGlowEffect = appearance.enableGlowingLyricsEffect
    val lyricsTextSize = appearance.lyricsTextSize
    val lyricsLineSpacing = appearance.lyricsLineSpacing
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

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

    // ── Vivi's line timing: which line is current, per displayed frame ──
    var currentLineIndex by remember { mutableIntStateOf(-1) }
    var currentPlaybackPosition by remember { mutableLongStateOf(0L) }
    var activeLineIndices by remember { mutableStateOf(emptySet<Int>()) }
    val offsetState = rememberUpdatedState(offsetMs)
    LaunchedEffect(lines, isSynced) {
        if (!isSynced) {
            currentLineIndex = -1
            activeLineIndices = emptySet()
            return@LaunchedEffect
        }
        while (isActive) {
            withFrameNanos { }
            val position = positionState.value
            currentPlaybackPosition = position
            val effectivePosition = position + offsetState.value
            currentLineIndex = findCurrentLineIndex(lines, effectivePosition)
            // A background vocal keeps the main line it belongs to active (Metro's paired lines).
            val active = findActiveLineIndices(lines, effectivePosition).toMutableSet()
            for (i in active.toList()) {
                if (lines.getOrNull(i)?.isBackground == true) {
                    (i - 1 downTo 0).firstOrNull { lines.getOrNull(it)?.isBackground == false }?.let(active::add)
                }
            }
            activeLineIndices = active
        }
    }

    // ── The list: sung lines only (no blank rows to scroll into) and interlude indicators ──
    val lazyItems = remember(lines) {
        buildList<LyricsListItem> {
            lines.forEachIndexed { i, entry ->
                if (entry.text.isNotBlank()) add(LyricsListItem.Line(i, entry))
                if (isSynced && i < lines.size - 1) {
                    val nextStart = lines[i + 1].time
                    val currentEnd = when {
                        !entry.words.isNullOrEmpty() -> (entry.words.last().endTime * 1000).toLong()
                        entry.text.isBlank() -> entry.time
                        else -> null
                    }
                    if (currentEnd != null && currentEnd < nextStart && nextStart - currentEnd > 4000L) {
                        add(LyricsListItem.Indicator(i, nextStart - currentEnd, currentEnd, nextStart, lines[i + 1].agent))
                    }
                }
            }
        }
    }
    // The list row a lyric line sits in; a blank line (or the head) maps to the next sung line.
    val lazyIndexOfLine = remember(lines, lazyItems) {
        val map = IntArray(lines.size) { -1 }
        lazyItems.forEachIndexed { lazyIndex, item -> if (item is LyricsListItem.Line) map[item.index] = lazyIndex }
        var next = lazyItems.indices.lastOrNull() ?: -1
        for (i in lines.indices.reversed()) {
            if (map[i] >= 0) next = map[i] else map[i] = next
        }
        map
    }
    val activeLazyIndexState = remember(lazyItems, lazyIndexOfLine) {
        derivedStateOf {
            if (!isSynced || lazyItems.isEmpty()) return@derivedStateOf -1
            val position = currentPlaybackPosition + offsetMs
            lazyItems.indexOfFirst { it is LyricsListItem.Indicator && position in it.gapStartMs..it.gapEndMs }
                .takeIf { it >= 0 }
                ?: currentLineIndex.takeIf { it in lines.indices }?.let { lazyIndexOfLine[it] }?.takeIf { it >= 0 }
                ?: 0
        }
    }

    // ── Auralis's scrolling ──
    val listState = rememberLazyListState()
    var isAutoScrollEnabled by rememberSaveable { mutableStateOf(appearance.autoScrollLyrics) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // Long-press picks up to five lines for the share panel.
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val isSelectionModeActive = selectedIndices.isNotEmpty()

    // Detects manual scrolling across all styles, even when line rows consume the clicks.
    val lyricsNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!isProgrammaticScroll && source == NestedScrollSource.UserInput && abs(available.y) > 0.5f) {
                    if (selectedIndices.isEmpty()) {
                        isAutoScrollEnabled = false
                        isUserInteracting = true
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!isProgrammaticScroll && source == NestedScrollSource.UserInput &&
                    (abs(consumed.y) > 0.5f || abs(available.y) > 0.5f) && selectedIndices.isEmpty()
                ) isAutoScrollEnabled = false
                return super.onPostScroll(consumed, available, source)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                isUserInteracting = false
                return super.onPostFling(consumed, available)
            }
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) isUserInteracting = false
    }

    // The tab stays composed while hidden, so leaving it has to end a selection itself.
    val lyricsOnScreen = com.auralis.music.ui.theme.LocalPreviewMotionActive.current != false
    val lyricsOnScreenNow = rememberUpdatedState(lyricsOnScreen)
    LaunchedEffect(lyricsOnScreen) { if (!lyricsOnScreen) selectedIndices = emptySet() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The active line rests at Auralis's reading point; the list's padding lets the first line
        // reach it and no further, so there is no empty space to drag into.
        val targetCenterFraction = readingFocusFraction.coerceIn(0.2f, 0.6f)
        val topPaddingDp = (maxHeight * targetCenterFraction - 40.dp).coerceAtLeast(32.dp)
        val bottomPaddingDp = (maxHeight * (1f - targetCenterFraction) + 60.dp).coerceAtLeast(180.dp)

        var hasInitialCentered by rememberSaveable(track?.id) { mutableStateOf(false) }
        var lastCenteredIndex by rememberSaveable(track?.id) { mutableIntStateOf(-1) }
        // Not saveable: on returning to the tab the first centering snaps while the tab fades in.
        var centeredSinceComposed by remember { mutableStateOf(false) }

        LaunchedEffect(track?.id, lyrics) {
            hasInitialCentered = false
            lastCenteredIndex = -1
            isAutoScrollEnabled = appearance.autoScrollLyrics
        }
        LaunchedEffect(appearance.autoScrollLyrics) { isAutoScrollEnabled = appearance.autoScrollLyrics }

        val lyricsGlide = remember { LyricsScrollGlide() }

        // Centres a row at the reading point: an off-screen target is glided to from an estimate
        // of the line height, then corrected exactly.
        val centerActiveLine: suspend (Int, Boolean) -> Unit = { targetIndex, animate ->
            if (targetIndex in lazyItems.indices) {
                isProgrammaticScroll = true
                try {
                    var layoutInfo = listState.layoutInfo
                    var viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                    if (viewportHeight <= 0) {
                        withFrameMillis { }
                        layoutInfo = listState.layoutInfo
                        viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                    }
                    if (viewportHeight > 0) {
                        val targetCenterY = layoutInfo.viewportStartOffset + (viewportHeight * targetCenterFraction)
                        var itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                        if (itemInfo == null) {
                            val visibleNow = layoutInfo.visibleItemsInfo
                            if (animate && visibleNow.isNotEmpty()) {
                                val avgItem = visibleNow.map { it.size }.average().toFloat() + layoutInfo.mainAxisItemSpacing
                                val anchor = visibleNow.first()
                                val estimatedCenter = anchor.offset + (targetIndex - anchor.index) * avgItem + avgItem / 2f
                                val estimatedDelta = estimatedCenter - targetCenterY
                                val glideMs = (350 + abs(estimatedDelta) / 6f).toInt().coerceIn(400, 900)
                                listState.animateScrollBy(estimatedDelta, tween(durationMillis = glideMs, easing = FastOutSlowInEasing))
                            } else {
                                val currentFirst = listState.firstVisibleItemIndex
                                if (abs(targetIndex - currentFirst) > 8) {
                                    listState.scrollToItem(if (targetIndex > currentFirst) (targetIndex - 2).coerceAtLeast(0)
                                        else (targetIndex + 2).coerceAtMost(lazyItems.size - 1))
                                } else {
                                    listState.scrollToItem(targetIndex)
                                }
                            }
                            withFrameMillis { }
                            layoutInfo = listState.layoutInfo
                            itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                        }
                        if (itemInfo != null) {
                            val scrollDelta = itemInfo.offset + (itemInfo.size / 2f) - targetCenterY
                            if (abs(scrollDelta) > 1.5f) {
                                if (animate && lyricsOnScreenNow.value) lyricsGlide.glide(listState, scrollDelta)
                                else listState.scrollBy(scrollDelta)
                            }
                        }
                    }
                } finally {
                    isProgrammaticScroll = false
                }
            }
        }

        val resyncLyrics: () -> Unit = {
            if (appearance.autoScrollLyrics) isAutoScrollEnabled = true
            isUserInteracting = false
            val activeIndex = activeLazyIndexState.value.takeIf { it in lazyItems.indices } ?: 0
            if (lazyItems.isNotEmpty()) {
                // Marked handled first so the follow-the-line effect doesn't start a second scroll.
                lastCenteredIndex = activeIndex
                coroutineScope.launch { centerActiveLine(activeIndex, true) }
            }
        }

        // With "Auto scroll lyrics" on, a manual scroll only pauses following: once the list has
        // been left alone for 1.5s (no drag, no fling, not selecting), the active line is centred again.
        LaunchedEffect(appearance.autoScrollLyrics, isAutoScrollEnabled, listState.isScrollInProgress, selectedIndices.isEmpty(), isSynced) {
            if (!appearance.autoScrollLyrics || !isSynced || isAutoScrollEnabled ||
                listState.isScrollInProgress || selectedIndices.isNotEmpty()
            ) return@LaunchedEffect
            delay(1_500L)
            resyncLyrics()
        }

        // Follows the active line (or interlude) with Auralis's glide.
        LaunchedEffect(activeLazyIndexState, isSynced, isAutoScrollEnabled, lazyItems) {
            if (!isSynced || !isAutoScrollEnabled) return@LaunchedEffect
            snapshotFlow { Triple(activeLazyIndexState.value, isUserInteracting, selectedIndices.isNotEmpty()) }
                .distinctUntilChanged()
                .collectLatest { (activeIndex, interacting, selecting) ->
                    if (interacting || selecting || activeIndex !in lazyItems.indices) return@collectLatest
                    if (hasInitialCentered && activeIndex == lastCenteredIndex) return@collectLatest
                    centerActiveLine(activeIndex, hasInitialCentered && centeredSinceComposed)
                    lastCenteredIndex = activeIndex
                    hasInitialCentered = true
                    centeredSinceComposed = true
                }
        }

        val isCurrentLineCentered by remember(listState, lazyItems) {
            derivedStateOf {
                if (isUserInteracting || listState.isScrollInProgress) return@derivedStateOf false
                val activeIdx = activeLazyIndexState.value
                if (activeIdx !in lazyItems.indices) return@derivedStateOf true
                if (activeIdx == 0 && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 24) return@derivedStateOf true
                val layoutInfo = listState.layoutInfo
                val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx } ?: return@derivedStateOf false
                val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                if (viewportHeight <= 0) return@derivedStateOf false
                val targetCenterY = layoutInfo.viewportStartOffset + (viewportHeight * targetCenterFraction)
                abs(item.offset + item.size / 2f - targetCenterY) < 100f
            }
        }
        val shouldShowResyncButton = isSynced && !isSelectionModeActive &&
            (if (appearance.autoScrollLyrics) !isAutoScrollEnabled else !isCurrentLineCentered)
        val resyncBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
        val resyncGlass = com.auralis.music.ui.components.rememberPageHeaderGlass(resyncBackdrop)

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
                if (appearance.autoScrollLyrics) isAutoScrollEnabled = true
                isUserInteracting = false
            }
        }

        val topFadePx = with(density) { 36.dp.toPx() }
        val bottomFadePx = with(density) { 90.dp.toPx() }
        val playbackClock = remember(positionState) { { positionState.value } }

        CompositionLocalProvider(LocalViviPlaybackClock provides playbackClock) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .then(if (resyncGlass != null && shouldShowResyncButton) Modifier.layerBackdrop(resyncBackdrop) else Modifier)
                    .fillMaxSize()
                    .nestedScroll(lyricsNestedScrollConnection)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = topFadePx),
                            blendMode = BlendMode.DstIn
                        )
                        drawRect(
                            brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent,
                                startY = size.height - bottomFadePx, endY = size.height),
                            blendMode = BlendMode.DstIn
                        )
                    },
                contentPadding = PaddingValues(
                    top = if (isSynced) topPaddingDp else 36.dp,
                    bottom = if (isSynced) bottomPaddingDp else 220.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                val effectivePlaybackPosition = currentPlaybackPosition + offsetMs
                itemsIndexed(
                    items = lazyItems,
                    key = { lazyIndex, item ->
                        when (item) {
                            is LyricsListItem.Line -> "line-${item.index}-${item.entry.time}"
                            is LyricsListItem.Indicator -> "gap-${item.afterLineIndex}-$lazyIndex"
                        }
                    }
                ) { _, listItem ->
                    when (listItem) {
                        is LyricsListItem.Indicator -> LyricsIntervalIndicator(
                            gapStartMs = listItem.gapStartMs,
                            gapEndMs = listItem.gapEndMs,
                            currentPositionMs = effectivePlaybackPosition,
                            visible = isSynced && effectivePlaybackPosition in (listItem.gapStartMs - 300L)..listItem.gapEndMs,
                            color = expressiveAccent,
                            isMetroLyrics = isMetro,
                            onSkip = { onSeekTo(listItem.gapEndMs) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        is LyricsListItem.Line -> {
                            val index = listItem.index
                            val item = listItem.entry
                            // Auralis's line spacing: a gap after each line, a small one when the next is its background vocal.
                            val next = lines.getOrNull(index + 1)
                            val pairedWithNext = next != null && (next.isBackground || (isSynced && next.time == item.time))
                            val bottomSpacing = when {
                                index == lines.lastIndex -> 0.dp
                                pairedWithNext -> 2.dp
                                else -> (14 * lyricsLineSpacing).dp
                            }
                            Box(Modifier.fillMaxWidth().padding(bottom = bottomSpacing)) {
                                when (lyricsAnimationStyle) {
                                    LyricsAnimationMode.METRO_LYRICS -> {
                                        val pairedMainLineIndex = if (item.isBackground)
                                            (index - 1 downTo 0).firstOrNull { lines.getOrNull(it)?.isBackground == false } ?: -1 else -1
                                        val inGapWithMain = item.isBackground && pairedMainLineIndex != -1 &&
                                            effectivePlaybackPosition >= lines[pairedMainLineIndex].time && effectivePlaybackPosition <= item.time
                                        MetroLyricsLine(
                                            entry = item,
                                            nextEntryTime = lines.getOrNull(index + 1)?.time,
                                            effectivePlaybackPosition = effectivePlaybackPosition,
                                            lyricsOffset = offsetMs,
                                            isSynced = isSynced,
                                            isActive = activeLineIndices.contains(index),
                                            distanceFromCurrent = abs(index - currentLineIndex),
                                            lyricsTextPosition = lyricsTextPosition,
                                            textColor = textColor,
                                            showRomanized = showRomanized,
                                            showTranslated = hasActiveTranslations,
                                            isAutoScrollActive = isAutoScrollEnabled,
                                            isSelectionModeActive = isSelectionModeActive,
                                            isSelected = index in selectedIndices,
                                            expressiveAccent = expressiveAccent,
                                            bgVisible = !item.isBackground || activeLineIndices.contains(pairedMainLineIndex) ||
                                                activeLineIndices.contains(index) || inGapWithMain,
                                            onClick = { onLineClick(index, item) },
                                            onLongClick = { toggleSelection(index) }
                                        )
                                    }
                                    else -> ViviListLine(
                                        index = index,
                                        item = item,
                                        lines = lines,
                                        style = lyricsAnimationStyle,
                                        displayedCurrentLineIndex = currentLineIndex,
                                        currentLineIndex = currentLineIndex,
                                        currentPlaybackPosition = currentPlaybackPosition,
                                        effectivePlaybackPosition = effectivePlaybackPosition,
                                        isSynced = isSynced,
                                        isSelected = index in selectedIndices,
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
                                        onClick = { onLineClick(index, item) },
                                        onLongClick = { toggleSelection(index) }
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "lyrics_credits") {
                    LyricsCredits(lyrics, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = shouldShowResyncButton,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        ) {
            LyricsResyncButton(resyncGlass, resyncLyrics)
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
        // Auralis's row spacing: the gap between lines comes from the list, not from the row.
        .padding(horizontal = 4.dp, vertical = if (item.isBackground) 1.dp else (3 * (lyricsLineSpacing / 1.3f).coerceIn(0.85f, 1.4f)).dp)

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
