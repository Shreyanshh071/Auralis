package com.auralis.music.windows

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** Anchored dropdown with the same sampled backdrop as account and song menus. */
@Composable
internal fun GlassDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val position = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
                val below = anchorBounds.bottom + gap
                val y = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - popupContentSize.height - gap
                return IntOffset(x.coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)),
                    y.coerceIn(8, (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8)))
            }
        }
    }
    Popup(position, onDismissRequest, properties = PopupProperties(focusable = true)) {
        LiquidPopupSurface(Modifier.widthIn(min = 180.dp, max = 300.dp)) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}
