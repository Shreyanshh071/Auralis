package com.auralis.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import com.auralis.music.ui.theme.LocalReducedMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class FloatingIconMotion(
    val interactionSource: MutableInteractionSource,
    val surfaceModifier: Modifier,
    val contentModifier: Modifier,
    val onClick: (Float, () -> Unit) -> Unit
)

/** Close the glass while navigation starts, then spring back on return. */
@Composable
internal fun rememberFloatingIconMotion(
    enabled: Boolean = true
): FloatingIconMotion {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val surface = remember { Animatable(1f) }
    val content = remember { Animatable(1f) }
    var running by remember { mutableStateOf(false) }
    var pivot by remember { mutableStateOf(0.5f) }
    val pressScale = animateFloatAsState(
        targetValue = if (pressed && enabled && !reducedMotion) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 900f),
        label = "floatingIconPress"
    )
    return FloatingIconMotion(
        interactionSource = source,
        surfaceModifier = if (!enabled || reducedMotion) Modifier else Modifier.graphicsLayer {
            val scale = surface.value * pressScale.value
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(pivot, 0.5f)
            alpha = (surface.value * 4f).coerceIn(0f, 1f)
        },
        contentModifier = if (!enabled || reducedMotion) Modifier else Modifier.graphicsLayer {
            alpha = content.value
            scaleX = 0.85f + 0.15f * content.value
            scaleY = scaleX
        },
        onClick = { anchor, action ->
            if (!running) {
                if (!enabled || reducedMotion) {
                    action()
                } else {
                    running = true
                    pivot = anchor.coerceIn(0f, 1f)
                    scope.launch {
                        try {
                            coroutineScope {
                                launch { content.animateTo(0f, tween(120)) }
                                launch { surface.animateTo(0f, tween(340, easing = FastOutSlowInEasing)) }
                                // The page enters while the circle finishes closing.
                                delay(110)
                                action()
                            }
                            // Keep the closed circle hidden until the overlapping page fade completes.
                            delay(80)
                            coroutineScope {
                                launch { content.animateTo(1f, tween(120)) }
                                surface.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 500f))
                            }
                        } finally {
                            running = false
                        }
                    }
                }
            }
        }
    )
}
