package com.auralis.music.windows.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A pill slider that thickens smoothly while hovered or dragged (Spicy Lyrics' seek and volume
 * bars). [vertical] fills from the bottom. Values are 0..1.
 */
@Composable
internal fun HoverSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    enabled: Boolean = true,
    vertical: Boolean = false,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = .22f),
    thin: Dp = 4.dp,
    thick: Dp = 10.dp,
    hitSize: Dp = 22.dp,
    onDraggingChanged: (Boolean) -> Unit = {},
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val size by animateDpAsState(if (enabled && (hovered || dragging)) thick else thin, tween(180), label = "sliderThickness")
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val draggingChanged by rememberUpdatedState(onDraggingChanged)
    Box(
        modifier
            .then(if (vertical) Modifier.width(hitSize).fillMaxHeight() else Modifier.height(hitSize).fillMaxWidth())
            .hoverable(hover, enabled)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .pointerInput(enabled, vertical) {
                if (!enabled) return@pointerInput
                fun fraction(position: Offset) = if (vertical) 1f - position.y / this.size.height else position.x / this.size.width
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    draggingChanged(true)
                    try {
                        change(fraction(down.position).coerceIn(0f, 1f))
                        drag(down.id) {
                            it.consume()
                            change(fraction(it.position).coerceIn(0f, 1f))
                        }
                        finished()
                    } finally {
                        dragging = false
                        draggingChanged(false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(if (vertical) Modifier.width(size).fillMaxHeight() else Modifier.height(size).fillMaxWidth()) {
            val radius = CornerRadius(if (vertical) this.size.width / 2 else this.size.height / 2)
            val v = value.coerceIn(0f, 1f)
            if (vertical) {
                val rail = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, this@Canvas.size.width, this@Canvas.size.height, radius))
                }
                drawPath(rail, inactiveColor)
                val fillHeight = this.size.height * v
                if (fillHeight > 0f) clipPath(rail) {
                    drawRect(activeColor, topLeft = Offset(0f, this.size.height - fillHeight),
                        size = Size(this.size.width, fillHeight))
                }
                drawPath(rail, Color.White.copy(alpha = .24f), style = Stroke(1.dp.toPx()))
            } else {
                drawRoundRect(inactiveColor, cornerRadius = radius)
                drawRoundRect(activeColor, size = Size(this.size.width * v, this.size.height), cornerRadius = radius)
            }
        }
    }
}
