package com.auralis.music.windows.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo

/**
 * A song's, album's or playlist's options (the phone's sheet: same layout and options) as a menu
 * where it was asked for: at the right-click, or at the â‹® that was clicked. Translucent dark
 * glass, not the item's cover. Kept inside the window; it opens up or left when there's no room.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun OptionsMenuPopup(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val invocation = LocalMenuInvocationAnchor.current
    val point = remember { invocation?.point ?: pointerInWindow() }
    val provider = remember(point) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                songMenuPosition(point, windowSize, popupContentSize)
        }
    }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        OptionsMenuSurface((windowHeight - 24.dp).coerceAtLeast(240.dp), content)
    }
}

/** The menu's glass: translucent dark with a faint top sheen and a hairline edge. */
@Composable
internal fun OptionsMenuSurface(maxHeight: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density * .88f, density.fontScale)) {
        com.auralis.music.ui.components.CoverSheetTheme {
            com.auralis.music.windows.LiquidPopupSurface(
                modifier = Modifier.width(380.dp).heightIn(max = maxHeight)) {
                Box(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                    CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
                }
            }
        }
    }
}

/** The pointer in window pixels (where the right-click or the â‹® click happened). */
private fun pointerInWindow(): IntOffset = runCatching {
    val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        ?: java.awt.Window.getWindows().firstOrNull { it.isVisible && it.isFocused }
        ?: error("No focused window")
    val pointer = MouseInfo.getPointerInfo().location
    val origin = window.locationOnScreen
    val scale = window.graphicsConfiguration.defaultTransform
    IntOffset(((pointer.x - origin.x) * scale.scaleX).toInt(), ((pointer.y - origin.y) * scale.scaleY).toInt())
}.getOrDefault(IntOffset(32, 64))
