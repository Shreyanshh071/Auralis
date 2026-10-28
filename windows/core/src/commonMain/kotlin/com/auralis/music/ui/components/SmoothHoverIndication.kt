package com.auralis.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

/** Draw-only state layers: entry, exit, focus and press all ease from the current value. */
object SmoothHoverIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = SmoothHoverNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()
}

private class SmoothHoverNode(private val source: InteractionSource) : Modifier.Node(),
    DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val opacity = Animatable(0f)
    private val active = mutableSetOf<Interaction>()
    private var animation: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter, is FocusInteraction.Focus, is PressInteraction.Press -> active.add(interaction)
                    is HoverInteraction.Exit -> active.remove(interaction.enter)
                    is FocusInteraction.Unfocus -> active.remove(interaction.focus)
                    is PressInteraction.Release -> active.remove(interaction.press)
                    is PressInteraction.Cancel -> active.remove(interaction.press)
                }
                val pressed = active.any { it is PressInteraction.Press }
                val target = if (pressed) .14f else if (active.isNotEmpty()) .08f else 0f
                animation?.cancel()
                animation = coroutineScope.launch {
                    opacity.animateTo(target, tween(if (pressed) 90 else 180, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    override fun onDetach() { active.clear() }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (opacity.value > 0f) drawRect(currentValueOf(LocalContentColor).copy(alpha = opacity.value))
    }
}
