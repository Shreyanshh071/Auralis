package com.auralis.music.windows

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import java.awt.event.MouseWheelEvent

/**
 * Wheel scrolling over the left sidebar that the sidebar itself doesn't use (its shortcut list is
 * already at an end, or too short to scroll) scrolls the page instead. The wheel event is re-sent at
 * the pointer's height just inside the page's left edge, so every page scrolls without wiring each
 * one's scroll state here. [pageBounds] is the page panel in window pixels.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.forwardUnusedScrollTo(pageBounds: () -> Rect, density: () -> Float): Modifier =
    onPointerEvent(PointerEventType.Scroll, PointerEventPass.Final) { event ->
        if (event.changes.any { it.isConsumed }) return@onPointerEvent
        val wheel = event.nativeEvent as? MouseWheelEvent ?: return@onPointerEvent
        val page = pageBounds()
        if (page.isEmpty) return@onPointerEvent
        // Window pixels to AWT's logical units: the display scale, not Compose density (which can
        // also carry the app's own UI zoom).
        val scale = (wheel.component.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()
            ?: density()).takeIf { it > 0f } ?: 1f
        // Page padding sits at its left edge: hover effects of cards there aren't triggered.
        val x = ((page.left / scale) + 6).toInt()
        val y = (wheel.y).coerceIn((page.top / scale).toInt() + 1, (page.bottom / scale).toInt() - 1)
        val forwarded = MouseWheelEvent(wheel.component, wheel.id, wheel.`when`, wheel.modifiersEx, x, y,
            wheel.xOnScreen, wheel.yOnScreen, wheel.clickCount, wheel.isPopupTrigger, wheel.scrollType,
            wheel.scrollAmount, wheel.wheelRotation, wheel.preciseWheelRotation)
        event.changes.forEach { it.consume() }
        java.awt.EventQueue.invokeLater { wheel.component.dispatchEvent(forwarded) }
    }
