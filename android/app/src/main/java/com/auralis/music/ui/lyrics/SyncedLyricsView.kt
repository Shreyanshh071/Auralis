package com.auralis.music.ui.lyrics

import com.kyant.backdrop.backdrops.layerBackdrop
import com.auralis.music.R
import com.auralis.music.ui.i18n.str

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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import kotlinx.coroutines.flow.first
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
    readingFocusFraction: Float = LYRICS_ANCHOR_RATIO,
    listState: LazyListState = rememberLazyListState(),
    standardLyricsBlur: Boolean = com.auralis.music.ui.theme.LocalAppearanceSettings.current.standardLyricsBlur,
    loadingAlignment: Alignment = Alignment.Center
) {
    val japaneseContext = remember(lyrics?.lines, lyrics?.plainLyrics) {
        com.auralis.music.domain.lyrics.LyricsRomanizationContext.isJapanese(lyrics)
    }
    CompositionLocalProvider(LocalJapaneseRomanizationContext provides japaneseContext) {
        SyncedLyricsContent(
            lyrics = lyrics,
            positionState = positionState,
            onSeekTo = onSeekTo,
            modifier = modifier,
            isLoading = isLoading,
            lyricsMode = lyricsMode,
            offsetMs = offsetMs,
            onOffsetChange = onOffsetChange,
            onSearchManually = onSearchManually,
            track = track,
            lyricsClockSource = lyricsClockSource,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            audioLeadingSilenceMs = audioLeadingSilenceMs,
            readingFocusFraction = readingFocusFraction,
            listState = listState,
            standardLyricsBlur = standardLyricsBlur,
            loadingAlignment = loadingAlignment
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SyncedLyricsContent(
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
    readingFocusFraction: Float = LYRICS_ANCHOR_RATIO,
    listState: LazyListState = rememberLazyListState(),
    standardLyricsBlur: Boolean = com.auralis.music.ui.theme.LocalAppearanceSettings.current.standardLyricsBlur,
    loadingAlignment: Alignment = Alignment.Center
) {
    val animationsActive = com.auralis.music.ui.player.LocalPlayerModalVisualState.current?.animationsActive != false
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
    // The clock is read inside snapshotFlow, never as an effect key: keying on positionState.value
    // recomposed this whole view on every lyric-clock frame, which is what made autoscroll stutter
    // in every style but Metro (Metro's view never read it at this level).
    LaunchedEffect(lyrics, track?.id) {
        val fvLine = firstVocalLine ?: return@LaunchedEffect
        if (hasLoggedFirstVocalDiag) return@LaunchedEffect
        val curPos = snapshotFlow { positionState.value + offsetMs }
            .first { it >= (fvLine.time - 50L) && it <= (fvLine.time + 4000L) }
        run {
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
                    animationActive = animationsActive,
                    isIndeterminate = true,
                    strokeWidth = 4.dp,
                    trackColor = Color.Transparent,
                    progressColor = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier.size(52.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = str(R.string.syncing_lyrics),
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
            lyrics.plainLyrics!!.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { LyricLine(time = 0L, text = com.auralis.music.data.parser.WordTiming.healSplitWordsInText(it)) }
        } else emptyList()
    }
    val hasMultipleVocalists = remember(effectiveLines) { MetroSpeakerLayout.build(effectiveLines) != null }

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
                    text = str(R.string.instrumental_track),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = str(R.string.this_composition_appears_to_have_no_voca),
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
                            contentDescription = str(R.string.search),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(str(R.string.search_lyrics_manually))
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
                    text = str(R.string.lyrics_not_available),
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
                            contentDescription = str(R.string.search),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(str(R.string.search_lyrics_manually))
                    }
                }
            }
        }
        return
    }

    val isSynced = (lyrics.syncType != SyncType.PLAIN || effectiveLines.any { it.time > 0L }) && effectiveLines.isNotEmpty()

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

    // Every style except Spicy Lyrics: the Vivi Music port.
    com.auralis.music.ui.lyrics.vivi.ViviLyricsView(
        lyrics = lyrics,
        sourceLines = effectiveLines,
        isSynced = isSynced,
        positionState = positionState,
        offsetMs = offsetMs,
        onSeekTo = onSeekTo,
        track = track,
        lyricsStandardBlur = standardLyricsBlur,
        modifier = modifier
    )
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
    // Use the same physical distance on both sides of the active line. Scaling each
    // side separately made past lines almost sharp while future lines blurred deeply.
    val falloffDistance = minOf(
        activeCenter - viewportStartPx,
        viewportEndPx - activeCenter
    ).coerceAtLeast(1f)
    val rawProgress = (kotlin.math.abs(lineCenter - activeCenter) / falloffDistance).coerceIn(0f, 1f)
    // Keep a small crisp band around the active line, then blur toward either edge.
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
