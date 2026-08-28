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
import androidx.compose.ui.graphics.Color
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
    if (lyrics.lines.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "♪ Instrumental ♪",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        return
    }

    val activeIndex = remember(lyrics.lines, currentPositionMs, offsetMs) {
        LyricsEngine.findActiveLyricIndex(lyrics.lines, currentPositionMs, offsetMs)
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

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { viewportCoords = it }
        ) {
            // Continuous, velocity-preserving auto-scroll:
            // When activeIndex changes, seamlessly glides from current position & velocity
            // to the exact viewport center without stopping, stuttering, or restarting from scratch.
            LaunchedEffect(activeIndex, isUserInteracting) {
                if (activeIndex < 0 || activeIndex >= lyrics.lines.size || isUserInteracting) return@LaunchedEffect

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

                val currentScroll = scrollState.value.toFloat()
                val distance = kotlin.math.abs(targetScroll.toFloat() - currentScroll)

                if (distance > 1f) {
                    // Ensure animatable baseline matches current scroll if out of sync
                    if (kotlin.math.abs(scrollAnim.value - currentScroll) > 2f) {
                        scrollAnim.snapTo(currentScroll)
                    }

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
                        top = 16.dp,
                        bottom = maxHeight / 2,
                        start = 24.dp,
                        end = 24.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                lyrics.lines.forEachIndexed { index, line ->
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
                                .clickable {
                                    isUserInteracting = false
                                    onSeekTo(line.time)
                                }
                        ) {
                            if (lyrics.syncType == SyncType.RICHSYNC && line.words != null && isActive) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    line.words.forEach { word ->
                                        val progress = LyricsEngine.calculateWordProgress(word, currentPositionMs, offsetMs)
                                        val wordColor = if (progress >= 1.0f) {
                                            MaterialTheme.colorScheme.primary
                                        } else if (progress > 0.0f) {
                                            Color.White
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }

                                        Text(
                                            text = word.word,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = wordColor,
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
