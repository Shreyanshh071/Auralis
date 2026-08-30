package com.auralis.music.ui.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * High-performance, 60fps Synced Lyrics View:
 * - Immediate centering and auto-scrolling on tab enter or mid-song playback
 * - Bold, vibrant active line highlighting with glowing visual cues
 * - Real-time word-level karaoke fill for RichSync tracks
 * - Touch-to-seek and human drag gesture detection
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    onSearchManually: (() -> Unit)? = null,
    track: com.auralis.music.domain.model.Track? = null
) {
    val dynamicPalette = MaterialTheme.dynamicPalette

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

    val isSynced = lyrics.syncType != SyncType.PLAIN && effectiveLines.any { it.time > 0L }
    val activeIndex = remember(currentPositionMs, offsetMs, effectiveLines, isSynced) {
        if (isSynced) LyricsEngine.findActiveLyricIndex(effectiveLines, currentPositionMs, offsetMs) else -1
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current

    // Human drag detection: pause auto-scrolling during touch gestures, resume after 3.5s
    var isUserInteracting by remember { mutableStateOf(false) }

    // Lyric Selection & Sharing State
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showShareSheet by remember { mutableStateOf(false) }
    var shareLyricsText by remember { mutableStateOf("") }

    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isUserInteracting = true
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    delay(3500)
                    isUserInteracting = false
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
    val horizontalArrangement = when (normalizedPos) {
        "left", "start" -> Arrangement.Start
        "right", "end" -> Arrangement.End
        else -> Arrangement.Center
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        // Offset so active lyric line stays in the comfortable upper-middle zone
        val centerOffsetPx = (viewportHeightPx * 0.25f).toInt()

        // Track whether initial scroll has completed
        var hasInitialCentered by remember { mutableStateOf(false) }

        // Initial centering on first composition / tab switch
        LaunchedEffect(lyrics) {
            hasInitialCentered = false
        }

        // Automatic, smooth centering of active lyric line
        LaunchedEffect(activeIndex, isSynced, isUserInteracting, appearance.autoScrollLyrics) {
            if (!isSynced || activeIndex < 0 || activeIndex >= effectiveLines.size) return@LaunchedEffect
            if (isUserInteracting || !appearance.autoScrollLyrics) return@LaunchedEffect

            val scrollTargetOffset = if (activeIndex <= 0) 0 else -centerOffsetPx

            if (!hasInitialCentered) {
                listState.scrollToItem(
                    index = activeIndex,
                    scrollOffset = scrollTargetOffset
                )
                hasInitialCentered = true
            } else {
                listState.animateScrollToItem(
                    index = activeIndex,
                    scrollOffset = scrollTargetOffset
                )
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
                            0.55f to Color.Black,
                            0.92f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentPadding = PaddingValues(
                top = 20.dp,
                bottom = 220.dp,
                start = 16.dp,
                end = 16.dp
            ),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── INSTRUMENTAL MUSIC INTRO COUNTDOWN (Metrolist / Apple Music style) ──
            val firstLineTime = effectiveLines.firstOrNull()?.time ?: 0L
            val isIntroActive = isSynced && firstLineTime >= 3500L && currentPositionMs < firstLineTime

            if (isIntroActive) {
                item(key = "instrumental_intro_countdown") {
                    InstrumentalIntroIndicator(
                        currentTimeMs = currentPositionMs,
                        introDurationMs = firstLineTime
                    )
                }
            }

            itemsIndexed(
                items = effectiveLines,
                key = { index, line -> "${line.time}_$index" }
            ) { index, line ->
                val isCurrent = isSynced && index == activeIndex
                val isPast = isSynced && index < activeIndex

                val isSelected = selectedIndices.contains(index)
                val isSelectionMode = selectedIndices.isNotEmpty()

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
                                        if (selectedIndices.size < 6) selectedIndices + index else selectedIndices
                                    }
                                } else if (isSynced && appearance.changeLyricsOnTap) {
                                    isUserInteracting = false
                                    onSeekTo(line.time)
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(
                                            index = index,
                                            scrollOffset = if (index <= 0) 0 else -centerOffsetPx
                                        )
                                    }
                                }
                            },
                            onLongClick = {
                                isUserInteracting = true
                                selectedIndices = if (isSelected) {
                                    selectedIndices - index
                                } else {
                                    selectedIndices + index
                                }
                            }
                        )
                ) {
                    LyricLineRow(
                        line = line,
                        isCurrent = isCurrent,
                        isPast = isPast,
                        lyricsMode = lyricsMode,
                        syncType = lyrics.syncType,
                        currentTimeMs = currentPositionMs + offsetMs,
                        textAlign = textAlign,
                        horizontalAlignment = horizontalAlignment,
                        horizontalArrangement = horizontalArrangement
                    )
                }
            }
        }

        // Floating Action Bar for Selected Lyrics (Matching Screenshot 1)
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
 * Lyric line row supporting syllable-level RichSync highlights and crisp typography without text clipping.
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isCurrent: Boolean,
    isPast: Boolean,
    lyricsMode: LyricsMode,
    syncType: SyncType,
    currentTimeMs: Long,
    textAlign: TextAlign,
    horizontalAlignment: Alignment.Horizontal,
    horizontalArrangement: Arrangement.Horizontal
) {
    val dynamicPalette = MaterialTheme.dynamicPalette
    val isPlain = syncType == SyncType.PLAIN

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

    val fontSize = when {
        isPlain -> 22.sp
        isCurrent -> if (lyricsMode == LyricsMode.CINEMA) 31.sp else 28.sp
        else -> if (lyricsMode == LyricsMode.CINEMA) 22.sp else 20.sp
    }

    val textColor = when {
        isCurrent -> Color.White
        isPast -> Color.White.copy(alpha = 0.65f)
        else -> Color.White.copy(alpha = 0.38f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = animAlpha
            }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = horizontalAlignment
    ) {
        if (isCurrent && !line.words.isNullOrEmpty()) {
            RichSyncLine(
                words = line.words,
                currentTimeMs = currentTimeMs,
                fontSize = fontSize,
                activeGlow = dynamicPalette.tintA,
                horizontalArrangement = horizontalArrangement
            )
        } else {
            Text(
                text = line.text,
                fontSize = fontSize,
                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = textColor,
                textAlign = textAlign,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = if (isCurrent) {
                        Shadow(
                            color = Color.White.copy(alpha = 0.80f),
                            blurRadius = 16f,
                            offset = Offset.Zero
                        )
                    } else null
                ),
                modifier = Modifier.fillMaxWidth(),
                lineHeight = (fontSize.value * 1.34f).sp,
                overflow = TextOverflow.Visible
            )
        }

        if (!line.translatedText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = line.translatedText,
                fontSize = (fontSize.value * 0.52f).coerceAtLeast(14f).sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.40f),
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Renders syllable-by-syllable richsync karaoke text with Apple Music-grade glowing bloom aura.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RichSyncLine(
    words: List<LyricWord>,
    currentTimeMs: Long,
    fontSize: androidx.compose.ui.unit.TextUnit,
    activeGlow: Color,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement
    ) {
        words.forEach { word ->
            val wordProgress = LyricsEngine.calculateWordProgress(word, currentTimeMs)
            val isWordActive = wordProgress > 0f && wordProgress < 1f
            val isWordFinished = wordProgress >= 1f
            val displayWord = if (word.word.endsWith(" ")) word.word else "${word.word} "

            // 1. Apple Music Liquid Wave Gradient Ramp (Soft glowing wave edge)
            val brush = when {
                isWordFinished -> Brush.linearGradient(listOf(Color.White, Color.White))
                isWordActive -> {
                    val p = wordProgress.coerceIn(0.01f, 0.99f)
                    val waveStart = (p - 0.08f).coerceAtLeast(0f)
                    val waveEnd = (p + 0.10f).coerceAtMost(1f)
                    Brush.horizontalGradient(
                        0.0f to Color.White,
                        waveStart to Color.White,
                        p to Color.White.copy(alpha = 0.92f),
                        waveEnd to Color.White.copy(alpha = 0.35f),
                        1.0f to Color.White.copy(alpha = 0.35f)
                    )
                }
                else -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.35f)))
            }

            // 2. Apple Music Subtle Organic Vocal Breathing Wave & Bloom
            val waveLift = if (isWordActive) {
                (kotlin.math.sin(wordProgress * Math.PI.toFloat()) * 3.0f)
            } else 0f

            val waveShadowRadius = if (isWordActive) {
                14f + (10f * kotlin.math.sin(wordProgress * Math.PI.toFloat()))
            } else 0f

            Text(
                text = displayWord,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(
                    brush = brush,
                    shadow = if (isWordActive) {
                        Shadow(
                            color = Color.White.copy(alpha = 0.90f),
                            blurRadius = waveShadowRadius,
                            offset = Offset.Zero
                        )
                    } else null
                ),
                modifier = Modifier.graphicsLayer {
                    translationY = -waveLift
                },
                lineHeight = (fontSize.value * 1.35f).sp
            )
        }
    }
}

/**
 * Animated circular countdown & rhythm orbs during song instrumental intros (Metrolist / Apple Music design).
 */
@Composable
private fun InstrumentalIntroIndicator(
    currentTimeMs: Long,
    introDurationMs: Long,
    modifier: Modifier = Modifier
) {
    val progress = (currentTimeMs.toFloat() / introDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    val remainingSec = ((introDurationMs - currentTimeMs).coerceAtLeast(0L) / 1000L) + 1

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
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
            modifier = Modifier.size(64.dp)
        ) {
            // Background track
            CircularProgressIndicator(
                progress = { 1.0f },
                modifier = Modifier.fillMaxSize(),
                color = Color.White.copy(alpha = 0.12f),
                strokeWidth = 3.dp
            )
            // Active countdown fill ring
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                color = Color.White.copy(alpha = 0.88f),
                strokeWidth = 3.dp
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
                        blurRadius = 14f,
                        offset = Offset.Zero
                    )
                ),
                modifier = Modifier.graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

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

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Intro (${remainingSec}s)",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.50f)
        )
    }
}
