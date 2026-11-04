package com.auralis.music.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.ui.unit.IntOffset

/** Presentation timing only; reorder thresholds and track commitment stay with their owners. */
internal object PlayerTransitionMotion {
    /** A sliding panel must leave the window completely; only a morph ends at the mini player. */
    fun sheetTravelDistance(fullHeight: Float, collapsedHeight: Float, classicSlide: Boolean): Float =
        (if (classicSlide) fullHeight else fullHeight - collapsedHeight).coerceAtLeast(0f)

    /** Reveal the mini player as the sliding panel clears its bounds, rather than over its header. */
    fun classicMiniPlayerAlpha(progress: Float, fullHeight: Float, collapsedHeight: Float, miniHeight: Float): Float {
        if (miniHeight <= 0f) return 0f
        val sheetTop = (1f - progress.coerceIn(0f, 1f)) * fullHeight
        val miniTop = fullHeight - collapsedHeight
        return ((sheetTop - miniTop) / miniHeight).coerceIn(0f, 1f)
    }

    /** Keep touch velocity on release and a precise endpoint at either refresh rate. */
    suspend fun settleSheet(
        progress: Animatable<Float, AnimationVector1D>,
        target: Float,
        reducedMotion: Boolean,
        initialVelocity: Float = progress.velocity,
        onSettled: (Boolean) -> Unit
    ) {
        progress.animateTo(
            targetValue = target,
            animationSpec = if (reducedMotion) snap() else spring(
                dampingRatio = 1f,
                stiffness = 350f,
                visibilityThreshold = 0.0005f
            ),
            initialVelocity = initialVelocity
        )
        // Cancellation must never commit an obsolete destination during a reversal.
        onSettled(target == 1f)
    }

    // Rows sliding up as songs move into History: long enough to read as a glide (160ms read as
    // a snap), easing out softly, no overshoot.
    const val QueuePlacementMillis = 380
    val queuePlacement = tween<IntOffset>(QueuePlacementMillis, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))

    // Song-change background blend. Reference crossfades run ~400-600ms; 240ms with a
    // full-speed start (LinearOutSlowIn) read as a colour snap rather than a flow.
    const val paletteDurationMillis = 500

    fun paletteSpec(progress: Float, durationMillis: Int = paletteDurationMillis) = tween<Float>(
        // Rapid skips interrupting a blend still catch up faster.
        durationMillis = if (progress < 0.85f) {
            (durationMillis * 0.65f).toInt().coerceAtLeast(120)
        } else {
            durationMillis
        },
        easing = FastOutSlowInEasing
    )
}
