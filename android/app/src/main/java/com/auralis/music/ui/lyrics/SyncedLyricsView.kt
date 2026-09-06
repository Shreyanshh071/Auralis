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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy

import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsMode
import com.auralis.music.domain.model.SyncType
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
    lyricsClockSource: com.auralis.music.data.service.PlaybackClockSource? = null
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
                  whether word timing exists: $hasWordTiming
                  line count: ${lyrics.lines.size}
                  track: "${track?.title}" by "${track?.artist}"
                ======================================================================
            """.trimIndent())
        }
    }

    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
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
        else if (lyrics.lines.isNotEmpty()) lyrics.lines
        else if (!lyrics.plainLyrics.isNullOrBlank()) {
            lyrics.plainLyrics.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { LyricLine(time = 0L, text = it) }
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
            contentAlignment = Alignment.Center
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
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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

    // Derived, not remembered against the position: composition re-runs when the
    // active *line* changes, not when the millisecond does.
    val activeIndexState = remember(effectiveLines, isSynced, offsetMs, positionState) {
        derivedStateOf {
            if (!isSynced) -1
            else LyricsEngine.findActiveLyricIndex(effectiveLines, positionState.value, offsetMs)
        }
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // Human drag detection: pause auto-scrolling during touch gestures, resume after 3.5s
    var isUserInteracting by remember { mutableStateOf(false) }

    // Lyric Selection & Sharing State (Capped strictly at max 5 lines)
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showShareSheet by remember { mutableStateOf(false) }
    var shareLyricsText by remember { mutableStateOf("") }

    var dragResetJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    dragResetJob?.cancel()
                    isUserInteracting = true
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragResetJob?.cancel()
                    dragResetJob = launch {
                        delay(3500)
                        isUserInteracting = false
                    }
                }
            }
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
        val targetCenterFraction = 0.45f
        val topPaddingDp = 20.dp
        val bottomPaddingDp = (maxHeight * (1f - targetCenterFraction) + 40.dp).coerceAtLeast(160.dp)

        // Track whether initial scroll has completed
        var hasInitialCentered by remember { mutableStateOf(false) }
        // Track last centered index to detect large jumps (tap-to-seek) vs natural progression
        var lastCenteredIndex by remember { mutableIntStateOf(-1) }

        // Initial centering on first composition / tab switch
        LaunchedEffect(lyrics) {
            hasInitialCentered = false
            lastCenteredIndex = -1
        }

        // Suspend function to accurately and smoothly center any lyric line in the viewport
        val centerActiveLine: suspend (targetIndex: Int, animate: Boolean) -> Unit = { targetIndex, animate ->
            if (targetIndex in effectiveLines.indices) {
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
                        listState.scrollToItem(targetIndex)
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
            }
        }

        // Automatic, smooth centering of active lyric line.
        // Driven by a snapshotFlow so the position clock never re-runs this
        // composable — the effect wakes only when the active index or the user's
        // touch state actually changes.
        LaunchedEffect(activeIndexState, isSynced, appearance.autoScrollLyrics, effectiveLines) {
            if (!isSynced || !appearance.autoScrollLyrics) return@LaunchedEffect
            snapshotFlow {
                Triple(activeIndexState.value, isUserInteracting, selectedIndices.isNotEmpty())
            }
                .distinctUntilChanged()
                .collectLatest { (activeIndex, interacting, selecting) ->
                    if (interacting || selecting) return@collectLatest
                    if (activeIndex < 0) {
                        if (effectiveLines.isNotEmpty()) {
                            centerActiveLine(0, hasInitialCentered)
                        }
                        return@collectLatest
                    }
                    if (activeIndex >= effectiveLines.size) return@collectLatest

                    // Snap (no animation) for large index jumps like tap-to-seek;
                    // animate smoothly only for natural 1-2 line progressions.
                    val indexDelta = if (lastCenteredIndex >= 0) kotlin.math.abs(activeIndex - lastCenteredIndex) else Int.MAX_VALUE
                    val shouldAnimate = hasInitialCentered && indexDelta <= 2
                    centerActiveLine(activeIndex, shouldAnimate)
                    lastCenteredIndex = activeIndex
                    hasInitialCentered = true
                }
        }

        val isIntroActiveState = remember(isSynced, introDurationMs, offsetMs, positionState) {
            derivedStateOf {
                isSynced && introDurationMs >= 1500L &&
                    (positionState.value + offsetMs).coerceAtLeast(0L) < introDurationMs
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

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.05f to Color.Black,
                            0.78f to Color.Black,
                            0.94f to Color.Transparent,
                            1.0f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentPadding = PaddingValues(
                top = if (isSynced) topPaddingDp else 36.dp,
                bottom = if (isSynced) bottomPaddingDp else 220.dp,
                start = 16.dp,
                end = 16.dp
            ),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                items = effectiveLines,
                key = { index, line -> "${line.time}_$index" }
            ) { index, line ->
                // Reading the derived index here — inside the item's own
                // recomposition scope — keeps line-change invalidation local to
                // the rows instead of the whole view.
                val activeIndex = activeIndexState.value
                val isCurrent = isSynced && index == activeIndex
                val isPast = isSynced && index < activeIndex
                val pastDistance = if (isPast) (activeIndex - index).coerceAtLeast(1) else 0

                val isSelected = selectedIndices.contains(index)
                val isSelectionMode = selectedIndices.isNotEmpty()

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = horizontalAlignment
                ) {
                    if (index == 0 && isIntroActiveState.value) {
                        InstrumentalIntroIndicator(
                            currentTimeMsState = introTimeState,
                            introDurationMs = introDurationMs,
                            onSkipIntro = { onSeekTo(introDurationMs) },
                            modifier = Modifier.padding(bottom = 16.dp)
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
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                } else {
                                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
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
                            isSelected = isSelected,
                            lyricsMode = lyricsMode,
                            syncType = lyrics.syncType,
                            textAlign = textAlign,
                            horizontalAlignment = horizontalAlignment,
                            positionState = positionState,
                            offsetMs = offsetMs,
                            rowMaxWidthPx = rowMaxWidthPx
                        )
                    }
                }
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

/**
 * Clean, high-contrast Line-Synced row with smooth alpha animations, bold active state,
 * and AI translated text support.
 */
/**
 * Precomputed geometry for a single word within a laid-out lyric line.
 * Created once when layout changes to guarantee zero allocations per frame during playback.
 */
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
                val prevItem = activeSweep!!.activeItem
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

/**
 * Clean, high-performance word-by-word karaoke lyric line row:
 * - When [line.hasWordTiming] && [isCurrent]: paints active word sweep in Draw phase with zero recompositions.
 * - When [line.hasWordTiming] is false: falls back gracefully to line-synced highlighting.
 * - Preserves vocal rests without bleeding highlight across silence.
 * - Styles background vocals distinctly (85% scale + italic).
 * - Preserves natural character boundaries and complex Unicode scripts (Indic matras, Arabic shaping).
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    nextLineTime: Long? = null,
    isCurrent: Boolean,
    isPast: Boolean,
    pastDistance: Int = 0,
    isSelected: Boolean = false,
    lyricsMode: LyricsMode,
    syncType: SyncType,
    textAlign: TextAlign,
    horizontalAlignment: Alignment.Horizontal,
    positionState: State<Long>,
    offsetMs: Long,
    rowMaxWidthPx: Int
) {
    val isPlain = syncType == SyncType.PLAIN

    // Depth-of-field is conveyed via alpha + subtle scale only (zero GPU blur cost).
    // Modifier.blur() was removed because it forced an offscreen render pass per item
    // (~20 simultaneous GPU texture allocations during scroll, destroying frame budgets).
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

    // Phase 4B-B: Word-level karaoke sweep/progression runs ONLY when genuine word timing
    // (RICHSYNC) is supplied by the provider. For line-synced lyrics with no genuine word timing,
    // do NOT fabricate word timestamps or subdivide durations. The entire active line is
    // presented as active for the line's genuine interval.
    val effectiveWords = remember(line.words, syncType) {
        resolveEffectiveWords(line, syncType)
    }

    val hasWordTiming = !effectiveWords.isNullOrEmpty()

    val targetAlpha = when {
        isPlain -> 0.95f
        isCurrent -> 1.0f
        isPast -> if (lyricsMode == LyricsMode.CINEMA) 0.55f else 0.58f
        else -> if (lyricsMode == LyricsMode.CINEMA) 0.32f else 0.38f
    }
    val animAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = motionTween(AuralisDuration.Standard, AuralisEasing.Standard),
        label = "LyricAlpha"
    )

    // FIX 1: Word-synced active lines must NOT be visually suppressed by the 300ms alpha fade-in.
    // Active word-synced lyrics snap to 1.0f immediately at the provider timestamp.
    // Ordinary line-synced lyrics and past transitions retain smooth alpha motion.
    val effectiveAlpha = if (hasWordTiming && isCurrent) 1.0f else animAlpha

    // Issue 2 Fix: Deterministic line wrapping based on active typography metrics.
    // Layout measurements and wrapping remain constant regardless of active state to eliminate jumping/reflow.
    val baseFontSize = when {
        isPlain -> 22.sp
        lyricsMode == LyricsMode.CINEMA -> 30.sp
        else -> 26.sp
    }
    // Background vocals (ad-libs, harmonies) styled distinctly
    val fontSize = if (line.isBackground) (baseFontSize.value * 0.85f).sp else baseFontSize
    val fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal
    val fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold

    val textColor = when {
        isCurrent -> Color.White
        isPast -> Color.White.copy(alpha = 0.65f)
        else -> Color.White.copy(alpha = 0.38f)
    }

    // Precompute character mapping for words when line changes
    val wordRanges = remember(line, effectiveWords) {
        if (hasWordTiming) LyricsEngine.mapWordsToLineSpans(line.text, effectiveWords)
        else emptyList()
    }

    // FIX 2: Precompute active text layout and word layout data using TextMeasurer.
    // This ensures textLayoutResult and wordLayouts are ALREADY populated and ready
    // on the exact frame isCurrent flips to true, eliminating the 1-2 frame layout dead zone.
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val activeTextStyle = remember(lyricsMode, line.isBackground, textAlign) {
        val activeBaseSize = if (lyricsMode == LyricsMode.CINEMA) 30.sp else 26.sp
        val activeSize = if (line.isBackground) (activeBaseSize.value * 0.85f).sp else activeBaseSize
        val activeFontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal
        TextStyle(
            fontSize = activeSize,
            fontWeight = FontWeight.ExtraBold,
            fontStyle = activeFontStyle,
            textAlign = textAlign,
            lineHeight = (activeSize.value * 1.34f).sp
        )
    }

    val precomputedLayout = remember(line, activeTextStyle, rowMaxWidthPx) {
        if (hasWordTiming && rowMaxWidthPx > 0) {
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

    val precomputedWordLayouts = remember(precomputedLayout, wordRanges) {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = effectiveAlpha
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .padding(vertical = if (line.isBackground) 2.dp else 4.dp, horizontal = 4.dp),
        horizontalAlignment = horizontalAlignment
    ) {
        val textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textAlign = textAlign,
            lineHeight = (fontSize.value * 1.34f).sp
        )

        if (isCurrent && hasWordTiming) {
            // High-performance draw-phase word-by-word karaoke highlight
            Text(
                text = line.text,
                style = textStyle,
                color = Color.White.copy(alpha = 0.35f),
                textAlign = textAlign,
                lineHeight = (fontSize.value * 1.34f).sp,
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
                        // (Replaces the previous saveLayer + DstIn approach which allocated
                        //  an offscreen bitmap every single frame — one of the most expensive
                        //  Canvas operations on Android.)
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
                lineHeight = (fontSize.value * 1.34f).sp,
                overflow = TextOverflow.Visible
            )
        }

        if (!line.translatedText.isNullOrBlank()) {
            val cleanTranslation = remember(line.translatedText) {
                line.translatedText.replace(Regex("""[\u0300-\u036F\u25CC\u093C\u093D]"""), "").trim()
            }
            if (cleanTranslation.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = cleanTranslation,
                    fontSize = (fontSize.value * 0.52f).coerceAtLeast(14f).sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.40f),
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Animated organic wavy circular countdown & rhythm orbs during song instrumental intros (BetterLyrics / Apple Music design).
 */
@Composable
private fun InstrumentalIntroIndicator(
    currentTimeMsState: State<Long>,
    introDurationMs: Long,
    modifier: Modifier = Modifier,
    onSkipIntro: (() -> Unit)? = null
) {
    val currentTimeMs = currentTimeMsState.value
    val progress = (currentTimeMs.toFloat() / introDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val remainingSec = ((introDurationMs - currentTimeMs).coerceAtLeast(0L) / 1000L) + 1

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .then(
                    if (onSkipIntro != null) {
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onSkipIntro)
                    } else Modifier
                )
        ) {
            // Squirly Organic Wavy Countdown Ring
            com.auralis.music.ui.components.SquirlyProgressRing(
                progress = progress,
                strokeWidth = 4.dp,
                trackColor = Color.White.copy(alpha = 0.18f),
                progressColor = Color.White.copy(alpha = 0.96f),
                waveCount = 6,
                waveAmplitudeRatio = 0.12f,
                modifier = Modifier.fillMaxSize()
            )

            // Pulsing Music Note Icon
            Text(
                text = "♪",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = Shadow(
                        color = Color.White.copy(alpha = 0.85f),
                        blurRadius = 16f,
                        offset = Offset.Zero
                    )
                ),
                modifier = Modifier.graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3 Animated Glowing Apple Music Rhythm Dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotCount = 3
            val activeDotIdx = ((currentTimeMs / 380L) % dotCount).toInt()
            repeat(dotCount) { i ->
                val isDotActive = i == activeDotIdx
                val dotAlpha = if (isDotActive) 0.95f else 0.30f
                val dotScale = if (isDotActive) 1.25f else 1.0f

                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .graphicsLayer {
                            scaleX = dotScale
                            scaleY = dotScale
                        }
                        .background(Color.White.copy(alpha = dotAlpha), CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Intro (${remainingSec}s)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.60f),
            modifier = if (onSkipIntro != null) Modifier.clickable(onClick = onSkipIntro) else Modifier
        )
    }
}

