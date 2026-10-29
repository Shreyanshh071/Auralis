package com.auralis.music.ui.theme

import androidx.compose.runtime.*
import androidx.compose.animation.core.*

/** Live previews suspend decorative clocks while offscreen or being paged. */
val LocalPreviewMotionActive = compositionLocalOf<Boolean?> { null }

@Composable
internal fun rememberPreviewRotation(durationMillis: Int, target: Float = 360f): State<Float> {
    val modalActive = com.auralis.music.ui.player.LocalPlayerModalVisualState.current?.animationsActive
    val active = LocalPreviewMotionActive.current?.let { it && modalActive != false } ?: modalActive
    if (active == null) {
        return rememberInfiniteTransition(label = "playerBackgroundRotation").animateFloat(
            initialValue = 0f, targetValue = target,
            animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Restart),
            label = "rotation")
    }
    val reduced = LocalReducedMotion.current
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active, reduced, durationMillis, target) {
        if (!active || reduced) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            val delta = (now - previous) / 1_000_000f
            angle.floatValue = (angle.floatValue + delta * target / durationMillis) % target
            previous = now
        }
    }
    return angle
}
