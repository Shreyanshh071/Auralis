package com.auralis.music.ui.glass

import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.Backdrop

/** Align a modal window's glass with the page recorded in the activity window. */
internal fun Modifier.liquidGlassDialog(
    glass: LiquidGlassContext, shape: CornerBasedShape, pageView: View,
    showEdgeHighlight: Boolean = true
): Modifier = composed {
    val dialogView = LocalView.current
    var windowOffset by remember { mutableStateOf(Offset.Zero) }
    val alignedBackdrop = remember(glass.backdrop, windowOffset) {
        object : Backdrop {
            override val isCoordinatesDependent = true
            override fun DrawScope.drawBackdrop(
                density: Density, coordinates: LayoutCoordinates?,
                layerBlock: (GraphicsLayerScope.() -> Unit)?
            ) {
                withTransform({ translate(-windowOffset.x, -windowOffset.y) }) {
                    with(glass.backdrop) { drawBackdrop(density, coordinates, layerBlock) }
                }
            }
        }
    }
    val alignedGlass = remember(glass, alignedBackdrop) {
        LiquidGlassContext(alignedBackdrop, glass.isDark, glass.collapse)
    }
    this.onGloballyPositioned {
        fun origin(view: View): Offset {
            val screen = IntArray(2)
            val window = IntArray(2)
            view.getLocationOnScreen(screen)
            view.getLocationInWindow(window)
            return Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
        }
        windowOffset = origin(dialogView) - origin(pageView)
    }.liquidGlass(alignedGlass, shape,
        // Keep the page's colour and refraction, while suppressing cover art
        // detail under labels. This profile is exclusive to glass modal windows.
        tint = if (glass.isDark) Color(0xFF121212).copy(alpha = .64f)
            else Color(0xFFFAFAFA).copy(alpha = .76f),
        blurRadius = 20.dp, lensDepth = 36.dp,
        edgeHighlight = if (showEdgeHighlight) com.kyant.backdrop.highlight.Highlight.Default else null)
}
