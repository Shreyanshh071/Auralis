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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

    if (!isSwipeQueueNextEnabled && !isSwipeRemoveEnabled) {
        Box(modifier = modifier) { content() }
        return
    }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxDragPx = with(density) { 140.dp.toPx() }

    val draggableState = rememberDraggableState { delta ->
        coroutineScope.launch {
            val current = offsetX.value
            val target = (current + delta).coerceIn(-maxDragPx, maxDragPx)
            offsetX.snapTo(target)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    val current = offsetX.value
                    if (current < -thresholdPx) {
                        // Swiped Left
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isSwipeRemoveEnabled) {
                            onRemoveFromPlaylist?.invoke()
                        } else if (isSwipeQueueNextEnabled) {
                            onAddToQueue?.invoke()
                        }
                    } else if (current > thresholdPx) {
                        // Swiped Right
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isSwipeQueueNextEnabled) {
                            onPlayNext?.invoke()
                        }
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
        val currentOffset = offsetX.value

        // Background action indicators behind the sliding track row
        if (currentOffset > 8f) {
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
        } else if (currentOffset < -8f) {
            // Swiping Left -> Add to Queue OR Remove from Playlist
            val isDelete = isSwipeRemoveEnabled
            val bgColor = if (isDelete) Color(0xFF331414) else Color(0xFF162B1E)
            val accentColor = if (isDelete) Color(0xFFFF5252) else Color(0xFF4CAF50)
            val icon = if (isDelete) Icons.Default.Delete else Icons.Default.PlaylistAdd
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
                .offset { IntOffset(currentOffset.roundToInt(), 0) }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            content()
        }
    }
}
