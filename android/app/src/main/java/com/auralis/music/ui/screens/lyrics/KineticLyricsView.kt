package com.auralis.music.ui.screens.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun KineticLyricsView(
    lyrics: LyricsData,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    offsetMs: Long = 0L,
    onOffsetChange: ((Long) -> Unit)? = null
) {
    val effectiveLines = remember(lyrics) {
        if (lyrics.lines.isNotEmpty()) lyrics.lines
        else if (!lyrics.plainLyrics.isNullOrBlank()) {
            lyrics.plainLyrics.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { LyricLine(time = 0L, text = it) }
        } else emptyList()
    }

    val isInstrumental = (effectiveLines.isNotEmpty() && effectiveLines.all { it.isInstrumental }) ||
        lyrics.plainLyrics?.trim()?.equals("[Instrumental]", ignoreCase = true) == true ||
        lyrics.plainLyrics?.trim()?.equals("♪ Instrumental ♪", ignoreCase = true) == true

    if (isInstrumental) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "♪ Instrumental ♪",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        return
    }

    if (effectiveLines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Lyrics not available",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        return
    }

    val isSynced = lyrics.syncType != SyncType.PLAIN && effectiveLines.any { it.time > 0L }
    val activeIndex = remember(effectiveLines, currentPositionMs, offsetMs, isSynced) {
        if (isSynced) LyricsEngine.findActiveLyricIndex(effectiveLines, currentPositionMs, offsetMs) else -1
    }

    val scrollState = rememberScrollState()

    // Store LayoutCoordinates references for window-space measurement
    var viewportCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val lyricCoordRefs = remember { mutableMapOf<Int, androidx.compose.ui.layout.LayoutCoordinates>() }

    // Persistent float animatable tracking scroll position and velocity across transitions
    val scrollAnim = remember { Animatable(scrollState.value.toFloat()) }

    LaunchedEffect(lyrics) {
        lyricCoordRefs.clear()
        scrollAnim.snapTo(0f)
    }

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

    Column(modifier = modifier.fillMaxSize()) {
        // Offset Stepper Controls (±500ms)
        if (onOffsetChange != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sync Offset: ${offsetMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { onOffsetChange(offsetMs - 500) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "-500ms",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onOffsetChange(offsetMs + 500) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "+500ms",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
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
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { viewportCoords = it }
        ) {
            // Continuous, velocity-preserving auto-scroll to keep active line centered
            LaunchedEffect(activeIndex, isUserInteracting, appearance.autoScrollLyrics) {
                if (activeIndex < 0 || activeIndex >= effectiveLines.size || isUserInteracting || !appearance.autoScrollLyrics) return@LaunchedEffect

                var lCoords = lyricCoordRefs[activeIndex]
                if (lCoords == null || !lCoords.isAttached) {
                    withFrameNanos { }
                    lCoords = lyricCoordRefs[activeIndex]
                }

                val vCoords = viewportCoords
                if (vCoords == null || lCoords == null || !vCoords.isAttached || !lCoords.isAttached) return@LaunchedEffect

                val viewportBounds = vCoords.boundsInWindow()
                val lyricBounds = lCoords.boundsInWindow()

                val viewportCenter = (viewportBounds.top + viewportBounds.bottom) / 2f
                val lyricCenter = (lyricBounds.top + lyricBounds.bottom) / 2f
                val delta = lyricCenter - viewportCenter

                val targetScroll = (scrollState.value + delta.roundToInt())
                    .coerceIn(0, scrollState.maxValue)

                val distance = kotlin.math.abs(targetScroll - scrollState.value)
                if (distance > 2) {
                    val animationSpec = when {
                        distance < 300 -> spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 85f)
                        distance < 800 -> spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 130f)
                        else -> spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 240f)
                    }
                    scrollState.animateScrollTo(targetScroll, animationSpec = animationSpec)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        top = 16.dp,
                        bottom = maxHeight / 2,
                        start = 24.dp,
                        end = 24.dp
                    ),
                horizontalAlignment = horizontalAlignment,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                effectiveLines.forEachIndexed { index, line ->
                    val isActive = index == activeIndex
                    val isPast = index < activeIndex

                    val targetAlpha = when {
                        isActive -> 1.0f
                        isPast -> 0.45f
                        else -> 0.35f
                    }
                    val alpha by animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = tween(300),
                        label = "lyricAlpha"
                    )

                    val textColor by animateColorAsState(
                        targetValue = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(300),
                        label = "lyricTextColor"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                lyricCoordRefs[index] = coords
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(alpha)
                                .clickable(
                                    enabled = appearance.changeLyricsOnTap,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (appearance.changeLyricsOnTap) {
                                            isUserInteracting = false
                                            onSeekTo(line.time)
                                        }
                                    }
                                ),
                            horizontalAlignment = horizontalAlignment
                        ) {
                            if (lyrics.syncType == SyncType.RICHSYNC && line.words != null && isActive) {
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = horizontalArrangement
                                ) {
                                    line.words.forEach { word ->
                                        val progress = LyricsEngine.calculateWordProgress(word, currentPositionMs, offsetMs)
                                        val isWordActive = progress > 0f && progress < 1f
                                        val isWordFinished = progress >= 1f
                                        val displayWord = if (word.word.endsWith(" ")) word.word else "${word.word} "

                                        val brush = when {
                                            isWordFinished -> Brush.linearGradient(listOf(Color.White, Color.White))
                                            isWordActive -> {
                                                val p = progress.coerceIn(0.01f, 0.99f)
                                                val waveStart = (p - 0.08f).coerceAtLeast(0f)
                                                val waveEnd = (p + 0.10f).coerceAtMost(1f)
                                                Brush.horizontalGradient(
                                                    0.0f to Color.White,
                                                    waveStart to Color.White,
                                                    p to Color.White.copy(alpha = 0.92f),
                                                    waveEnd to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                                    1.0f to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                                )
                                            }
                                            else -> Brush.linearGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                                )
                                            )
                                        }

                                        val waveLift = if (isWordActive) {
                                            (kotlin.math.sin(progress * Math.PI.toFloat()) * 3.0f)
                                        } else 0f

                                        val waveShadowRadius = if (isWordActive) {
                                            14f + (10f * kotlin.math.sin(progress * Math.PI.toFloat()))
                                        } else 0f

                                        Text(
                                            text = displayWord,
                                            fontSize = 24.sp,
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
                                            lineHeight = 32.sp
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = line.text,
                                    fontSize = if (isActive) 24.sp else 20.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = textColor,
                                    textAlign = textAlign,
                                    modifier = Modifier.fillMaxWidth(),
                                    lineHeight = if (isActive) 32.sp else 28.sp
                                )
                            }

                            if (!line.translatedText.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = line.translatedText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    textAlign = textAlign,
                                    modifier = Modifier.fillMaxWidth(),
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
