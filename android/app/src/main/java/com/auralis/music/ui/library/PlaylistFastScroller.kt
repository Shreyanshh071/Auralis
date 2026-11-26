package com.auralis.music.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.glass.glassBarTint
import com.auralis.music.ui.glass.glassContentColor
import com.auralis.music.ui.glass.glassSurfaceTint
import com.auralis.music.ui.glass.liquidGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where the handle should sit, 0..1, for a list at a given song position. */
internal fun fastScrollFraction(songPosition: Float, songCount: Int, visibleSongs: Int): Float {
    val scrollable = (songCount - visibleSongs).coerceAtLeast(1)
    return (songPosition / scrollable).coerceIn(0f, 1f)
}

/** The song a handle at [fraction] lands on: the first song that would show at the top. */
internal fun fastScrollTarget(fraction: Float, songCount: Int, visibleSongs: Int): Int {
    val scrollable = (songCount - visibleSongs).coerceAtLeast(0)
    return (fraction.coerceIn(0f, 1f) * scrollable).roundToInt().coerceIn(0, (songCount - 1).coerceAtLeast(0))
}

/**
 * The song beside the handle: the row whose on-screen span holds [centerY], or in a gap the
 * nearest row. [rows] are (song index, top, bottom) in the same coordinates as [centerY].
 */
internal fun fastScrollRowAt(centerY: Float, rows: List<Triple<Int, Float, Float>>): Int? =
    rows.firstOrNull { (_, top, bottom) -> centerY >= top && centerY < bottom }?.first
        ?: rows.minByOrNull { (_, top, bottom) -> minOf(kotlin.math.abs(centerY - top), kotlin.math.abs(centerY - bottom)) }?.first

private val HandleHeight = 58.dp
private val TouchWidth = 30.dp

/**
 * A fast scroller for long playlists, on the right edge. It slides in while the list moves; the
 * rounded handle can be held and dragged, which widens it into the accent colour and shows a
 * bubble with the song you'd land on (cover, title, artist, position). A light tick marks each
 * step, and it fades a moment after you let go.
 *
 * [firstSongIndex] is how many list items come before the songs, so positions map to songs.
 */
