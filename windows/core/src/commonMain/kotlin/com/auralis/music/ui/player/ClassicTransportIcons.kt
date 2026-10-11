package com.auralis.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke

/*
 * Bold, rounded transport glyphs for the classic player. Material's FastRewind / Pause are thin at
 * any size; these are solid shapes whose corners are softened by stroking the same path with a
 * round join, so they read heavy and friendly like a native music app.
 */

/** Fills [path] and strokes it with a round join, which rounds every corner by about [round]/2. */
private fun DrawScope.roundedFill(path: Path, color: Color, round: Float) {
    drawPath(path, color, style = Fill)
    drawPath(path, color, style = Stroke(width = round, join = StrokeJoin.Round))
}

/** A right-pointing triangle filling the box from (left, top) to (right, bottom). */
private fun triangle(left: Float, top: Float, right: Float, bottom: Float, pointsRight: Boolean) = Path().apply {
    if (pointsRight) {
        moveTo(left, top)
        lineTo(right, (top + bottom) / 2f)
        lineTo(left, bottom)
    } else {
        moveTo(right, top)
        lineTo(left, (top + bottom) / 2f)
        lineTo(right, bottom)
    }
    close()
}

/** Two overlapping triangles pointing forward ([forward] = true) or back. */
@Composable
fun ClassicSkipGlyph(forward: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val round = size.height * 0.16f
        val inset = round / 2f
        val half = size.width / 2f
        val top = inset
        val bottom = size.height - inset
        // Each triangle spans just over half the width so the two touch at the middle.
        roundedFill(triangle(inset, top, half + inset * 0.2f, bottom, forward), color, round)
        roundedFill(triangle(half - inset * 0.2f, top, size.width - inset, bottom, forward), color, round)
    }
}

@Composable
fun ClassicPlayGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val round = size.height * 0.14f
        val inset = round / 2f
        // Nudged right so the triangle looks centred (its visual mass sits left of its box).
        val shift = size.width * 0.06f
        roundedFill(
            triangle(size.width * 0.14f + shift, inset, size.width * 0.92f + shift - inset, size.height - inset, true),
            color,
            round
        )
    }
}

@Composable
fun ClassicPauseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val barWidth = size.width * 0.38f
        val gap = size.width * 0.2f
        val start = (size.width - barWidth * 2 - gap) / 2f
        val radius = CornerRadius(barWidth * 0.28f)
        drawRoundRect(color, Offset(start, 0f), Size(barWidth, size.height), radius)
        drawRoundRect(color, Offset(start + barWidth + gap, 0f), Size(barWidth, size.height), radius)
    }
}
