package com.auralis.music.ui.player

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

/** Keep content mounted and follow the dock's single animation in layout/draw, not composition. */
internal fun Modifier.miniPlayerDockReveal(
    collapse: State<Float>?,
    horizontal: Boolean
): Modifier = graphicsLayer {
    alpha = 1f - (collapse?.value ?: 0f).coerceIn(0f, 1f)
    clip = true
}.layout { measurable, constraints ->
    val reveal = 1f - (collapse?.value ?: 0f).coerceIn(0f, 1f)
    // Buttons retain their natural size while their allotted space collapses. This avoids
    // squeezing/reflowing them as the host pill changes width on the same frame.
    val childConstraints = if (horizontal) {
        constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
    } else {
        constraints.copy(minHeight = 0)
    }
    val child = measurable.measure(childConstraints)
    val width = constraints.constrainWidth(if (horizontal) (child.width * reveal).roundToInt() else child.width)
    val height = constraints.constrainHeight(if (horizontal) child.height else (child.height * reveal).roundToInt())
    layout(width, height) { child.placeRelative(0, 0) }
}
