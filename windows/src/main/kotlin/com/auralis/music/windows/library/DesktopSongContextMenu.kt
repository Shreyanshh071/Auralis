package com.auralis.music.windows.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.auralis.music.ui.components.GlassMenuItem
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo

/** A focusable, scrollable desktop menu at the initiating click, constrained to the window. */
@Composable
internal fun DesktopSongContextMenu(items: List<GlassMenuItem>, onDismiss: () -> Unit, background: @Composable () -> Unit) {
    val invocation = LocalMenuInvocationAnchor.current
    val point = remember {
        invocation?.point ?: runCatching {
            val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                ?: java.awt.Window.getWindows().firstOrNull { it.isVisible && it.isFocused }
                ?: error("No focused window")
            val pointer = MouseInfo.getPointerInfo().location
            val origin = window.locationOnScreen
            val scale = window.graphicsConfiguration.defaultTransform
            IntOffset(((pointer.x - origin.x) * scale.scaleX).toInt(), ((pointer.y - origin.y) * scale.scaleY).toInt())
        }.getOrDefault(IntOffset(32, 64))
    }
    val provider = remember(point) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                songMenuPosition(point, windowSize, popupContentSize)
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        DesktopSongMenuContent(items, onDismiss, background)
    }
}

@Composable
internal fun DesktopSongMenuContent(items: List<GlassMenuItem>, onDismiss: () -> Unit, background: @Composable () -> Unit = {}) {
    com.auralis.music.ui.components.CoverSheetTheme {
        Surface(shape = RoundedCornerShape(18.dp), color = com.auralis.music.ui.components.CoverSheetFloor,
            shadowElevation = 12.dp, border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))) {
          Box {
            Box(Modifier.matchParentSize()) { background() }
            Column(Modifier.width(280.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item ->
                    DropdownMenuItem(text = { Text(item.label, style = MaterialTheme.typography.bodyMedium) },
                        onClick = { item.onClick(); if (item.dismisses) onDismiss() }, enabled = item.enabled,
                        leadingIcon = { item.icon?.let { Icon(it, null, Modifier.size(18.dp), tint = item.tint ?: MaterialTheme.colorScheme.onSurfaceVariant) } },
                        modifier = Modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .08f)))
                }
            }
        }
      }
    }
}

/** Frosted glass over the app's current backdrop: the playing cover when Liquidify is on. */
@Composable
internal fun SongMenuFrost(artwork: String?) {
    Box(Modifier.fillMaxSize()) {
        if (artwork != null) com.auralis.music.windows.player.BlurredArtworkBackground(artwork, blurRadius = 40.dp)
        Box(Modifier.fillMaxSize().background(Color(0xFF171519).copy(alpha = if (artwork != null) .55f else 1f)))
    }
}

internal fun songMenuPosition(point: IntOffset, window: IntSize, menu: IntSize): IntOffset {
    val x = if (point.x + menu.width <= window.width) point.x else point.x - menu.width
    val y = if (point.y + menu.height <= window.height) point.y else point.y - menu.height
    return IntOffset(x.coerceIn(0, (window.width - menu.width).coerceAtLeast(0)),
        y.coerceIn(0, (window.height - menu.height).coerceAtLeast(0)))
}
