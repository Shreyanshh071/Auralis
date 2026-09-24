package com.auralis.music.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/** Presentation timing only; reorder thresholds and track commitment stay with their owners. */
internal object PlayerTransitionMotion {
    // Starts moving on the first frame, with a short settle and no spring overshoot.
    val queuePlacement = tween<IntOffset>(160, easing = LinearOutSlowInEasing)

    // Song-change background blend. VIVI's crossfade runs ~400-600ms; 240ms with a
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
