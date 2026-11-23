package com.auralis.music.ui.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Browser-style auto-scroll: click the middle button to drop an anchor, move the mouse above or
 * below it to scroll (further = faster), click anything to stop. Holding the middle button and
 * dragging works too; releasing it then stops.
 */
@Stable
internal class MiddleClickAutoScroll {
    var anchor by mutableStateOf<Offset?>(null)
    var pointer by mutableStateOf(Offset.Zero)
}

@Composable
internal fun rememberMiddleClickAutoScroll(list: ScrollableState): MiddleClickAutoScroll {
    val auto = remember(list) { MiddleClickAutoScroll() }
    val active = auto.anchor != null
    LaunchedEffect(auto, active) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val anchor = auto.anchor ?: break
            val dy = auto.pointer.y - anchor.y
            if (abs(dy) > DEAD_ZONE_PX) {
                val pxPerSecond = (abs(dy) - DEAD_ZONE_PX) * SPEED * sign(dy)
                list.dispatchRawDelta(pxPerSecond * (now - last) / 1_000_000_000f)
            }
            last = now
        }
    }
    return auto
}

internal fun Modifier.middleClickAutoScroll(auto: MiddleClickAutoScroll): Modifier = pointerInput(auto) {
    awaitPointerEventScope {
        var middleDown = false
        var swallowRelease = false
        while (true) {
            // Initial pass: seen before the rows, so the stopping click doesn't also play a song.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            val middleNow = event.buttons.isTertiaryPressed
            val anchor = auto.anchor
            if (anchor != null) auto.pointer = change.position
            when (event.type) {
                PointerEventType.Press -> when {
                    anchor != null -> {
                        auto.anchor = null
                        swallowRelease = true
                        event.changes.forEach { it.consume() }
                    }
                    middleNow && !middleDown -> {
                        auto.anchor = change.position
                        auto.pointer = change.position
                        event.changes.forEach { it.consume() }
                    }
                }
                PointerEventType.Release -> {
                    if (swallowRelease) {
                        swallowRelease = false
                        event.changes.forEach { it.consume() }
                    } else if (middleDown && !middleNow && anchor != null) {
                        // Held and dragged: letting go ends it. A plain click keeps it running.
                        if ((change.position - anchor).getDistance() > HOLD_DRAG_PX) auto.anchor = null
                        event.changes.forEach { it.consume() }
                    }
                }
                PointerEventType.Scroll -> if (anchor != null) auto.anchor = null
            }
            middleDown = middleNow
        }
    }
}

/**
 * The anchor marker, drawn where the middle click landed. Place in the list's parent Box.
 * A glass disc (no glow); the arrow for the scrolling direction lights up in the accent, and
 * chevrons stream out that way, more of them the faster it scrolls. Pops in, fades out.
 */
@Composable
internal fun MiddleClickAutoScrollMarker(auto: MiddleClickAutoScroll) {
    val anchor = auto.anchor
    var lastAnchor by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(anchor) { if (anchor != null) lastAnchor = anchor }
    val visible = anchor != null
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 120 else 180))
    val scale by animateFloatAsState(if (visible) 1f else .6f,
        if (visible) spring(dampingRatio = .55f, stiffness = 600f) else tween(180))
    val at = anchor ?: lastAnchor ?: return
    if (!visible && alpha <= .01f) return

    val dy = if (visible) auto.pointer.y - at.y else 0f
    val direction = when {
        dy > DEAD_ZONE_PX -> 1f
        dy < -DEAD_ZONE_PX -> -1f
        else -> 0f
    }
    val speed by animateFloatAsState(((abs(dy) - DEAD_ZONE_PX) / FULL_SPEED_PX).coerceIn(0f, 1f), spring(stiffness = 700f))
    val up by animateFloatAsState(if (direction < 0f) 1f else 0f, tween(140))
    val down by animateFloatAsState(if (direction > 0f) 1f else 0f, tween(140))
    // Chevrons drift outward; faster scrolling makes them flow faster.
    val flow by rememberInfiniteTransition().animateFloat(0f, 1f,
        infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart))
    val accent = MaterialTheme.colorScheme.primary

    val width = 48.dp
    val height = 150.dp
    Canvas(
        Modifier
            .offset { IntOffset(at.x.roundToInt() - width.roundToPx() / 2, at.y.roundToInt() - height.roundToPx() / 2) }
            .size(width, height)
            .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
    ) {
        val c = center
        val r = 17.dp.toPx()
        val moving = up + down
        // Glass disc: dark tinted fill with a light top edge.
        drawCircle(Brush.verticalGradient(listOf(Color(0xE62A2A33), Color(0xE6141418)), c.y - r, c.y + r), r, c)
        drawCircle(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = .45f), lerp(Color.White.copy(alpha = .12f), accent, .5f * moving)), c.y - r, c.y + r),
            r, c, style = Stroke(1.2.dp.toPx())
        )
        drawCircle(Color.White.copy(alpha = .9f), 2.4.dp.toPx(), c)

        // Inner arrows: the active one lit in the accent, the other faded.
        val idle = 1f - moving
        val arrowW = 4.5.dp.toPx()
        val arrowH = 3.dp.toPx()
        val arrowGap = r * .5f
        chevron(Offset(c.x, c.y - arrowGap), arrowW, arrowH, pointsUp = true,
            color = lerp(Color.White, accent, up).copy(alpha = .85f * idle + up + .25f * down))
        chevron(Offset(c.x, c.y + arrowGap), arrowW, arrowH, pointsUp = false,
            color = lerp(Color.White, accent, down).copy(alpha = .85f * idle + down + .25f * up))

        // Speed trail outside the disc.
        if (direction != 0f) {
            val step = 10.dp.toPx()
            val start = r + 7.dp.toPx()
            val drift = flow * step * (.4f + .6f * speed)
            for (i in 0 until 3) {
                val shown = (speed * 3f - i + .35f).coerceIn(0f, 1f)
                if (shown <= 0f) continue
                val distance = start + i * step + drift
                val fade = 1f - (distance - start) / (step * 3.2f)
                chevron(Offset(c.x, c.y + direction * distance), 6.dp.toPx(), 4.dp.toPx(), pointsUp = direction < 0f,
                    color = accent.copy(alpha = (shown * fade.coerceIn(0f, 1f)).coerceIn(0f, 1f)), stroke = 2.2.dp.toPx())
            }
        }
    }
}

private fun DrawScope.chevron(at: Offset, halfWidth: Float, halfHeight: Float, pointsUp: Boolean, color: Color, stroke: Float = 1.8f * density) {
    val tip = if (pointsUp) -halfHeight else halfHeight
    val path = Path().apply {
        moveTo(at.x - halfWidth, at.y - tip)
        lineTo(at.x, at.y + tip)
        lineTo(at.x + halfWidth, at.y - tip)
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private const val DEAD_ZONE_PX = 12f
private const val SPEED = 6f
private const val FULL_SPEED_PX = 220f
private const val HOLD_DRAG_PX = 24f
