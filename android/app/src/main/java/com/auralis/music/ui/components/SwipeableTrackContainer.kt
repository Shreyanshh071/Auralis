package com.auralis.music.ui.components

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wraps a track row with swipe actions: right plays it next, left adds it to the queue or
 * removes it (queue, or a playlist with swipe-to-remove on).
 *
 * The row itself never gets a fill: it slides over whatever is behind the list (the player's
 * colours, a page), so nothing turns into a dark slab when it is held or swiped. The space it
 * uncovers holds one soft capsule exactly as wide as the gap, with the action's icon in a disc
 * that fills with the theme accent once letting go would act (a tick marks that moment), and
 * the action's name sliding in beside it.
 *
 * Swipe state is only allocated when a swipe action is available, so plain lists pay nothing.
 */
@Composable
fun SwipeableTrackContainer(
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    isPlaylistContext: Boolean = false,
    /** A list whose rows can be removed by swiping, like a playlist (e.g. the play queue). */
    removeOnSwipeLeft: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Two separate modes, never mixed (Settings turns one off when the other is turned on):
    //  - "Swipe left to add to the queue, or right to play next": only those two actions.
    //  - "Swipe to remove": swiping left removes, where a row can be removed; nothing else.
    // The queue used to remove on swipe left under the first setting, so play next and remove
    // ended up on the same row with "Swipe to remove" switched off.
    val appearance = LocalAppearanceSettings.current
    val isSwipeQueueNextEnabled = appearance.swipeLeftQueueRightPlayNext
    val isSwipeRemoveEnabled = onRemoveFromPlaylist != null &&
        appearance.swipeToRemoveSongFromPlaylist && (isPlaylistContext || removeOnSwipeLeft)

    val canSwipeRight = !isSwipeRemoveEnabled && isSwipeQueueNextEnabled && onPlayNext != null
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

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val thresholdPx = remember(density) { with(density) { 76.dp.toPx() } }
    val maxDragPx = remember(density) { with(density) { 150.dp.toPx() } }

    // Which side is open and whether letting go now would act. Derived, so a row at rest
    // recomposes only when these flip, not on every pixel of the drag.
    val side by remember { derivedStateOf { offsetX.value.let { if (it > 1f) 1 else if (it < -1f) -1 else 0 } } }
    val armed by remember(thresholdPx) { derivedStateOf { abs(offsetX.value) >= thresholdPx } }
    LaunchedEffect(armed) {
        if (armed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .draggable(
                state = rememberDraggableState { delta ->
                    val current = offsetX.value
                    val minAllowed = if (canSwipeLeft) -maxDragPx else 0f
                    val maxAllowed = if (canSwipeRight) maxDragPx else 0f
                    // Past the trigger point the row drags heavier, so the end of the swipe is felt.
                    val resist = if (abs(current) > thresholdPx) 0.45f else 1f
                    val target = (current + delta * resist).coerceIn(minAllowed, maxAllowed)
                    coroutineScope.launch { offsetX.snapTo(target) }
                },
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    val current = offsetX.value
                    if (current <= -thresholdPx && canSwipeLeft) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isSwipeRemoveEnabled) onRemoveFromPlaylist?.invoke() else onAddToQueue?.invoke()
                    } else if (current >= thresholdPx && canSwipeRight) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayNext?.invoke()
                    }
                    coroutineScope.launch {
                        // Settles without a bounce: a wobbling row read as a glitch in the list.
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    }
                }
            )
    ) {
        if (side != 0) {
            val toRight = side > 0
            val isRemove = !toRight && isSwipeRemoveEnabled
            SwipeActionReveal(
                icon = when {
                    toRight -> Icons.Default.SkipNext
                    isRemove -> Icons.Default.DeleteOutline
                    else -> Icons.AutoMirrored.Filled.PlaylistAdd
                },
                label = when {
                    toRight -> str(R.string.play_next_2)
                    isRemove -> str(R.string.remove)
                    else -> str(R.string.add_to_queue_2)
                },
                accent = if (isRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                onAccent = if (isRemove) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                fromStart = toRight,
                armed = armed,
                width = { abs(offsetX.value) },
                modifier = Modifier.matchParentSize()
            )
        }

        // The row: moved in the layout phase only, and never given a background.
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
        ) {
            content()
        }
    }
}

/** The capsule in the gap a swiped row leaves, as wide as the gap and anchored to its edge. */
@Composable
private fun SwipeActionReveal(
    icon: ImageVector,
    label: String,
    accent: Color,
    onAccent: Color,
    fromStart: Boolean,
    armed: Boolean,
    width: () -> Float,
    modifier: Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val discColor by animateColorAsState(
        targetValue = if (armed) accent else onSurface.copy(alpha = 0.12f),
        animationSpec = tween(140),
        label = "swipeDisc"
    )
    val iconColor by animateColorAsState(
        targetValue = if (armed) onAccent else onSurface.copy(alpha = 0.85f),
        animationSpec = tween(140),
        label = "swipeIcon"
    )
    val discScale by animateFloatAsState(
        targetValue = if (armed) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "swipeDiscScale"
    )
    Box(modifier, contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                // Exactly the uncovered width, measured each frame without recomposing.
                .layout { measurable, constraints ->
                    val w = width().roundToInt().coerceIn(0, constraints.maxWidth)
                    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                    layout(w, placeable.height) { placeable.place(0, 0) }
                }
                .padding(vertical = 4.dp, horizontal = 4.dp)
                .clip(RoundedCornerShape(50))
                .background(onSurface.copy(alpha = 0.07f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End
        ) {
            if (!fromStart) {
                SwipeActionLabel(label, armed, accent)
                Spacer(Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = discScale
                        scaleY = discScale
                        // Fades in as the gap opens, so a tiny wobble while holding shows nothing.
                        alpha = (width() / 40.dp.toPx()).coerceIn(0f, 1f)
                    }
                    .clip(CircleShape)
                    .background(discColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(19.dp))
            }
            if (fromStart) {
                Spacer(Modifier.width(8.dp))
                SwipeActionLabel(label, armed, accent)
            }
        }
    }
}

@Composable
private fun SwipeActionLabel(label: String, armed: Boolean, accent: Color) {
    AnimatedVisibility(
        visible = armed,
        enter = fadeIn(tween(120)) + expandHorizontally(tween(160)),
        exit = fadeOut(tween(100)) + shrinkHorizontally(tween(140))
    ) {
        Text(
            text = label,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}
