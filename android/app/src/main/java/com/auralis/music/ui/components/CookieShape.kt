package com.auralis.music.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A circle with a soft wavy ("cookie") edge, like Material 3 Expressive's cookie shapes.
 * [lobes] bumps around the edge; [depth] is how deep each wave dips, as a fraction of the radius.
 */
class CookieShape(private val lobes: Int = 12, private val depth: Float = 0.08f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val steps = lobes * 16
        val path = Path()
        for (i in 0..steps) {
            val angle = 2.0 * PI * i / steps
            // Cosine waves: rounded bumps and rounded dips, no sharp points.
            val r = radius * (1f - depth + depth * cos(lobes * angle).toFloat())
            val x = center.x + r * cos(angle).toFloat()
            val y = center.y + r * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}
