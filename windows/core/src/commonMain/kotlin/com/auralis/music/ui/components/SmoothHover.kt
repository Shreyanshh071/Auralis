package com.auralis.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.node.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.filterNot

/** Shared hover timing for custom glass rows, links and desktop controls. */
@Composable
fun smoothHoverColor(target: Color): Color = animateColorAsState(target, tween(180), label = "hoverHighlight").value

/** Draw-time state keeps hover animation local to the hovered component. */
data class SmoothHoverIndication(
    private val shape: Shape = RectangleShape,
    private val tint: Color = Color.Unspecified,
    private val enabled: Boolean = true,
    private val pressFeedback: Boolean = true
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HoverNode(interactionSource, shape, tint, enabled, pressFeedback)
}

private class HoverNode(private val source: InteractionSource, private val shape: Shape,
    private val tint: Color, private val enabled: Boolean, private val pressFeedback: Boolean
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val alpha = Animatable(0f)
    override fun onAttach() {
        coroutineScope.launch {
            alpha.snapTo(0f)
            val hovered = mutableSetOf<HoverInteraction.Enter>()
            val pressed = mutableSetOf<PressInteraction.Press>()
            val focused = mutableSetOf<FocusInteraction.Focus>()
            var animation: Job? = null
            source.interactions.collect { event ->
                when (event) {
                    is HoverInteraction.Enter -> hovered += event
                    is HoverInteraction.Exit -> hovered -= event.enter
                    is PressInteraction.Press -> pressed += event
                    is PressInteraction.Release -> pressed -= event.press
                    is PressInteraction.Cancel -> pressed -= event.press
                    is FocusInteraction.Focus -> focused += event
                    is FocusInteraction.Unfocus -> focused -= event.focus
                }
                val target = when {
                    !enabled -> 0f
                    pressed.isNotEmpty() && pressFeedback -> .14f
                    hovered.isNotEmpty() -> .08f
                    focused.isNotEmpty() && pressFeedback -> .10f
                    else -> 0f
                }
                animation?.cancel()
                animation = launch { alpha.animateTo(target, tween(if (pressed.isNotEmpty() && pressFeedback) 60 else 180)) }
            }
        }
    }
    override fun ContentDrawScope.draw() {
        drawContent()
        val color = if (tint == Color.Unspecified) currentValueOf(LocalContentColor) else tint
        if (alpha.value > 0f) drawOutline(shape.createOutline(size, layoutDirection, this),
            color.copy(alpha = color.alpha * alpha.value))
    }
}

val SmoothAppIndication = SmoothHoverIndication()

/** The hover layer follows the thumb without recreating its animation while dragging. */
@Composable
internal fun rememberThumbHoverShape(fraction: Float, insetDp: Float = 0f): Shape {
    val position = rememberUpdatedState(fraction)
    return remember(insetDp) {
        object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                val inset = with(density) { insetDp.dp.toPx() }
                val radius = with(density) { 20.dp.toPx() }
                val progress = if (layoutDirection == LayoutDirection.Ltr) position.value else 1f - position.value
                val center = Offset(inset + (size.width - 2f * inset) * progress, size.height / 2f)
                return Outline.Generic(Path().apply { addOval(Rect(center, radius)) })
            }
        }
    }
}

/** Material controls retain their native press/focus feedback; hover uses the shared fade. */
private class HoverFilteredSource(private val source: MutableInteractionSource) : MutableInteractionSource {
    override val interactions = source.interactions.filterNot { it is HoverInteraction }
    override suspend fun emit(interaction: Interaction) = source.emit(interaction)
    override fun tryEmit(interaction: Interaction) = source.tryEmit(interaction)
}

@Composable
internal fun SmoothControl(modifier: Modifier, source: MutableInteractionSource?, shape: Shape,
    tint: Color, enabled: Boolean, content: @Composable (Modifier, MutableInteractionSource) -> Unit) {
    val actual = source ?: remember { MutableInteractionSource() }
    val filtered = remember(actual) { HoverFilteredSource(actual) }
    val indication = remember(shape, tint, enabled) { SmoothHoverIndication(shape, tint, enabled, pressFeedback = false) }
    content(modifier.indication(actual, indication), filtered)
}
