package com.auralis.music.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.auralis.music.ui.theme.AuralisKaraokeActive
import com.auralis.music.ui.theme.AuralisKaraokeInactive
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.auralis.music.ui.theme.dynamicPalette
import com.auralis.music.ui.theme.motionTween



/**
 * Clean, Immersive 60fps Synced Lyrics View:
 * - Line-level and syllable-level highlights
 * - Smooth spring-physics auto-scrolling centering active line
 * - Explicit states for Loading, Word-Synced, Line-Synced, Plain, Instrumental, and Unavailable
 */
@Composable
fun SyncedLyricsView(
    lyrics: LyricsData?,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    lyricsMode: LyricsMode = LyricsMode.CINEMA,
    offsetMs: Long = 0,
    onOffsetChange: ((Long) -> Unit)? = null,
    onSearchManually: (() -> Unit)? = null
) {
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = MaterialTheme.dynamicPalette.tintA,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Syncing Lyrics...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val isInstrumental = lyrics != null && (lyrics.lines.all { it.isInstrumental } || lyrics.plainLyrics == "[Instrumental]")

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
            }
        }
        return
    }

    if (lyrics == null || lyrics.lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (lyrics?.lines?.isEmpty() == true) "♪ Instrumental ♪" else "Lyrics not available",
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

    val scrollState = rememberScrollState()
    val isSynced = lyrics.syncType != SyncType.PLAIN
    val activeIndex = remember(currentPositionMs, offsetMs, lyrics.lines, isSynced) {
        if (isSynced) LyricsEngine.findActiveLyricIndex(lyrics.lines, currentPositionMs, offsetMs) else -1
    }

    // Store LayoutCoordinates references (NOT positions) for window-space measurement
    var viewportCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val lyricCoordRefs = remember { mutableMapOf<Int, androidx.compose.ui.layout.LayoutCoordinates>() }

    // Persistent float animatable tracking scroll position and velocity across transitions
    val scrollAnim = remember { Animatable(scrollState.value.toFloat()) }

    // Clear coordinate refs and sync animatable when song lyrics change
    LaunchedEffect(lyrics) {
        lyricCoordRefs.clear()
        scrollAnim.snapTo(0f)
    }

    // Human drag detection: pause auto-scrolling during touch gestures, resume after 3.5s
    var isUserInteracting by remember { mutableStateOf(false) }

    LaunchedEffect(scrollState.interactionSource) {
        scrollState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isUserInteracting = true
                    scrollAnim.snapTo(scrollState.value.toFloat())
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    delay(3500)
                    isUserInteracting = false
                }
            }
        }
    }

    // Keep scrollAnim in sync during external/user drag
    LaunchedEffect(isUserInteracting, scrollState.value) {
        if (isUserInteracting) {
            scrollAnim.snapTo(scrollState.value.toFloat())
        }
    }

    // The viewport container — its window bounds define where the lyrics area is on screen
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { viewportCoords = it }
    ) {
        // Continuous, velocity-preserving auto-scroll:
        // When activeIndex changes, seamlessly glides from current position & velocity
        // to the exact viewport center without stopping, stuttering, or restarting from scratch.
        LaunchedEffect(activeIndex, isSynced, isUserInteracting) {
            if (!isSynced || activeIndex < 0 || activeIndex >= lyrics.lines.size || isUserInteracting) return@LaunchedEffect

            var lCoords = lyricCoordRefs[activeIndex]
            if (lCoords == null || !lCoords.isAttached) {
                withFrameNanos { }
                lCoords = lyricCoordRefs[activeIndex]
            }

            val vCoords = viewportCoords
            if (vCoords == null || lCoords == null || !vCoords.isAttached || !lCoords.isAttached) return@LaunchedEffect

            // Measure both in the SAME coordinate space (window coordinates)
            val viewportBounds = vCoords.boundsInWindow()
            val lyricBounds = lCoords.boundsInWindow()

            val viewportCenter = (viewportBounds.top + viewportBounds.bottom) / 2f
            val lyricCenter = (lyricBounds.top + lyricBounds.bottom) / 2f
            val delta = lyricCenter - viewportCenter

            val targetScroll = (scrollState.value + delta.roundToInt())
                .coerceIn(0, scrollState.maxValue)

            val currentScroll = scrollState.value.toFloat()
            val distance = kotlin.math.abs(targetScroll.toFloat() - currentScroll)

            if (distance > 1f) {
                // Ensure animatable baseline matches current scroll if out of sync
                if (kotlin.math.abs(scrollAnim.value - currentScroll) > 2f) {
                    scrollAnim.snapTo(currentScroll)
                }

                // Stiffness tuned for natural floating speed with zero bounce (DampingRatioNoBouncy = 1.0f).
                // Initial velocity is preserved across line transitions so movement continues seamlessly.
                val stiffness = when {
                    distance < 300f -> 85f   // Gentle, organic float for adjacent line transitions
                    distance < 800f -> 130f  // Smooth fluid glide for multi-line shifts
                    else -> 240f            // Responsive catch-up for manual seek jumps
                }

                scrollState.scroll {
                    var lastValue = scrollAnim.value
                    scrollAnim.animateTo(
                        targetValue = targetScroll.toFloat(),
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = stiffness,
                            visibilityThreshold = 0.5f
                        )
                    ) {
                        val deltaPx = value - lastValue
                        lastValue = value
                        scrollBy(deltaPx)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    top = if (isSynced) 16.dp else 40.dp,
                    bottom = if (isSynced) maxHeight / 2 else 140.dp,
                    start = 20.dp,
                    end = 20.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (lyricsMode == LyricsMode.CINEMA) 26.dp else 18.dp)
        ) {
            lyrics.lines.forEachIndexed { index, line ->
                val isCurrent = isSynced && index == activeIndex
                val isPast = isSynced && index < activeIndex

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            lyricCoordRefs[index] = coords
                        }
                ) {
                    LyricLineRow(
                        line = line,
                        isCurrent = isCurrent,
                        isPast = isPast,
                        lyricsMode = lyricsMode,
                        syncType = lyrics.syncType,
                        currentTimeMs = currentPositionMs + offsetMs,
                        onClick = {
                            if (isSynced) {
                                isUserInteracting = false
                                onSeekTo(line.time)
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Lyric line row supporting syllable-level richsync highlights and depth blurs.
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isCurrent: Boolean,
    isPast: Boolean,
    lyricsMode: LyricsMode,
    syncType: SyncType,
    currentTimeMs: Long,
    onClick: () -> Unit
) {
    val dynamicPalette = MaterialTheme.dynamicPalette
    val isPlain = syncType == SyncType.PLAIN

    val targetAlpha = when {
        isPlain -> 0.90f
        isCurrent -> 1.0f
        isPast -> if (lyricsMode == LyricsMode.CINEMA) 0.35f else 0.45f
        else -> if (lyricsMode == LyricsMode.CINEMA) 0.28f else 0.40f
    }
    val animAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = motionTween(AuralisDuration.Standard, AuralisEasing.Standard),
        label = "LyricAlpha"
    )

    val fontSize = when {
        isPlain -> 20.sp
        isCurrent -> if (lyricsMode == LyricsMode.CINEMA) 30.sp else 24.sp
        else -> if (lyricsMode == LyricsMode.CINEMA) 24.sp else 20.sp
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .alpha(animAlpha)
            .padding(vertical = 4.dp)
    ) {
        if (syncType == SyncType.RICHSYNC && !line.words.isNullOrEmpty()) {
            RichSyncLine(
                words = line.words,
                currentTimeMs = currentTimeMs,
                fontSize = fontSize,
                activeGlow = dynamicPalette.tintA
            )
        } else {
            Text(
                text = line.text,
                fontSize = fontSize,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isCurrent) AuralisKaraokeActive else AuralisKaraokeInactive,
                lineHeight = (fontSize.value * 1.35f).sp
            )
        }
    }
}

/**
 * Renders syllable-by-syllable richsync karaoke text with real-time progressive fill.
 */
@Composable
private fun RichSyncLine(
    words: List<LyricWord>,
    currentTimeMs: Long,
    fontSize: androidx.compose.ui.unit.TextUnit,
    activeGlow: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        words.forEach { word ->
            val wordProgress = LyricsEngine.calculateWordProgress(word, currentTimeMs)
            val isWordActive = wordProgress > 0f && wordProgress < 1f
            val isWordCompleted = wordProgress >= 1f

            val wordColor by animateColorAsState(
                targetValue = when {
                    isWordCompleted -> AuralisKaraokeActive
                    isWordActive -> activeGlow
                    else -> AuralisKaraokeInactive
                },
                animationSpec = tween(120),
                label = "WordColor"
            )

            Text(
                text = word.word,
                fontSize = fontSize,
                fontWeight = if (isWordCompleted || isWordActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = wordColor,
                lineHeight = (fontSize.value * 1.35f).sp
            )
        }
    }
}
