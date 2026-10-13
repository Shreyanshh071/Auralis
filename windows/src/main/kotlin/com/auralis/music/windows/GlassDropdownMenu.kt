package com.auralis.music.windows

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** Anchored dropdown with the same live page blur as the app's other glass popups. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun GlassDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val margin = with(density) { 8.dp.roundToPx() }
    val provider = remember(margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                glassDropdownPosition(anchorBounds, windowSize, popupContentSize, margin, layoutDirection)
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)) {
        LiquidPopupSurface(Modifier.width(with(density) { (window.width - margin * 2).coerceAtLeast(1).toDp() }.coerceAtMost(280.dp))
            .heightIn(max = with(density) { (window.height - margin * 2).coerceAtLeast(1).toDp() })) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = .72f))) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
                }
            }
        }
    }
}

internal fun glassDropdownPosition(anchor: IntRect, window: IntSize, popup: IntSize, margin: Int,
    direction: LayoutDirection = LayoutDirection.Ltr): IntOffset {
    val x = if (direction == LayoutDirection.Ltr) anchor.right - popup.width else anchor.left
    val y = if (anchor.bottom + popup.height + margin <= window.height) anchor.bottom else anchor.top - popup.height
    return IntOffset(x.coerceIn(margin, (window.width - popup.width - margin).coerceAtLeast(margin)),
        y.coerceIn(margin, (window.height - popup.height - margin).coerceAtLeast(margin)))
}
