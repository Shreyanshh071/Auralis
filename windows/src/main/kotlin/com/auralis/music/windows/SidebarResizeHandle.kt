package com.auralis.music.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.awt.Cursor

@Composable
internal fun SidebarResizeHandle(onDrag: (Float) -> Unit, onDragging: (Boolean) -> Unit) {
    val density = LocalDensity.current.density
    // The gap between panels is the handle.
    Box(Modifier.width(8.dp).fillMaxHeight()
        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
        .draggable(rememberDraggableState { onDrag(it / density) }, Orientation.Horizontal,
            onDragStarted = { onDragging(true) }, onDragStopped = { onDragging(false) }))
}

internal fun sidebarWidth(requested: Float, minimum: Float, maximum: Float): Float =
    requested.coerceIn(minimum, maximum.coerceAtLeast(minimum))

internal data class SidebarLayout(val left: Float = 232f, val right: Float = 340f,
    val leftCollapsed: Boolean = false, val rightCollapsed: Boolean = false)

internal object SidebarLayoutPreferences {
    private val store by lazy { com.auralis.music.util.PropertiesFileKeyValueStore(
        java.io.File(com.auralis.music.util.desktopAppDataDir(), "desktop-sidebars.properties")) }
    fun load() = SidebarLayout(
        sidebarWidth(store.getString("left", "232")?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 232f, 180f, 320f),
        sidebarWidth(store.getString("right", "340")?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 340f, 280f, 460f),
        store.getString("leftCollapsed", "false") == "true", store.getString("rightCollapsed", "false") == "true")
    fun save(layout: SidebarLayout) = store.putStrings(mapOf("left" to layout.left.toString(), "right" to layout.right.toString(),
        "leftCollapsed" to layout.leftCollapsed.toString(), "rightCollapsed" to layout.rightCollapsed.toString()))
}
