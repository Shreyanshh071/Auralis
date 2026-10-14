package com.auralis.music.windows

import androidx.compose.foundation.background
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** Separate popup scene keeps the modal and scrim out of the captured page display list. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun GlassDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val width = with(density) { size.width.toDp() }
    val height = with(density) { size.height.toDp() }
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .42f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
            LiquidPopupSurface(Modifier.widthIn(max = (width - 48.dp).coerceAtLeast(1.dp)).width(488.dp)
                .heightIn(max = (height - 48.dp).coerceAtLeast(1.dp))) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
    }
}
