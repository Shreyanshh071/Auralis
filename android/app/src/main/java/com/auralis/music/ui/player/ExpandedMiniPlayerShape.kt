package com.auralis.music.ui.player

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Broad, smooth squircle corners for both the expanded card and its compact form. */
internal object ExpandedMiniPlayerShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = minOf(with(density) { 24.dp.toPx() }, size.width / 2f, size.height / 2f)
        val shoulder = radius * 0.15f
        val width = size.width
        val height = size.height
        return Outline.Generic(Path().apply {
            moveTo(radius, 0f)
            lineTo(width - radius, 0f)
            cubicTo(width - shoulder, 0f, width, shoulder, width, radius)
            lineTo(width, height - radius)
            cubicTo(width, height - shoulder, width - shoulder, height, width - radius, height)
            lineTo(radius, height)
            cubicTo(shoulder, height, 0f, height - shoulder, 0f, height - radius)
            lineTo(0f, radius)
            cubicTo(0f, shoulder, shoulder, 0f, radius, 0f)
            close()
        })
    }
}
