package com.auralis.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.auralis.music.ui.theme.dynamicBackground
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Wraps a track item row with swipe-to-queue, swipe-to-play-next, and swipe-to-remove actions.
 * Optimized for 120fps buttery smooth list scrolling — swipe state is only allocated
 * when swipe gestures are enabled, not per-item unconditionally.
 */
@Composable
fun SwipeableTrackContainer(
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    isPlaylistContext: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val appearance = LocalAppearanceSettings.current
    val isSwipeQueueNextEnabled = appearance.swipeLeftQueueRightPlayNext
    val isSwipeRemoveEnabled = appearance.swipeToRemoveSongFromPlaylist && isPlaylistContext && onRemoveFromPlaylist != null

    val canSwipeRight = isSwipeQueueNextEnabled && onPlayNext != null
    val canSwipeLeft = isSwipeRemoveEnabled || (isSwipeQueueNextEnabled && onAddToQueue != null)

    // Fast path: no swipe features enabled or capable — render content directly with zero overhead
    if (!canSwipeRight && !canSwipeLeft) {
        if (modifier == Modifier) {
            content()
        } else {
            Box(modifier = modifier) { content() }
        }
        return
    }

    // Swipe state — only allocated when at least one swipe feature is enabled
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val thresholdPx = remember(density) { with(density) { 72.dp.toPx() } }
    val maxDragPx = remember(density) { with(density) { 140.dp.toPx() } }

    val isSwipingRight = canSwipeRight && offsetX.value > 8f
    val isSwipingLeft = canSwipeLeft && offsetX.value < -8f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .draggable(
                state = rememberDraggableState { delta ->
                    val current = offsetX.value
                    val minAllowed = if (canSwipeLeft) -maxDragPx else 0f
                    val maxAllowed = if (canSwipeRight) maxDragPx else 0f
                    val target = (current + delta).coerceIn(minAllowed, maxAllowed)
                    coroutineScope.launch {
                        offsetX.snapTo(target)
                    }
                },
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    val current = offsetX.value
                    if (current < -thresholdPx && canSwipeLeft) {
                        // Swiped Left
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isSwipeRemoveEnabled) {
                            onRemoveFromPlaylist?.invoke()
                        } else {
                            onAddToQueue?.invoke()
                        }
                    } else if (current > thresholdPx && canSwipeRight) {
                        // Swiped Right
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayNext?.invoke()
                    }
                    coroutineScope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    }
                }
            )
    ) {
        // Background action indicators (only composed when swiping — zero overhead during idle scroll)
        if (isSwipingRight) {
            // Swiping Right -> Play Next (Left background revealed)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xFF2E2415)),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.padding(start = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Next",
                        tint = Color(0xFFFFB84D),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Play Next",
                        color = Color(0xFFFFB84D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        } else if (isSwipingLeft) {
            // Swiping Left -> Add to Queue OR Remove from Playlist
            val isDelete = isSwipeRemoveEnabled
            val bgColor = if (isDelete) Color(0xFF331414) else Color(0xFF162B1E)
            val accentColor = if (isDelete) Color(0xFFFF5252) else Color(0xFF4CAF50)
            val icon = if (isDelete) Icons.Default.Delete else Icons.AutoMirrored.Filled.PlaylistAdd
            val label = if (isDelete) "Remove" else "Add to Queue"

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(bgColor),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    modifier = Modifier.padding(end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Foreground track row
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .then(
                    if (offsetX.value != 0f) {
                        Modifier.background(MaterialTheme.dynamicBackground)
                    } else Modifier
                )
        ) {
            content()
        }
    }
}
