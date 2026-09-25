package com.auralis.music.ui.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap

import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsMode
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.lyrics.renderers.AppleMusicLyricsLine
import com.auralis.music.ui.lyrics.renderers.BasicWordLyricsLine
import com.auralis.music.ui.lyrics.renderers.LyricsV2FluidLine
import com.auralis.music.ui.lyrics.renderers.MetroLyricsLine
import com.auralis.music.ui.lyrics.renderers.OceanWaveLyricsLine
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.dynamicPalette
import com.auralis.music.ui.theme.motionTween
import androidx.compose.runtime.withFrameMillis

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * High-performance, smooth 60fps Line-Synced Lyrics View:
 * - Immediate centering and fluid auto-scrolling to active lyric line
 * - Bold, vibrant active line highlighting with soft glowing typography
 * - Tap-to-seek and human drag gesture detection with auto-resume
 * - Multi-line selection and beautiful lyric card sharing
 * - Instrumental intro countdown and rhythm orbs
 * - AI lyric translation display support
 *
 * The playback position arrives as a [State] rather than a `Long` on purpose.
 * A `Long` parameter forces this whole composable — and everything above it —
 * to recompose on every clock tick, roughly 60 times a second. Held as state,
 * the value is read only where it is used, and each read site invalidates on its
 * own terms: the active index through a `derivedStateOf` that changes once per
 * lyric line, the scroll driver through a `snapshotFlow` outside composition.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SyncedLyricsView(
    lyrics: LyricsData?,
    positionState: State<Long>,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    lyricsMode: LyricsMode = LyricsMode.CINEMA,
    offsetMs: Long = 0,
    onOffsetChange: ((Long) -> Unit)? = null,
    onSearchManually: (() -> Unit)? = null,
    track: com.auralis.music.domain.model.Track? = null,
    lyricsClockSource: com.auralis.music.data.service.PlaybackClockSource? = null,
    isPlaying: Boolean = true,
    isBuffering: Boolean = false,
    audioLeadingSilenceMs: Long? = null,
    readingFocusFraction: Float = 0.45f,
    listState: LazyListState = rememberLazyListState(),
    standardLyricsBlur: Boolean = com.auralis.music.ui.theme.LocalAppearanceSettings.current.standardLyricsBlur,
    loadingAlignment: Alignment = Alignment.Center
) {
    // ── DIAGNOSTIC REQUIREMENT 1: Log EXACT lyrics candidate reaching the UI ──
    LaunchedEffect(lyrics, track?.duration) {
        if (lyrics != null) {
            val tier = when {
                lyrics.lines.any { line -> !line.isInstrumental && (line.words?.count { it.duration != null } ?: 0) >= 2 } -> "TIER_WORD (2)"
                lyrics.lines.any { it.time > 0L } -> "TIER_LINE (1)"
                else -> "TIER_NONE (0)"
            }
            val playbackDurationMs = (track?.duration ?: 0L) * 1000L
            val masterMatch = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(lyrics, playbackDurationMs)
            val hasWordTiming = lyrics.lines.any { it.hasWordTiming }
            android.util.Log.i("LYRICS_DIAG", """
                ======================================================================
                [LYRICS_DIAG_CANDIDATE] Candidate reaching UI:
                  provider: ${lyrics.provider}
                  tier: $tier
                  master match status: $masterMatch
                  stated lyrics duration: ${lyrics.durationMs}ms (effective: ${lyrics.effectiveDurationMs}ms)
                  playback duration: ${playbackDurationMs}ms (diff: ${playbackDurationMs - lyrics.effectiveDurationMs}ms)
                  leading silence: ${lyrics.leadingSilenceMs}ms
                  applied offset: ${lyrics.appliedOffsetMs}ms
                  whether word timing exists: $hasWordTiming
                  line count: ${lyrics.lines.size}
                  track: "${track?.title}" by "${track?.artist}"
                ======================================================================
            """.trimIndent())
        }
    }

    // ── DIAGNOSTIC REQUIREMENT 2: Log exact playback sync state when the first vocal starts ──
    var hasLoggedFirstVocalDiag by remember(lyrics, track?.id) { mutableStateOf(false) }
    val firstVocalLine = remember(lyrics) { lyrics?.lines?.firstOrNull { !it.isInstrumental } }
    LaunchedEffect(positionState.value, lyrics, track?.id) {
        val fvLine = firstVocalLine ?: return@LaunchedEffect
        val curPos = positionState.value + offsetMs
        if (!hasLoggedFirstVocalDiag && curPos >= (fvLine.time - 50L) && curPos <= (fvLine.time + 4000L)) {
            hasLoggedFirstVocalDiag = true
            val exoPos = lyricsClockSource?.rawPositionMs() ?: -1L
            val appliedOffset = lyrics?.appliedOffsetMs ?: 0L
            val firstWord = fvLine.words?.firstOrNull()
            val alignedFirstWordTime = firstWord?.time ?: fvLine.time
            val origFirstWordTime = alignedFirstWordTime - appliedOffset
            val activeWord = fvLine.words?.firstOrNull { curPos in it.time..(it.endTime ?: (it.time + (it.duration ?: 0L))) }
                ?: firstWord
            val activeVideoId = track?.id ?: "unknown"

            android.util.Log.i("LYRICS_DIAG", """
                ======================================================================
                [LYRICS_DIAG_FIRST_VOCAL] First vocal playback sync snapshot:
                  1. ExoPlayer currentPosition: ${exoPos}ms
                  2. audioLeadingSilenceMs: ${audioLeadingSilenceMs}ms
                  3. original first-word timestamp: ${origFirstWordTime}ms
                  4. aligned first-word timestamp: ${alignedFirstWordTime}ms
                  5. LyricsClock current position: ${curPos}ms
                  6. currently highlighted lyric word: '${activeWord?.word}'
                  7. current lyric line timestamp: ${fvLine.time}ms
                  8. video/audio ID actually being played: $activeVideoId
                ======================================================================
            """.trimIndent())
        }
    }

    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = loadingAlignment
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                com.auralis.music.ui.components.SquirlyProgressRing(
                    isIndeterminate = true,
                    strokeWidth = 4.dp,
                    trackColor = Color.Transparent,
                    progressColor = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier.size(52.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Syncing Lyrics...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.70f)
                )
            }
        }
        return
    }

    val effectiveLines = remember(lyrics) {
        if (lyrics == null) emptyList()
        else if (lyrics.lines.isNotEmpty()) {
            lyrics.lines.map { line ->
                val unglued = com.auralis.music.data.parser.WordTiming.splitMergedWordsInLine(line)
                val healed = com.auralis.music.data.parser.WordTiming.healSplitWordsInText(unglued.text)
                if (healed != unglued.text) unglued.copy(text = healed) else unglued
            }
        }
        else if (!lyrics.plainLyrics.isNullOrBlank()) {
            lyrics.plainLyrics.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { LyricLine(time = 0L, text = com.auralis.music.data.parser.WordTiming.healSplitWordsInText(it)) }
        } else emptyList()
    }

    val isInstrumental = lyrics != null && (
        (effectiveLines.isNotEmpty() && effectiveLines.all { it.isInstrumental }) ||
        lyrics.plainLyrics?.trim()?.equals("[Instrumental]", ignoreCase = true) == true ||
        lyrics.plainLyrics?.trim()?.equals("♪ Instrumental ♪", ignoreCase = true) == true
    )

    if (isInstrumental) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = loadingAlignment
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = "🎵",
                    fontSize = 42.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Instrumental Track",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "This composition appears to have no vocals.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (onSearchManually != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onSearchManually,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Search Lyrics Manually")
                    }
                }
            }
        }
        return
    }

    if (lyrics == null || effectiveLines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = loadingAlignment) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Lyrics not available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
                if (onSearchManually != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onSearchManually,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Search Lyrics Manually")
                    }
                }
            }
        }
        return
    }

    val playbackDurationMs = (track?.duration ?: 0L) * 1000L
    val rawFirstLineTime = effectiveLines.firstOrNull { !it.isInstrumental }?.time ?: 0L
    val introDurationMs = remember(effectiveLines, playbackDurationMs) {
        val isValidIntro = rawFirstLineTime >= 1500L &&
            (playbackDurationMs <= 0L || rawFirstLineTime < playbackDurationMs - 5000L)
        if (isValidIntro) rawFirstLineTime else 0L
    }

    val isSynced = (lyrics.syncType != SyncType.PLAIN || effectiveLines.any { it.time > 0L }) && effectiveLines.isNotEmpty()

    var scrollRequestId by remember { mutableLongStateOf(0L) }
    var pendingSeekTarget by remember { mutableStateOf<SyncedPendingSeekTarget?>(null) }

    LaunchedEffect(positionState.value, isBuffering) {
        val pending = pendingSeekTarget
        if (pending != null) {
            val now = System.currentTimeMillis()
            val currentPos = positionState.value + offsetMs
            val clockSourceBuffering = isBuffering || (lyricsClockSource?.isBuffering() == true)
            val clockSourcePlaying = lyricsClockSource?.isPlaying() ?: isPlaying
            val hasReachedTarget = kotlin.math.abs(currentPos - pending.targetTimeMs) <= 350L
            val minTimeElapsed = (now - pending.timestamp) >= 80L
            val hasConverged = minTimeElapsed && hasReachedTarget && !clockSourceBuffering && clockSourcePlaying
            val isTimedOut = (now - pending.timestamp) > 1500L
            if (hasConverged || isTimedOut) {
                pendingSeekTarget = null
            }
        }
    }

    // Derived, not remembered against the position: composition re-runs when the
    // active *lines* change, not when the millisecond does.
    val activeIndicesState = remember(effectiveLines, isSynced, offsetMs, positionState) {
        derivedStateOf {
            if (!isSynced) emptySet<Int>()
            else LyricsEngine.findActiveLyricIndices(effectiveLines, positionState.value, offsetMs)
        }
    }

    val visualActiveIndicesState = remember(effectiveLines, isSynced, offsetMs, positionState, pendingSeekTarget) {
        derivedStateOf {
            if (!isSynced) emptySet<Int>()
            else {
                val pending = pendingSeekTarget
                if (pending != null) {
                    setOf(pending.lineIndex)
                } else {
                    LyricsEngine.findVisualActiveLineIndices(effectiveLines, positionState.value, offsetMs)
                }
            }
        }
    }

    val primaryActiveIndexState = remember(effectiveLines, isSynced, offsetMs, positionState, pendingSeekTarget) {
        derivedStateOf {
            if (!isSynced) -1
            else {
                val pending = pendingSeekTarget
                if (pending != null) {
                    pending.lineIndex
                } else {
                    LyricsEngine.findActiveLyricIndex(effectiveLines, positionState.value, offsetMs)
                }
            }
        }
    }

    val pastIndicesState = remember(effectiveLines, isSynced, offsetMs, positionState) {
        derivedStateOf {
            if (!isSynced) emptySet<Int>()
            else {
                val targetTime = positionState.value + offsetMs
                val active = LyricsEngine.findActiveLyricIndices(effectiveLines, positionState.value, offsetMs)
                val primary = LyricsEngine.findActiveLyricIndex(effectiveLines, positionState.value, offsetMs)
                buildSet {
                    for (i in effectiveLines.indices) {
                        if (!active.contains(i) && (active.isNotEmpty() || primary != i)) {
                            val line = effectiveLines[i]
                            val lineEnd = effectiveLines.getOrNull(i + 1)?.time ?: Long.MAX_VALUE
                            if (targetTime >= lineEnd) {
                                add(i)
                            }
                        }
                    }
                }
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
    if (appearance.shouldUseExperimentalLyrics) {
        ExperimentalLyricsView(
            lyrics = lyrics,
            positionState = positionState,
            onSeekTo = onSeekTo,
            modifier = modifier,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            lyricsClockSource = lyricsClockSource,
            offsetMs = offsetMs,
            onOffsetChange = onOffsetChange,
            onSearchManually = onSearchManually,
            headerContent = null,
            footerContent = null,
            track = track,
            standardLyricsBlur = standardLyricsBlur
        )
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Auto-scrolling state & user drag detection
    var isAutoScrollEnabled by rememberSaveable { mutableStateOf(appearance.autoScrollLyrics) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // Lyric Selection & Sharing State (Capped strictly at max 5 lines)
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showShareSheet by remember { mutableStateOf(false) }
    var shareLyricsText by remember { mutableStateOf("") }

    // NestedScrollConnection detects manual user scrolling across all lyrics modes,
    // even when child row clicks consume pointer events.
    val lyricsNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!isProgrammaticScroll && source == NestedScrollSource.UserInput && kotlin.math.abs(available.y) > 0.5f) {
                    if (selectedIndices.isEmpty()) {
                        isAutoScrollEnabled = false
                        isUserInteracting = true
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!isProgrammaticScroll && source == NestedScrollSource.UserInput &&
                    (kotlin.math.abs(consumed.y) > 0.5f || kotlin.math.abs(available.y) > 0.5f)
                ) {
                    if (selectedIndices.isEmpty()) {
                        isAutoScrollEnabled = false
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

    // Direct scroll-state observer: ensures any non-programmatic scroll immediately disengages auto-scroll
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            isUserInteracting = false
        }
    }

    val normalizedPos = appearance.lyricsTextPosition.lowercase()
    val textAlign = when (normalizedPos) {
        "left", "start" -> TextAlign.Start
        "right", "end" -> TextAlign.End
        else -> TextAlign.Center
    }
    val horizontalAlignment = when (normalizedPos) {
        "left", "start" -> Alignment.Start
        "right", "end" -> Alignment.End
        else -> Alignment.CenterHorizontally
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val rowMaxWidthPx = (viewportWidthPx - with(density) { 48.dp.toPx() }).toInt().coerceAtLeast(100)

        // Target center position: 0.45f keeps the active lyric line directly in
        // the user's natural reading focal point while providing generous space for
        // reading upcoming lines below.
        val targetCenterFraction = readingFocusFraction.coerceIn(0.2f, 0.6f)
        val topPaddingDp = 8.dp
        val bottomPaddingDp = (maxHeight * (1f - targetCenterFraction) + 40.dp).coerceAtLeast(160.dp)

        // Track whether initial scroll has completed
        var hasInitialCentered by rememberSaveable(track?.id) { mutableStateOf(false) }
        // Track last centered index to detect large jumps (tap-to-seek) vs natural progression
        var lastCenteredIndex by rememberSaveable(track?.id) { mutableIntStateOf(-1) }
        // Not saveable on purpose: when this view is (re)composed — e.g. switching back to the
        // Lyrics tab — the first centering snaps while the tab is still fading in, instead of
        // stacking a 260ms scroll on top of the tab's entry glide (read as a slow creep).
        var centeredSinceComposed by remember { mutableStateOf(false) }

        // Initial centering on track change
        LaunchedEffect(track?.id, lyrics) {
            hasInitialCentered = false
            lastCenteredIndex = -1
            isAutoScrollEnabled = appearance.autoScrollLyrics
        }

        LaunchedEffect(appearance.autoScrollLyrics) {
            isAutoScrollEnabled = appearance.autoScrollLyrics
        }

        val mergedLyricsItems = remember(effectiveLines) {
            val items = mutableListOf<SyncedLyricsItem>()
            effectiveLines.forEachIndexed { index, line ->
                items.add(SyncedLyricsItem.Line(index, line))
                if (index < effectiveLines.size - 1) {
                    val nextLine = effectiveLines[index + 1]
                    val nextStart = nextLine.time
                    // Circle appears when the singing stops and fills the whole break.
                    instrumentalBreakWindow(line, nextStart)?.let { (start, end) ->
                        items.add(SyncedLyricsItem.Indicator(index, start, end))
                    }
                }
            }
            items
        }

        val activeMergedIndexState = remember(mergedLyricsItems, primaryActiveIndexState, positionState, offsetMs, pendingSeekTarget) {
            derivedStateOf {
                if (!isSynced) return@derivedStateOf -1
                val pending = pendingSeekTarget
                if (pending != null) {
                    val found = mergedLyricsItems.indexOfFirst {
                        it is SyncedLyricsItem.Line && it.originalIndex == pending.lineIndex
                    }
                    if (found >= 0) return@derivedStateOf found
                }
                val currentMs = positionState.value + offsetMs
                val activeIndicatorIndex = mergedLyricsItems.indexOfFirst {
                    it is SyncedLyricsItem.Indicator && currentMs in it.gapStartMs..it.gapEndMs
                }
                if (activeIndicatorIndex >= 0) {
                    return@derivedStateOf activeIndicatorIndex
                }
                val primaryIdx = primaryActiveIndexState.value
                if (primaryIdx >= 0) {
                    val found = mergedLyricsItems.indexOfFirst {
                        it is SyncedLyricsItem.Line && it.originalIndex == primaryIdx
                    }
                    if (found >= 0) return@derivedStateOf found
                }
                if (mergedLyricsItems.isNotEmpty()) 0 else -1
            }
        }

        val isCurrentLineCentered by remember(activeMergedIndexState, listState, isUserInteracting) {
            derivedStateOf {
                if (isUserInteracting || listState.isScrollInProgress) return@derivedStateOf false
                val activeIdx = activeMergedIndexState.value
                if (activeIdx !in mergedLyricsItems.indices) return@derivedStateOf true
                // The first item (index 0) anchored near top of viewport is centered by definition
                if (activeIdx == 0 && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 24) {
                    return@derivedStateOf true
                }
                val layoutInfo = listState.layoutInfo
                val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx } ?: return@derivedStateOf false
                val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                if (viewportHeight <= 0) return@derivedStateOf false
                val targetCenterY = layoutInfo.viewportStartOffset + (viewportHeight * targetCenterFraction)
                val itemCenterY = item.offset + (item.size / 2f)
                kotlin.math.abs(itemCenterY - targetCenterY) < 100f
            }
        }

        val shouldShowResyncButton by remember(isAutoScrollEnabled, isCurrentLineCentered, isSynced, selectedIndices.size, appearance.autoScrollLyrics) {
            derivedStateOf {
                if (!isSynced || selectedIndices.isNotEmpty()) return@derivedStateOf false
                if (appearance.autoScrollLyrics) {
                    !isAutoScrollEnabled
                } else {
                    !isCurrentLineCentered
                }
            }
        }

        // Suspend function to accurately and smoothly center any lyric line or interval in the viewport
        val centerActiveLine: suspend (targetIndex: Int, animate: Boolean) -> Unit = { targetIndex, animate ->
            if (targetIndex in mergedLyricsItems.indices) {
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
                        val targetCenterY = layoutInfo.viewportStartOffset + (viewportHeight * targetCenterFraction)
                        var itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                        if (itemInfo == null) {
                            val visibleNow = layoutInfo.visibleItemsInfo
                            if (animate && visibleNow.isNotEmpty()) {
                                // Off-screen target on an animated move (e.g. auto-resume after the user scrolled
                                // away): glide there in one motion instead of jumping, estimating the distance
                                // from the average visible line height. The correction below centres it exactly.
                                val avgItem = visibleNow.map { it.size }.average().toFloat() + layoutInfo.mainAxisItemSpacing
                                val anchor = visibleNow.first()
                                val estimatedCenter = anchor.offset + (targetIndex - anchor.index) * avgItem + avgItem / 2f
                                val estimatedDelta = estimatedCenter - targetCenterY
                                val glideMs = (350 + kotlin.math.abs(estimatedDelta) / 6f).toInt().coerceIn(400, 900)
                                listState.animateScrollBy(
                                    value = estimatedDelta,
                                    animationSpec = tween(durationMillis = glideMs, easing = FastOutSlowInEasing)
                                )
                            } else {
                                val currentFirst = listState.firstVisibleItemIndex
                                val distance = kotlin.math.abs(targetIndex - currentFirst)
                                if (distance > 8) {
                                    val preIndex = if (targetIndex > currentFirst) {
                                        (targetIndex - 2).coerceAtLeast(0)
                                    } else {
                                        (targetIndex + 2).coerceAtMost(mergedLyricsItems.size - 1)
                                    }
                                    listState.scrollToItem(preIndex)
                                } else {
                                    listState.scrollToItem(targetIndex)
                                }
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
                            val targetCenterYActual = if (targetIndex == 0) {
                                layoutInfo.viewportStartOffset + with(density) { 16.dp.toPx() } + (itemInfo.size / 2f)
                            } else {
                                targetCenterY
                            }
                            val itemCenterY = itemInfo.offset + (itemInfo.size / 2f)
                            val scrollDelta = itemCenterY - targetCenterYActual
                            if (kotlin.math.abs(scrollDelta) > 1.5f) {
                                try {
                                    if (animate) {
                                        listState.animateScrollBy(
                                            value = scrollDelta,
                                            animationSpec = tween(
                                                durationMillis = 260,
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
            if (appearance.autoScrollLyrics) {
                isAutoScrollEnabled = true
            }
            isUserInteracting = false
            pendingSeekTarget = null
            val activeIndex = activeMergedIndexState.value
            if (activeIndex in mergedLyricsItems.indices) {
                // Mark it handled first so the follow-the-line effect (restarted by re-enabling
                // auto scroll) does not launch a second, competing scroll to the same line.
                lastCenteredIndex = activeIndex
                coroutineScope.launch {
                    centerActiveLine(activeIndex, true)
                }
            } else if (mergedLyricsItems.isNotEmpty()) {
                // Mark it handled first so the follow-the-line effect (restarted by re-enabling
                // auto scroll) does not launch a second, competing scroll to the same line.
                lastCenteredIndex = 0
                coroutineScope.launch {
                    centerActiveLine(0, true)
                }
            }
        }

        // With "Auto scroll lyrics" on, a manual scroll only pauses following: once the user has
        // left the list alone for 1.5s (no drag, no fling, not selecting lines), re-centre the
        // active line and resume. Any new touch restarts the wait. Previously it stayed off until
        // the Re-sync button was tapped.
        // (isScrollInProgress stays true for the whole drag, even while the finger rests, so it
        // alone tracks "user is scrolling"; isUserInteracting can be left stale by line selection.)
        LaunchedEffect(
            appearance.autoScrollLyrics,
            isAutoScrollEnabled,
            listState.isScrollInProgress,
            selectedIndices.isEmpty(),
            isSynced
        ) {
            if (!appearance.autoScrollLyrics || !isSynced || isAutoScrollEnabled ||
                listState.isScrollInProgress || selectedIndices.isNotEmpty()
            ) return@LaunchedEffect
            delay(1_500L)
            resyncLyrics()
        }

        // Automatic, smooth centering of active lyric line or instrumental indicator.
        LaunchedEffect(activeMergedIndexState, isSynced, isAutoScrollEnabled, mergedLyricsItems) {
            if (!isSynced || !isAutoScrollEnabled) return@LaunchedEffect
            snapshotFlow {
                Triple(activeMergedIndexState.value, isUserInteracting, selectedIndices.isNotEmpty())
            }
                .distinctUntilChanged()
                .collectLatest { (activeIndex, interacting, selecting) ->
                    if (interacting || selecting) return@collectLatest
                    if (activeIndex < 0) {
                        if (mergedLyricsItems.isNotEmpty() && lastCenteredIndex != 0) {
                            centerActiveLine(0, hasInitialCentered)
                            lastCenteredIndex = 0
                        }
                        return@collectLatest
                    }
                    if (activeIndex >= mergedLyricsItems.size) return@collectLatest
                    // Do not re-scroll or animate if the active line has not changed
                    if (hasInitialCentered && activeIndex == lastCenteredIndex) return@collectLatest

                    val shouldAnimate = hasInitialCentered && centeredSinceComposed
                    centerActiveLine(activeIndex, shouldAnimate)
                    lastCenteredIndex = activeIndex
                    hasInitialCentered = true
                    centeredSinceComposed = true
                }
        }

        val isIntroActiveState = remember(isSynced, introDurationMs, offsetMs, positionState) {
            derivedStateOf {
                if (!isSynced || introDurationMs < 1500L) return@derivedStateOf false
                val currentMs = (positionState.value + offsetMs).coerceAtLeast(0L)
                currentMs < introDurationMs
            }
        }
        // The intro indicator shows whole seconds and a 380 ms dot cycle, so a
        // 100 ms grid is indistinguishable from the raw clock and costs a tenth
        // of the invalidations.
        val introTimeState = remember(positionState, offsetMs) {
            derivedStateOf {
                ((positionState.value + offsetMs).coerceAtLeast(0L) / 100L) * 100L
            }
        }

        val topFadePx = with(density) { 36.dp.toPx() }
        val bottomFadePx = with(density) { 90.dp.toPx() }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(lyricsNestedScrollConnection)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    if (topFadePx > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black,
                                startY = 0f,
                                endY = topFadePx
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                    if (bottomFadePx > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black,
                                1f to Color.Transparent,
                                startY = size.height - bottomFadePx,
                                endY = size.height
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                },
            contentPadding = PaddingValues(
                top = if (isSynced) topPaddingDp else 36.dp,
                bottom = if (isSynced) bottomPaddingDp else 220.dp,
                start = 16.dp,
                end = 16.dp
            ),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            itemsIndexed(
                items = mergedLyricsItems,
                key = { listIndex, item ->
                    when (item) {
                        is SyncedLyricsItem.Line -> "${item.line.time}_${item.originalIndex}"
                        is SyncedLyricsItem.Indicator -> "indicator_${item.gapStartMs}_${item.gapEndMs}_$listIndex"
                    }
                }
            ) { listIndex, item ->
                when (item) {
                    is SyncedLyricsItem.Line -> {
                        val index = item.originalIndex
                        val line = item.line
                        val activeIndices = activeIndicesState.value
                        val visualActiveIndices = visualActiveIndicesState.value
                        val pastIndices = pastIndicesState.value
                        val primaryIndex = primaryActiveIndexState.value
                        val isCurrent = isSynced && (visualActiveIndices.contains(index) || activeIndices.contains(index) || (activeIndices.isEmpty() && primaryIndex == index))
                        val isPast = isSynced && !isCurrent && (index < primaryIndex || (primaryIndex == -1 && pastIndices.contains(index)))
                        val pastDistance = if (isPast) {
                            val minActive = (visualActiveIndices + activeIndices).minOrNull()
                            val ref = minActive ?: (primaryIndex + 1)
                            (ref - index).coerceAtLeast(1)
                        } else 0
                        val futureDistance = if (!isPast && !isCurrent) {
                            val maxActive = (visualActiveIndices + activeIndices).maxOrNull()
                            val ref = maxActive ?: primaryIndex
                            if (ref >= 0) (index - ref).coerceAtLeast(1) else 1
                        } else 0
                        val distanceFromCurrent = when {
                            isCurrent -> 0
                            isPast -> pastDistance
                            else -> futureDistance
                        }
                        val blurGeometry by remember(
                            listIndex,
                            listState,
                            activeMergedIndexState,
                            targetCenterFraction,
                            viewportHeightPx
                        ) {
                            derivedStateOf {
                                resolveLyricsBlurGeometry(
                                    layoutInfo = listState.layoutInfo,
                                    lineIndex = listIndex,
                                    activeIndex = activeMergedIndexState.value,
                                    fallbackActiveFraction = targetCenterFraction,
                                    fallbackViewportHeightPx = viewportHeightPx
                                )
                            }
                        }

                        // Vocal agent & background vocal positioning
                        val lineAlignment = when {
                            line.isBackground -> Alignment.CenterHorizontally
                            line.agent == "v1" -> Alignment.Start
                            line.agent == "v2" -> Alignment.End
                            line.agent == "v1000" -> Alignment.CenterHorizontally
                            else -> horizontalAlignment
                        }
                        val lineTextAlign = when {
                            line.isBackground -> TextAlign.Center
                            line.agent == "v1" -> TextAlign.Start
                            line.agent == "v2" -> TextAlign.End
                            line.agent == "v1000" -> TextAlign.Center
                            else -> textAlign
                        }

                        val nextLine = effectiveLines.getOrNull(index + 1)
                        val isNextBg = nextLine?.isBackground == true
                        val isPairedWithNext = nextLine != null && (isNextBg || (nextLine.time == line.time && isSynced))

                        val itemBottomSpacing = when {
                            index == effectiveLines.lastIndex -> 0.dp
                            isPairedWithNext -> 2.dp
                            else -> (14 * appearance.lyricsLineSpacing).dp
                        }

                        val isSelected = selectedIndices.contains(index)
                        val isSelectionMode = selectedIndices.isNotEmpty()

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = itemBottomSpacing),
                            horizontalAlignment = lineAlignment
                        ) {
                            if (index == 0 && isIntroActiveState.value) {
                                InstrumentalIntroIndicator(
                                    currentTimeMsState = introTimeState,
                                    introDurationMs = introDurationMs,
                                    isMetroLyrics = false,
                                    onSkipIntro = { onSeekTo(introDurationMs) }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isSelected) {
                                            Modifier
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(Color.White.copy(alpha = 0.18f))
                                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        } else {
                                            Modifier.padding(
                                                horizontal = 4.dp,
                                                vertical = if (line.isBackground || isPairedWithNext) 1.dp else 2.dp
                                            )
                                        }
                                    )
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedIndices = if (isSelected) {
                                                    selectedIndices - index
                                                } else {
                                                    if (selectedIndices.size < 5) {
                                                        selectedIndices + index
                                                    } else {
                                                        android.widget.Toast.makeText(context, "Select up to 5 lines for showoff", android.widget.Toast.LENGTH_SHORT).show()
                                                        selectedIndices
                                                    }
                                                }
                                            } else if (isSynced && appearance.changeLyricsOnTap) {
                                                val reqId = ++scrollRequestId
                                                pendingSeekTarget = SyncedPendingSeekTarget(
                                                    lineIndex = index,
                                                    targetTimeMs = line.time,
                                                    requestId = reqId
                                                )
                                                if (appearance.autoScrollLyrics) {
                                                    isAutoScrollEnabled = true
                                                }
                                                isUserInteracting = false
                                                onSeekTo(line.time)
                                            }
                                        },
                                        onLongClick = {
                                            isUserInteracting = true
                                            selectedIndices = if (isSelected) {
                                                selectedIndices - index
                                            } else {
                                                if (selectedIndices.size < 5) {
                                                    selectedIndices + index
                                                } else {
                                                    android.widget.Toast.makeText(context, "Select up to 5 lines for showoff", android.widget.Toast.LENGTH_SHORT).show()
                                                    selectedIndices
                                                }
                                            }
                                        }
                                    )
                            ) {
                                LyricLineRow(
                                    line = line,
                                    nextLineTime = effectiveLines.getOrNull(index + 1)?.time,
                                    isCurrent = isCurrent,
                                    isPast = isPast,
                                    pastDistance = pastDistance,
                                    distanceFromCurrent = distanceFromCurrent,
                                    lineCenterPx = blurGeometry.lineCenterPx,
                                    activeLineCenterPx = blurGeometry.activeLineCenterPx,
                                    viewportStartPx = blurGeometry.viewportStartPx,
                                    viewportEndPx = blurGeometry.viewportEndPx,
                                    isSelected = isSelected,
                                    lyricsMode = lyricsMode,
                                    syncType = lyrics.syncType,
                                    isSynced = isSynced,
                                    textAlign = lineTextAlign,
                                    horizontalAlignment = lineAlignment,
                                    positionState = positionState,
                                    offsetMs = offsetMs,
                                    animationMode = LyricsAnimationMode.fromDisplayName(appearance.lyricsAnimation),
                                    enableGlowEffect = appearance.enableGlowingLyricsEffect,
                                    standardBlur = standardLyricsBlur,
                                    fontSizeSp = appearance.lyricsTextSize,
                                    lineSpacingMultiplier = appearance.lyricsLineSpacing,
                                    isAutoScrollActive = isAutoScrollEnabled,
                                    isUserInteracting = isUserInteracting || !isAutoScrollEnabled,
                                    isScrolling = listState.isScrollInProgress,
                                    rowMaxWidthPx = rowMaxWidthPx,
                                    isPlaying = isPlaying
                                )
                            }
                        }
                    }
                    is SyncedLyricsItem.Indicator -> {
                        val currentPos = positionState.value + offsetMs
                        val isIndicatorVisible = isSynced && currentPos in (item.gapStartMs - 300L)..item.gapEndMs
                        LyricsIntervalIndicator(
                            gapStartMs = item.gapStartMs,
                            gapEndMs = item.gapEndMs,
                            currentPositionMs = currentPos,
                            visible = isIndicatorVisible,
                            color = Color.White,
                            isMetroLyrics = false,
                            onSkip = { onSeekTo(item.gapEndMs) }
                        )
                    }
                }
            }
        }

        // Floating Re-sync button when auto-scroll is disabled by manual scrolling or active line is not centered
        AnimatedVisibility(
            visible = shouldShowResyncButton,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
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
        val isSelectionMode = selectedIndices.isNotEmpty()
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
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
                    onClick = { selectedIndices = emptySet() },
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
                selectedIndices = emptySet()
            }
        )
    }
}

sealed class SyncedLyricsItem {
    data class Line(
        val originalIndex: Int,
        val line: LyricLine
    ) : SyncedLyricsItem()

    data class Indicator(
        val afterOriginalIndex: Int,
        val gapStartMs: Long,
        val gapEndMs: Long
    ) : SyncedLyricsItem()
}

internal data class SyncedPendingSeekTarget(
    val lineIndex: Int,
    val targetTimeMs: Long,
    val requestId: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Resolves the effective words for lyric line rendering.
 *
 * Phase 4B-B Contract:
 * - When [syncType] is [SyncType.RICHSYNC] and [line.words] is non-empty, returns [line.words] verbatim
 *   (preserving genuine provider timestamps without modification).
 * - For line-synced lyrics ([SyncType.LINE_SYNC]), static lyrics ([SyncType.PLAIN]), or lines with no
 *   genuine word timing, returns `null`. Under NO circumstances are word timestamps fabricated or durations
 *   subdivided across words.
 */
internal fun resolveEffectiveWords(line: LyricLine, syncType: SyncType): List<LyricWord>? {
    return if (syncType == SyncType.RICHSYNC && !line.words.isNullOrEmpty()) {
        line.words
    } else {
        null
    }
}

private data class WordLayoutData(
    val range: LyricsEngine.WordRange,
    val wordPath: androidx.compose.ui.graphics.Path,
    val bounds: androidx.compose.ui.geometry.Rect,
    val lineIndex: Int,
    val lineTop: Float,
    val lineBottom: Float,
    val isSingleLine: Boolean,
    val isRtl: Boolean
)

/**
 * Result of computing the active frame's karaoke highlight state for [wordLayouts] at [adjustedMs].
 * Separates completed words from the currently active sweeping word to enable soft feathered leading-edge rendering.
 */
private data class WordHighlightSweep(
    val activeItem: WordLayoutData,
    val progress: Float
)

/**
 * Builds [finishedPath] containing the geometry of all fully sung words (progress >= 1f).
 * Returns the currently active sweeping word (0f < progress < 1f) along with its progress,
 * or null if no word is actively sweeping (e.g. during vocal rests or before line start).
 *
 * For finished words: appends the full word path or line-clamped bounding box.
 * For unstarted words / rests: appends nothing, preserving vocal silence.
 */
private fun updateWordHighlightState(
    finishedPath: androidx.compose.ui.graphics.Path,
    wordLayouts: List<WordLayoutData>,
    adjustedMs: Long
): WordHighlightSweep? {
    finishedPath.reset()
    if (wordLayouts.isEmpty()) return null

    var activeSweep: WordHighlightSweep? = null

    for (i in wordLayouts.indices) {
        val item = wordLayouts[i]
        val progress = LyricsEngine.calculateWordProgress(item.range.word, adjustedMs, 0L)
        if (progress <= 0f) {
            // Word not started yet — preserves vocal rests and upcoming silence
            continue
        }

        if (progress >= 1f) {
            // Fully sung word / syllable
            val bounds = item.bounds
            if (!bounds.isEmpty && item.isSingleLine) {
                finishedPath.addRect(
                    androidx.compose.ui.geometry.Rect(
                        left = bounds.left - 4f,
                        top = item.lineTop,
                        right = bounds.right + 4f,
                        bottom = item.lineBottom
                    )
                )
            } else {
                finishedPath.addPath(item.wordPath)
            }
        } else {
            // Word is currently sweeping across its measured interval (0f < progress < 1f).
            // When synthetic word pacing produces overlapping durations (wordDur > stepMs),
            // two consecutive words can be simultaneously active. Promote the earlier word
            // to finishedPath so it is never dropped from rendering (fixes blink/flicker).
            if (activeSweep != null) {
                val prevItem = activeSweep.activeItem
                val prevBounds = prevItem.bounds
                if (!prevBounds.isEmpty && prevItem.isSingleLine) {
                    finishedPath.addRect(
                        androidx.compose.ui.geometry.Rect(
                            left = prevBounds.left - 4f,
                            top = prevItem.lineTop,
                            right = prevBounds.right + 4f,
                            bottom = prevItem.lineBottom
                        )
                    )
                } else {
                    finishedPath.addPath(prevItem.wordPath)
                }
            }
            activeSweep = WordHighlightSweep(item, progress)
        }
    }

    return activeSweep
}

/**
 * Helper to build [WordLayoutData] ensuring word geometry never bleeds into adjacent visual rows
 * when text wraps across multiple display lines.
 */
private fun buildWordLayouts(
    layout: androidx.compose.ui.text.TextLayoutResult,
    wordRanges: List<LyricsEngine.WordRange>
): List<WordLayoutData> {
    val textLen = layout.layoutInput.text.length
    return wordRanges.mapNotNull { range ->
        val start = range.startIndex.coerceIn(0, textLen)
        val end = range.endIndex.coerceIn(start, textLen)
        if (start >= end) return@mapNotNull null

        // 1. Trim trailing whitespace only for geometry lookup to avoid pulling adjacent visual rows
        val glyphEnd = (start + range.word.word.trimEnd().length).coerceIn(start, textLen)
        val wordPath = if (glyphEnd > start) layout.getPathForRange(start, glyphEnd) else layout.getPathForRange(start, end)
        val rawBounds = wordPath.getBounds()

        // 2. Determine the visual line from the word's start offset
        val wordLine = layout.getLineForOffset(start)
        val endLine = if (glyphEnd > start) {
            layout.getLineForOffset((glyphEnd - 1).coerceAtLeast(start))
        } else {
            wordLine
        }

        // 3. Strictly clamp the geometry vertically to that visual line
        val lineTop = layout.getLineTop(wordLine)
        val lineBottom = layout.getLineBottom(wordLine)
        val clampedBounds = if (!rawBounds.isEmpty) {
            androidx.compose.ui.geometry.Rect(
                left = rawBounds.left,
                top = rawBounds.top.coerceIn(lineTop, lineBottom),
                right = rawBounds.right,
                bottom = rawBounds.bottom.coerceIn(lineTop, lineBottom)
            )
        } else {
            rawBounds
        }

        val isRtl = layout.getParagraphDirection(start) == ResolvedTextDirection.Rtl
        WordLayoutData(
            range = range,
            wordPath = wordPath,
            bounds = clampedBounds,
            lineIndex = wordLine,
            lineTop = lineTop,
            lineBottom = lineBottom,
            isSingleLine = wordLine == endLine,
            isRtl = isRtl
        )
    }
}

internal fun computeLyricsProgressiveBlur(
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
    if (lineCenter <= activeCenter) {
        val availableAbove = (activeCenter - viewportStartPx).coerceAtLeast(1f)
        val aboveProgress = ((activeCenter - lineCenter) / availableAbove).coerceIn(0f, 1f)
        return 2.5f * aboveProgress.pow(1.35f)
    }

    val availableBelow = (viewportEndPx - activeCenter).coerceAtLeast(1f)
    val rawProgress = ((lineCenter - activeCenter) / availableBelow).coerceIn(0f, 1f)
    // A small crisp band around the active line flows continuously into full blur
    // at the viewport bottom. This is positional, not a line-distance bucket.
    val blurProgress = ((rawProgress - 0.04f) / 0.96f).coerceIn(0f, 1f)
    return 24f * blurProgress.pow(1.35f)
}

internal data class LyricsBlurGeometry(
    val lineCenterPx: Float,
    val activeLineCenterPx: Float,
    val viewportStartPx: Float,
    val viewportEndPx: Float
)

internal fun resolveLyricsBlurGeometry(
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

internal fun computeClassicLyricsLayerAlpha(
    isPlain: Boolean,
    isCurrent: Boolean,
    isPast: Boolean,
    lyricsMode: LyricsMode
): Float = when {
    isPlain -> 0.95f
    isCurrent -> 1f
    isPast -> if (lyricsMode == LyricsMode.CINEMA) 0.55f else 0.58f
    else -> if (lyricsMode == LyricsMode.CINEMA) 0.32f else 0.38f
}

internal fun computeClassicLyricsTextAlpha(
    isBackground: Boolean,
    isCurrent: Boolean,
    isPast: Boolean
): Float = when {
    isCurrent -> if (isBackground) 0.85f else 1f
    isPast -> if (isBackground) 0.45f else 0.60f
    else -> if (isBackground) 0.28f else 0.38f
}

/**
 * Universal lyric line row supporting Auralis native canvas sweep + 10 animation styles.
 *
 * Preserves the original Auralis typography hierarchy:
 * - Active: 22sp ExtraBold, alpha 1.0f (or 28sp in Cinema mode).
 * - Inactive: 20sp SemiBold, alpha 0.38f upcoming, 0.58f past (or 22sp in Cinema mode).
 * - Scale falloff: 0.98f, 0.96f, 0.94f for past lines.
 * - Draw-phase word sweep with soft glow shadow on completed syllables.
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    nextLineTime: Long? = null,
    isCurrent: Boolean,
    isPast: Boolean,
    pastDistance: Int = 0,
    distanceFromCurrent: Int = 0,
    lineCenterPx: Float = Float.NaN,
    activeLineCenterPx: Float = Float.NaN,
    viewportStartPx: Float = Float.NaN,
    viewportEndPx: Float = Float.NaN,
    isSelected: Boolean = false,
    lyricsMode: LyricsMode,
    syncType: SyncType,
    isSynced: Boolean = true,
    textAlign: TextAlign,
    horizontalAlignment: Alignment.Horizontal,
    positionState: State<Long>,
    offsetMs: Long,
    animationMode: LyricsAnimationMode,
    enableGlowEffect: Boolean,
    standardBlur: Boolean,
    fontSizeSp: Float,
    lineSpacingMultiplier: Float,
    isAutoScrollActive: Boolean,
    isUserInteracting: Boolean = false,
    isScrolling: Boolean = false,
    rowMaxWidthPx: Int,
    isPlaying: Boolean = true
) {
    val isPlain = syncType == SyncType.PLAIN

    val targetBlur = computeLyricsProgressiveBlur(
        standardBlur = standardBlur,
        isSynced = isSynced,
        isPlain = isPlain,
        isSelected = isSelected,
        isCurrent = isCurrent,
        isUserInteracting = isUserInteracting,
        lineCenterPx = lineCenterPx,
        activeLineCenterPx = activeLineCenterPx,
        viewportStartPx = viewportStartPx,
        viewportEndPx = viewportEndPx
    )

    val animatedBlur by animateFloatAsState(
        targetValue = targetBlur,
        animationSpec = if (isUserInteracting || isScrolling) androidx.compose.animation.core.snap() else tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "LyricStandardBlur"
    )

    val targetScale = when {
        isPlain || isCurrent || isSelected -> 1.0f
        isPast -> when (pastDistance) {
            1 -> 0.98f
            2 -> 0.96f
            else -> 0.94f
        }
        else -> 1.0f
    }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = motionTween(AuralisDuration.Standard, AuralisEasing.Standard),
        label = "LyricScale"
    )

    val effectiveWords = remember(line.words, syncType) {
        resolveEffectiveWords(line, syncType)
    }

    val hasWordTiming = !effectiveWords.isNullOrEmpty()

    val targetAlpha = computeClassicLyricsLayerAlpha(isPlain, isCurrent, isPast, lyricsMode)
    val animAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = motionTween(AuralisDuration.Standard, AuralisEasing.Standard),
        label = "LyricAlpha"
    )

    // Active word-synced lyrics snap to 1.0f immediately at the provider timestamp
    val effectiveAlpha = if (hasWordTiming && isCurrent) 1.0f else animAlpha

    val scaleRatio = (fontSizeSp / 22f).coerceIn(0.7f, 1.6f)
    val baseFontSize = when {
        isPlain -> (20f * scaleRatio).sp
        isCurrent -> if (lyricsMode == LyricsMode.CINEMA) (28f * scaleRatio).sp else (21f * scaleRatio).sp
        else -> if (lyricsMode == LyricsMode.CINEMA) (22f * scaleRatio).sp else (20f * scaleRatio).sp
    }
    val fontSize = if (line.isBackground) (baseFontSize.value * 0.70f).sp else baseFontSize
    val fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal
    val fontWeight = when {
        line.isBackground -> if (isCurrent) FontWeight.Bold else FontWeight.SemiBold
        isCurrent -> FontWeight.ExtraBold
        else -> FontWeight.SemiBold
    }

    val textColor = Color.White.copy(
        alpha = computeClassicLyricsTextAlpha(line.isBackground, isCurrent, isPast)
    )

    // Precompute character mapping for words only when line is active
    val wordRanges = remember(line, effectiveWords, isCurrent) {
        if (isCurrent && hasWordTiming) LyricsEngine.mapWordsToLineSpans(line.text, effectiveWords)
        else emptyList()
    }

    // Precompute active text layout and word layout data using TextMeasurer for frame 0 readiness (only for active line)
    val textMeasurer = rememberTextMeasurer()
    val activeTextStyle = remember(lyricsMode, line.isBackground, textAlign, fontSize) {
        val activeBaseSize = if (lyricsMode == LyricsMode.CINEMA) (28f * scaleRatio).sp else (21f * scaleRatio).sp
        val activeSize = if (line.isBackground) (activeBaseSize.value * 0.70f).sp else activeBaseSize
        val activeFontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal
        val activeWeight = if (line.isBackground) FontWeight.Bold else FontWeight.ExtraBold
        TextStyle(
            fontSize = activeSize,
            fontWeight = activeWeight,
            fontStyle = activeFontStyle,
            textAlign = textAlign,
            letterSpacing = (-0.4).sp,
            lineHeight = (activeSize.value * 1.30f * (lineSpacingMultiplier / 1.3f).coerceIn(0.85f, 1.4f)).sp
        )
    }

    val precomputedLayout = remember(line, activeTextStyle, rowMaxWidthPx, isCurrent) {
        if (isCurrent && hasWordTiming && rowMaxWidthPx > 0) {
            try {
                textMeasurer.measure(
                    text = AnnotatedString(line.text),
                    style = activeTextStyle,
                    constraints = Constraints(maxWidth = rowMaxWidthPx)
                )
            } catch (_: Throwable) {
                null
            }
        } else null
    }

    val precomputedWordLayouts = remember(precomputedLayout, wordRanges, isCurrent) {
        if (!isCurrent) return@remember emptyList<WordLayoutData>()
        val layout = precomputedLayout ?: return@remember emptyList<WordLayoutData>()
        buildWordLayouts(layout, wordRanges)
    }

    var textLayoutResult by remember(line) { mutableStateOf(precomputedLayout) }
    var wordLayouts by remember(line) { mutableStateOf(precomputedWordLayouts) }
    val finishedHighlightPath = remember(line) { androidx.compose.ui.graphics.Path() }

    LaunchedEffect(precomputedLayout, precomputedWordLayouts) {
        if (textLayoutResult == null && precomputedLayout != null) {
            textLayoutResult = precomputedLayout
        }
        if (wordLayouts.isEmpty() && precomputedWordLayouts.isNotEmpty()) {
            wordLayouts = precomputedWordLayouts
        }
    }

    val effectivePlaybackPosition = if (isCurrent && animationMode != LyricsAnimationMode.AURALIS) {
        positionState.value + offsetMs
    } else if (isPast) {
        line.effectiveEndTime ?: (line.time + 10_000L)
    } else {
        0L
    }

    val effectiveLineColor = when {
        isPlain -> Color.White.copy(alpha = 0.95f)
        isPast -> Color.White.copy(alpha = 0.60f)
        else -> Color.White.copy(alpha = if (lyricsMode == LyricsMode.CINEMA) 0.30f else 0.35f)
    }
    val effectiveAccentColor = Color.White

    val blurModifier = if (standardBlur && animatedBlur > 0.1f) {
        Modifier.blur(
            radius = animatedBlur.dp,
            edgeTreatment = BlurredEdgeTreatment.Unbounded
        )
    } else Modifier

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = effectiveAlpha
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .then(blurModifier)
            .padding(
                vertical = if (line.isBackground) 1.dp
                           else (3.dp * (lineSpacingMultiplier / 1.3f).coerceIn(0.85f, 1.4f)),
                horizontal = 4.dp
            ),
        horizontalAlignment = horizontalAlignment
    ) {
        val textStyle = TextStyle(
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textAlign = textAlign,
            letterSpacing = (-0.4).sp,
            lineHeight = (fontSize.value * 1.30f * (lineSpacingMultiplier / 1.3f).coerceIn(0.85f, 1.4f)).sp
        )

        if (!isCurrent) {
            // Clean un-highlighted presentation with SemiBold weight & native depth-of-field alpha
            Text(
                text = line.text,
                style = textStyle,
                color = textColor,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
                lineHeight = (fontSize.value * 1.30f * (lineSpacingMultiplier / 1.3f).coerceIn(0.85f, 1.4f)).sp,
                letterSpacing = (-0.4).sp,
                overflow = TextOverflow.Visible
            )
        } else {
            // Active line: choose animation mode
            when (animationMode) {
                LyricsAnimationMode.AURALIS -> {
                    if (hasWordTiming) {
                        // High-performance draw-phase word-by-word karaoke highlight
                        Text(
                            text = line.text,
                            style = textStyle,
                            color = if (line.isBackground) Color.White.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.35f),
                            textAlign = textAlign,
                            lineHeight = (fontSize.value * 1.30f * (lineSpacingMultiplier / 1.3f).coerceIn(0.85f, 1.4f)).sp,
                            letterSpacing = (-0.4).sp,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawWithContent {
                                    // 1. Draw inactive unhighlighted base text
                                    drawContent()

                                    val layout = textLayoutResult ?: precomputedLayout ?: return@drawWithContent
                                    val activeWordLayouts = if (wordLayouts.isNotEmpty()) wordLayouts else precomputedWordLayouts
                                    if (activeWordLayouts.isEmpty()) return@drawWithContent

                                    // Sample clock position inside DrawScope: does NOT cause recomposition/re-layout
                                    val currentMs = positionState.value
                                    val adjustedMs = currentMs + offsetMs

                                    // 2. Separate finished words from currently active sweeping word
                                    val activeSweep = updateWordHighlightState(
                                        finishedPath = finishedHighlightPath,
                                        wordLayouts = activeWordLayouts,
                                        adjustedMs = adjustedMs
                                    )

                                    // 3. Draw fully sung words with glow shadow (batched into one draw call)
                                    if (!finishedHighlightPath.isEmpty) {
                                        clipPath(finishedHighlightPath) {
                                            drawText(
                                                textLayoutResult = layout,
                                                color = Color.White,
                                                shadow = Shadow(
                                                    color = Color.White.copy(alpha = 0.60f),
                                                    blurRadius = 8f,
                                                    offset = Offset.Zero
                                                )
                                            )
                                        }
                                    }

                                    // 4. Draw currently active sweeping word with a clean clipRect sweep
                                    if (activeSweep != null && activeSweep.progress > 0f) {
                                        val activeItem = activeSweep.activeItem
                                        val progress = activeSweep.progress
                                        val bounds = activeItem.bounds

                                        if (!bounds.isEmpty) {
                                            if (activeItem.isSingleLine) {
                                                val wordLineTop = activeItem.lineTop
                                                val wordLineBottom = activeItem.lineBottom
                                                val sweepTop = wordLineTop
                                                val sweepBottom = wordLineBottom

                                                // Sweep edge: progress maps linearly across the word bounds
                                                val sweepEdge = if (activeItem.isRtl) {
                                                    bounds.right - bounds.width * progress
                                                } else {
                                                    bounds.left + bounds.width * progress
                                                }

                                                val clipLeft = if (activeItem.isRtl) sweepEdge else bounds.left - 6f
                                                val clipRight = if (activeItem.isRtl) bounds.right + 6f else sweepEdge

                                                clipRect(
                                                    left = clipLeft,
                                                    top = sweepTop,
                                                    right = clipRight,
                                                    bottom = sweepBottom
                                                ) {
                                                    drawText(
                                                        textLayoutResult = layout,
                                                        color = Color.White,
                                                        shadow = Shadow(
                                                            color = Color.White.copy(alpha = 0.60f),
                                                            blurRadius = 8f,
                                                            offset = Offset.Zero
                                                        )
                                                    )
                                                }
                                            } else {
                                                // Multi-line wrapped span fallback: clip to the actual word path
                                                clipPath(activeItem.wordPath) {
                                                    drawText(
                                                        textLayoutResult = layout,
                                                        color = Color.White,
                                                        shadow = Shadow(
                                                            color = Color.White.copy(alpha = 0.60f),
                                                            blurRadius = 8f,
                                                            offset = Offset.Zero
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                            onTextLayout = { result ->
                                textLayoutResult = result
                                wordLayouts = buildWordLayouts(result, wordRanges)
                            }
                        )
                    } else {
                        // Line-sync fallback or inactive/past line
                        Text(
                            text = line.text,
                            style = textStyle,
                            color = textColor,
                            textAlign = textAlign,
                            modifier = Modifier.fillMaxWidth(),
                            lineHeight = (fontSize.value * 1.30f * (lineSpacingMultiplier / 1.3f).coerceIn(0.85f, 1.4f)).sp,
                            letterSpacing = (-0.4).sp,
                            overflow = TextOverflow.Visible
                        )
                    }
                }
                LyricsAnimationMode.FADE -> {
                    BasicWordLyricsLine(
                        mode = LyricsAnimationMode.FADE,
                        line = line,
                        words = effectiveWords,
                        isActive = isCurrent,
                        effectivePlaybackPosition = effectivePlaybackPosition,
                        lineColor = effectiveLineColor,
                        accentColor = effectiveAccentColor,
                        textAlign = textAlign,
                        alignment = horizontalAlignment,
                        fontSizeSp = fontSize.value,
                        lineSpacingMultiplier = lineSpacingMultiplier,
                        enableGlowEffect = enableGlowEffect
                    )
                }
                LyricsAnimationMode.GLOW -> {
                    AppleMusicLyricsLine(
                        isV2 = false,
                        line = line,
                        nextLineTime = nextLineTime,
                        words = effectiveWords,
                        isActive = isCurrent,
                        effectivePlaybackPosition = effectivePlaybackPosition,
                        lineColor = effectiveLineColor,
                        accentColor = effectiveAccentColor,
                        textAlign = textAlign,
                        alignment = horizontalAlignment,
                        fontSizeSp = fontSize.value,
                        lineSpacingMultiplier = lineSpacingMultiplier
                    )
                }
                LyricsAnimationMode.APPLE_MUSIC_V2 -> {
                    AppleMusicLyricsLine(
                        isV2 = true,
                        line = line,
                        nextLineTime = nextLineTime,
                        words = effectiveWords,
                        isActive = isCurrent,
                        effectivePlaybackPosition = effectivePlaybackPosition,
                        lineColor = effectiveLineColor,
                        accentColor = effectiveAccentColor,
                        textAlign = textAlign,
                        alignment = horizontalAlignment,
                        fontSizeSp = fontSize.value,
                        lineSpacingMultiplier = lineSpacingMultiplier
                    )
                }
                LyricsAnimationMode.LYRICS_V2_FLUID -> {
                    LyricsV2FluidLine(
                        line = line,
                        words = effectiveWords,
                        isActive = isCurrent,
                        isPast = isPast,
                        effectivePlaybackPosition = effectivePlaybackPosition,
                        accentColor = effectiveAccentColor,
                        inactiveAlpha = if (lyricsMode == LyricsMode.CINEMA) 0.35f else 0.40f,
                        fontSizeSp = fontSize.value,
                        lineSpacingMultiplier = lineSpacingMultiplier,
                        textAlign = textAlign,
                        alignment = horizontalAlignment
                    )
                }
                LyricsAnimationMode.METRO_LYRICS -> {
                    MetroLyricsLine(
                        line = line,
                        nextLineTime = nextLineTime,
                        words = effectiveWords,
                        isActive = isCurrent,
                        distanceFromCurrent = distanceFromCurrent,
                        effectivePlaybackPosition = effectivePlaybackPosition,
                        lineColor = effectiveLineColor,
                        accentColor = effectiveAccentColor,
                        textAlign = textAlign,
                        alignment = horizontalAlignment,
                        fontSizeSp = fontSize.value,
                        lineSpacingMultiplier = lineSpacingMultiplier,
                        isPlaying = isPlaying
                    )
                }
            }
        }

        if (!line.translatedText.isNullOrBlank()) {
            val cleanTranslation = remember(line.translatedText) {
                line.translatedText.replace(Regex("""[\u0300-\u036F\u25CC\u093C\u093D]"""), "").trim()
            }
            if (cleanTranslation.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = cleanTranslation,
                    fontSize = (fontSize.value * 0.44f).coerceIn(12f, 16f).sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = if (isCurrent) 0.66f else 0.32f),
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


/**
 * Modern circular progress countdown during song instrumental intros.
 *
 * For MetroLyrics, displays the thicker 40dp / 5.0dp MetroLyrics style.
 * For all other animation modes (Auralis Default, Apple Music, Fade, Glow, etc.),
 * displays the classic thin 34dp / 3.0dp style.
 */
@Composable
internal fun InstrumentalIntroIndicator(
    currentTimeMsState: State<Long>,
    introDurationMs: Long,
    modifier: Modifier = Modifier,
    isMetroLyrics: Boolean = false,
    indicatorSize: Dp = if (isMetroLyrics) com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.MetroIndicatorSize else com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.StandardIndicatorSize,
    strokeWidth: Dp = if (isMetroLyrics) com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.MetroStrokeWidth else com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.StandardStrokeWidth,
    gapSize: Dp = if (isMetroLyrics) com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.MetroTrackGapSize else com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults.StandardTrackGapSize,
    onSkipIntro: (() -> Unit)? = null
) {
    val currentTimeMs = currentTimeMsState.value
    // Reaches 100% 650ms before line 0 starts singing, then completes and collapses smoothly
    val effectiveEnd = (introDurationMs - 650L).coerceAtLeast(1000L)
    val progress = (currentTimeMs.toFloat() / effectiveEnd.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 100, easing = androidx.compose.animation.core.LinearEasing),
        label = "introProgress"
    )

    val clickableSize = if (isMetroLyrics || indicatorSize.value >= 40f) 44.dp else 40.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(clickableSize)
                .then(
                    if (onSkipIntro != null) {
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onSkipIntro)
                    } else Modifier
                )
        ) {
            WavyProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier.size(indicatorSize),
                color = Color.White.copy(alpha = 0.95f),
                trackColor = Color.White.copy(alpha = 0.18f),
                strokeWidth = strokeWidth,
                gapSize = gapSize,
            )
        }
    }
}
