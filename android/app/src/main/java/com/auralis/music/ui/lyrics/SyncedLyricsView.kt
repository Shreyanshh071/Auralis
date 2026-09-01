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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsMode
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.dynamicPalette
import com.auralis.music.ui.theme.motionTween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * High-performance, smooth 60fps Line-Synced Lyrics View:
 * - Immediate centering and fluid auto-scrolling to active lyric line
 * - Bold, vibrant active line highlighting with soft glowing typography
 * - Tap-to-seek and human drag gesture detection with auto-resume
 * - Multi-line selection and beautiful lyric card sharing
 * - Instrumental intro countdown and rhythm orbs
 * - AI lyric translation display support
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

    val isBitterSweetSymphony = remember(lyrics, track) {
        val title = (lyrics?.trackName ?: track?.title ?: "").lowercase()
        title.contains("bitter sweet symphony") || title.contains("bittersweet symphony")
    }

    val effectiveLines = remember(lyrics, isBitterSweetSymphony) {
        if (lyrics == null) emptyList()
        else {
            val base = if (lyrics.lines.isNotEmpty()) lyrics.lines
            else if (!lyrics.plainLyrics.isNullOrBlank()) {
                lyrics.plainLyrics.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { LyricLine(time = 0L, text = it) }
            } else emptyList()

            val firstTime = base.firstOrNull()?.time ?: 0L
            val isFirstLineMatch = base.firstOrNull()?.text?.contains("bitter sweet symphony", ignoreCase = true) == true

            if ((isBitterSweetSymphony || isFirstLineMatch) && firstTime > 33000L) {
                // Audio starts singing at 32.0s on YouTube. Shift all lines by exactly (firstTime - 32000L).
                val delta = firstTime - 32000L
                base.map { line ->
                    line.copy(
                        time = (line.time - delta).coerceAtLeast(0L),
                        words = line.words?.map { w -> w.copy(time = (w.time - delta).coerceAtLeast(0L)) }
                    )
                }
            } else {
                base
            }
        }
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

    val rawFirstLineTime = effectiveLines.firstOrNull()?.time ?: 0L
    val isBitterSweet = isBitterSweetSymphony || effectiveLines.firstOrNull()?.text?.contains("bitter sweet symphony", ignoreCase = true) == true
    val introDurationMs = remember(effectiveLines, isBitterSweet) {
        if (isBitterSweet) {
            0L // Special directive: remove the circle only for Bitter Sweet Symphony
        } else if (rawFirstLineTime >= 1500L) {
            rawFirstLineTime
        } else {
            0L
        }
    }

    val isSynced = (lyrics.syncType != SyncType.PLAIN || effectiveLines.any { it.time > 0L }) && effectiveLines.isNotEmpty()
    val activeIndex = remember(currentPositionMs, offsetMs, effectiveLines, isSynced, introDurationMs) {
        if (!isSynced) -1
        else if (introDurationMs >= 1500L && (currentPositionMs + offsetMs) < introDurationMs) -1
        else LyricsEngine.findActiveLyricIndex(effectiveLines, currentPositionMs, offsetMs)
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
            if (!isSynced || isUserInteracting || !appearance.autoScrollLyrics) return@LaunchedEffect
            if (activeIndex < 0) {
                listState.animateScrollToItem(0, 0)
                return@LaunchedEffect
            }
            if (activeIndex >= effectiveLines.size) return@LaunchedEffect

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
            val effectiveTime = (currentPositionMs + offsetMs).coerceAtLeast(0L)
            val isIntroActive = isSynced && introDurationMs >= 1500L && effectiveTime < introDurationMs

            if (isIntroActive) {
                item(key = "instrumental_intro_countdown") {
                    InstrumentalIntroIndicator(
                        currentTimeMs = effectiveTime,
                        introDurationMs = introDurationMs,
                        onSkipIntro = { onSeekTo(introDurationMs) }
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
                        isCurrent = isCurrent,
                        isPast = isPast,
                        lyricsMode = lyricsMode,
                        syncType = lyrics.syncType,
                        textAlign = textAlign,
                        horizontalAlignment = horizontalAlignment
                    )
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
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isCurrent: Boolean,
    isPast: Boolean,
    lyricsMode: LyricsMode,
    syncType: SyncType,
    textAlign: TextAlign,
    horizontalAlignment: Alignment.Horizontal
) {
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
        Text(
            text = line.text,
            fontSize = fontSize,
            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = textColor,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
            lineHeight = (fontSize.value * 1.34f).sp,
            overflow = TextOverflow.Visible
        )

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
    currentTimeMs: Long,
    introDurationMs: Long,
    modifier: Modifier = Modifier,
    onSkipIntro: (() -> Unit)? = null
) {
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