@Composable
internal fun PlaylistFastScroller(
    state: LazyListState,
    songCount: Int,
    firstSongIndex: Int,
    songAt: (Int) -> Track?,
    /** Where the list's top edge is on screen, so rows can be lined up with the handle. */
    listTopInRoot: () -> Float,
    /** The page's liquid glass (its list is the backdrop); null for the solid look. */
    glass: com.auralis.music.ui.glass.LiquidGlassContext? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(0) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) recentlyScrolled = true
        else { delay(1400); recentlyScrolled = false }
    }
    // Only once the songs reach the top: not beside the cover and header.
    val songsAtTop by remember(state, firstSongIndex) { derivedStateOf { state.firstVisibleItemIndex >= firstSongIndex } }
    val shown = dragging || (recentlyScrolled && songsAtTop && (state.canScrollForward || state.canScrollBackward))
    val reveal by animateFloatAsState(if (shown) 1f else 0f,
        if (shown) spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow) else tween(380), label = "scrollerReveal")
    val handleWidth by animateDpAsState(if (dragging) 30.dp else 22.dp,
        spring(dampingRatio = .62f, stiffness = Spring.StiffnessMedium), label = "scrollerWidth")
    val press by animateFloatAsState(if (dragging) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "scrollerPress")

    val visibleSongs by remember(state) {
        derivedStateOf { state.layoutInfo.visibleItemsInfo.count { it.index >= firstSongIndex && it.index < firstSongIndex + songCount }.coerceAtLeast(1) }
    }
    val listFraction by remember(state, songCount, firstSongIndex) {
        derivedStateOf {
            if (!state.canScrollForward) return@derivedStateOf 1f
            val first = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index >= firstSongIndex } ?: return@derivedStateOf 0f
            val rowSize = first.size.coerceAtLeast(1)
            val above = (state.layoutInfo.viewportStartOffset - first.offset).coerceAtLeast(0)
            fastScrollFraction(first.index - firstSongIndex + above / rowSize.toFloat(), songCount, visibleSongs)
        }
    }
    val fraction = if (dragging) dragFraction else listFraction
    // The drag reads these live: captured once, they froze at the values from when the header
    // was on screen (a 99-song range, and a handle stuck at the top on the next grab).
    val currentFraction by rememberUpdatedState(fraction)
    val currentVisible by rememberUpdatedState(visibleSongs)
    var scrollerTopInRoot by remember { mutableFloatStateOf(0f) }
    // Where the named row's middle is, in the scroller's own coordinates: the bubble sits on it.
    var rowCenter by remember { mutableFloatStateOf(Float.NaN) }

    BoxWithConstraints(modifier.fillMaxHeight().widthIn(min = TouchWidth)
        .onGloballyPositioned { scrollerTopInRoot = it.positionInRoot().y }) {
        val handlePx = with(density) { HandleHeight.toPx() }
        val trackPx = (with(density) { maxHeight.toPx() } - handlePx).coerceAtLeast(1f)
        val handleTop = fraction * trackPx
        // While held, the bubble names exactly the row level with the handle's middle.
        if (dragging) {
            val info = state.layoutInfo
            val base = listTopInRoot() - info.viewportStartOffset
            val rows = info.visibleItemsInfo.filter { it.index >= firstSongIndex && it.index < firstSongIndex + songCount }
                .map { Triple(it.index - firstSongIndex, base + it.offset, base + it.offset + it.size) }
            fastScrollRowAt(scrollerTopInRoot + handleTop + handlePx / 2f, rows)?.let { row ->
                rows.firstOrNull { it.first == row }?.let { (_, top, bottom) -> rowCenter = (top + bottom) / 2f - scrollerTopInRoot }
                LaunchedEffect(row) {
                    val step = (songCount / 30).coerceAtLeast(1)
                    if (row / step != target / step) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    target = row
                }
            }
        }

        // A faint rail while held, so the whole length reads as the playlist.
        Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().width(TouchWidth).drawBehind {
            if (press <= .01f) return@drawBehind
            val w = 3.dp.toPx()
            drawRoundRect(colors.onBackground.copy(alpha = .12f * press),
                topLeft = Offset(size.width - 4.dp.toPx() - handleWidth.toPx() / 2f - w / 2f, handlePx / 2f),
                size = Size(w, trackPx), cornerRadius = CornerRadius(w / 2f))
        })

        if (reveal > .01f) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(((1f - reveal) * 36.dp.toPx()).roundToInt(), handleTop.roundToInt()) }
                    .padding(end = 4.dp)
                    .then(if (glass != null) {
                        // A lens over the songs; held, it takes on the accent colour.
                        Modifier.graphicsLayer { alpha = reveal }.liquidGlass(glass, CircleShape,
                            tint = if (dragging) accent.copy(alpha = if (glass.isDark) .55f else .42f) else glassBarTint(glass.isDark))
                    } else Modifier
                        .graphicsLayer { alpha = reveal; scaleX = 1f + .06f * press; scaleY = 1f + .06f * press }
                        .shadow(if (dragging) 10.dp else 4.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(if (dragging) colors.primary else colors.surfaceContainerHighest.copy(alpha = .96f))
                        .border(1.dp, if (dragging) Color.Transparent else colors.outlineVariant.copy(alpha = .35f), CircleShape))
                    .width(handleWidth)
                    .height(HandleHeight)
                    .pointerInput(songCount, firstSongIndex, trackPx) {
                        var start = 0f
                        var moved = 0f
                        var visible = 1
                        var top = 0
                        detectVerticalDragGestures(
                            onDragStart = {
                                // Start from where the handle is now, with the page's real song count on screen.
                                visible = currentVisible
                                start = currentFraction * trackPx; moved = 0f
                                dragFraction = currentFraction; dragging = true; rowCenter = Float.NaN
                                top = fastScrollTarget(dragFraction, songCount, visible)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                moved += amount
                                dragFraction = ((start + moved) / trackPx).coerceIn(0f, 1f)
                                val nextTop = fastScrollTarget(dragFraction, songCount, visible)
                                if (nextTop != top) {
                                    top = nextTop
                                    scope.launch { state.scrollToItem(firstSongIndex + nextTop) }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // Three soft grip lines; they turn to the accent's ink while held.
                val gripColor = when { glass != null -> glassContentColor(glass.isDark); dragging -> colors.onPrimary; else -> colors.onSurface }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    repeat(3) {
                        Box(Modifier.width(if (dragging) 12.dp else 9.dp).height(2.dp).clip(CircleShape)
                            .background(gripColor.copy(alpha = if (dragging) .9f else .6f)))
                    }
                }
            }
        }

        // The song beside the handle, laid over its own row.
        val bubbleHalf = with(density) { 32.dp.toPx() }
        val bubbleCenter by animateFloatAsState(if (rowCenter.isNaN()) handleTop + handlePx / 2f else rowCenter,
            spring(dampingRatio = .9f, stiffness = Spring.StiffnessHigh), label = "bubbleRow")
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(tween(120)) + scaleIn(initialScale = .85f, transformOrigin = TransformOrigin(1f, .5f)),
            exit = fadeOut(tween(180)) + scaleOut(targetScale = .9f, transformOrigin = TransformOrigin(1f, .5f)),
            modifier = Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(-(handleWidth + 14.dp).roundToPx(), (bubbleCenter - bubbleHalf).roundToInt()) }
        ) {
            FastScrollBubble(songAt(target), target, songCount, glass)
        }
    }
}

@Composable
private fun FastScrollBubble(track: Track?, index: Int, songCount: Int, glass: com.auralis.music.ui.glass.LiquidGlassContext? = null) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val ink = if (glass != null) glassContentColor(glass.isDark) else colors.onSurface
    Row(
        Modifier.widthIn(max = 260.dp).height(64.dp)
            .then(if (glass != null) Modifier.liquidGlass(glass, shape, tint = glassSurfaceTint(glass.isDark), blurRadius = 14.dp)
                else Modifier.shadow(12.dp, shape).clip(shape).background(colors.surfaceContainerHighest)
                    .border(1.dp, colors.outlineVariant.copy(alpha = .3f), shape))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkCard(url = track?.thumbnail, modifier = Modifier.size(44.dp), cornerRadius = 10.dp,
            contentDescription = null, fallbackTrack = track)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(track?.title.orEmpty(), color = ink, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track?.artist.orEmpty(), color = ink.copy(alpha = .7f), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text("${index + 1} / $songCount", color = colors.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            modifier = Modifier.clip(CircleShape).background(colors.primaryContainer).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
