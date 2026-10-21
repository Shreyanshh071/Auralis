package com.auralis.music.windows

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * The left panel with its bottom-left corner cut out for the Up Next card, like a tiling window
 * manager: the panel fills all the room the card leaves, with every corner (the inner one too)
 * rounded by [radius]. [notchWidth] and [notchHeight] are the card's size plus the gap around it;
 * a card as wide as the panel just makes the panel shorter.
 */
internal class SidebarNotchShape(private val radius: Dp, private val notchWidth: Dp, private val notchHeight: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val w = size.width
        val h = size.height
        val nw = with(density) { notchWidth.toPx() }.coerceIn(0f, w)
        val nh = with(density) { notchHeight.toPx() }.coerceIn(0f, (h - 2 * r).coerceAtLeast(0f))
        if (nw <= 0.5f || nh <= 0.5f) return Outline.Rounded(RoundRect(0f, 0f, w, h, CornerRadius(r)))
        // The card spans (nearly) the whole width: no lobe beside it, just a shorter panel.
        if (w - nw < 2 * r + 1f) return Outline.Rounded(RoundRect(0f, 0f, w, h - nh, CornerRadius(r)))
        val lobe = minOf(r, (w - nw) / 2f, nh / 2f) // corner of the part beside the card
        val inner = minOf(r, nw / 2f, nh - lobe)     // the concave corner above the card's right edge
        val top = h - nh                             // where the card's gap begins
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w - r, 0f)
            arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(nw + lobe, h)
            arcTo(Rect(nw, h - 2 * lobe, nw + 2 * lobe, h), 90f, 90f, false)
            lineTo(nw, top + inner)
            arcTo(Rect(nw - 2 * inner, top, nw, top + 2 * inner), 0f, -90f, false)
            lineTo(r, top)
            arcTo(Rect(0f, top - 2 * r, 2 * r, top), 90f, 90f, false)
            lineTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}
