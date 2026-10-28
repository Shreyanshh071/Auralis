package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class MiniPopupResizeTest {
    private val start = IntRect(200, 140, 540, 480)
    private val limits = IntRect(0, 0, 900, 700)

    @Test fun everyHandleStopsAtTheReferenceMaximumEvenOnLargeMonitors() {
        val largeWindow = IntRect(0,0,4000,2400)
        val centered = IntRect(1800,1100,2140,1440)
        for (edge in MiniPopupEdge.entries) {
            val resized = resizeMiniPopup(centered, edge, Offset(if (edge.left) -9000f else 9000f,
                if (edge.top) -9000f else 9000f), largeWindow, 220)
            assertEquals(if (edge.left || edge.right) 1280 else 340, resized.width, edge.name)
            assertEquals(if (edge.top || edge.bottom) 720 else 340, resized.height, edge.name)
        }
        assertEquals(IntSize(1280,720), fitMiniPopup(largeWindow,largeWindow,220).size)
    }

    @Test fun actualPopupKeepsTheSameBottomRightCornerWhileItsTopLeftHandleMoves() {
        var contentPosition = Offset.Zero
        var contentSize = IntSize.Zero
        val scene = ImageComposeScene(900,700, coroutineContext = Dispatchers.Unconfined) {
            androidx.compose.material3.MaterialTheme {
                CompositionLocalProvider(com.auralis.music.ui.theme.LocalAppearanceSettings provides
                    com.auralis.music.domain.model.AppearanceSettings(playerBackgroundStyle = "Follow theme")) {
                    Box(Modifier.fillMaxSize()) {
                        LyricsMiniPopup(com.auralis.music.domain.model.Track("fixture", "Song", "Artist"),
                            com.auralis.music.ui.theme.DefaultArtworkPalette, {}, {}, null) {
                            Box(Modifier.fillMaxSize().onGloballyPositioned {
                                contentPosition = it.positionInWindow(); contentSize = it.size
                            }.background(Color.DarkGray))
                        }
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render() { scene.render(frame++ * 16_666_667L).close() }
            repeat(5) { render() }
            assertEquals(340, contentSize.width)
            val initial = contentSize
            val right = contentPosition.x + contentSize.width
            val bottom = contentPosition.y + contentSize.height
            val down = Offset(contentPosition.x + 3f, contentPosition.y - 12f + 3f)
            scene.sendPointerEvent(PointerEventType.Press, down, type = PointerType.Mouse)
            for (i in 1..12) {
                scene.sendPointerEvent(PointerEventType.Move, down - Offset(i * 5f, i * 4f), type = PointerType.Mouse)
                render()
            }
            scene.sendPointerEvent(PointerEventType.Release, down - Offset(60f,48f), type = PointerType.Mouse)
            repeat(3) { render() }
            assertTrue(contentSize.width > initial.width + 40, "Popup must expand horizontally")
            assertTrue(contentSize.height > initial.height + 30, "Popup must expand vertically")
            assertEquals(right, contentPosition.x + contentSize.width, 1f)
            assertEquals(bottom, contentPosition.y + contentSize.height, 1f)
        } finally { scene.close() }
    }

    @Test fun everyEdgeAndCornerResizesIndependentlyAndKeepsItsOppositeAnchor() {
        for (edge in MiniPopupEdge.entries) {
            val delta = Offset(if (edge.left) -40f else 40f, if (edge.top) -30f else 30f)
            val resized = resizeMiniPopup(start, edge, delta, limits, 220)
            assertEquals(if (edge.left) 160 else 200, resized.left, edge.name)
            assertEquals(if (edge.top) 110 else 140, resized.top, edge.name)
            assertEquals(if (edge.right) 580 else 540, resized.right, edge.name)
            assertEquals(if (edge.bottom) 510 else 480, resized.bottom, edge.name)
        }
    }

    @Test fun limitsAndMinimumDoNotAccumulateRoundingOrBreakWhenTheAppShrinks() {
        for (edge in MiniPopupEdge.entries) {
            val grown = resizeMiniPopup(start, edge, Offset(if (edge.left) -5000f else 5000f,
                if (edge.top) -5000f else 5000f), limits, 220)
            assertTrue(grown.left >= 0 && grown.top >= 0 && grown.right <= 900 && grown.bottom <= 700)
            val shrunk = resizeMiniPopup(start, edge, Offset(if (edge.left) 5000f else -5000f,
                if (edge.top) 5000f else -5000f), limits, 220)
            assertTrue(shrunk.width >= 220 && shrunk.height >= 220)
            assertEquals(start, resizeMiniPopup(start, edge, Offset.Zero, limits, 220))
        }
        assertEquals(IntRect(0,0,180,140), fitMiniPopup(start, IntRect(0,0,180,140),220))
    }

    @Test fun realPointerDragsStayUnderTheCursorWhileTheirOwnHandlesMove() {
        for (edge in MiniPopupEdge.entries) {
            var bounds by mutableStateOf(start)
            var dragging = false
            val scene = ImageComposeScene(900,700, coroutineContext = Dispatchers.Unconfined) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset { bounds.topLeft }.size(bounds.width.dp, bounds.height.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
                        MiniPopupResizeHandles(bounds, limits, 220, { dragging = it }, { bounds = it })
                    }
                }
            }
            try {
                var frame = 0L
                fun render() { scene.render(frame++ * 16_666_667L).close() }
                repeat(3) { render() }
                val x = when { edge.left -> start.left + 3f; edge.right -> start.right - 3f; else -> start.center.x.toFloat() }
                val y = when { edge.top -> start.top + 3f; edge.bottom -> start.bottom - 3f; else -> start.center.y.toFloat() }
                val down = Offset(x,y)
                scene.sendPointerEvent(PointerEventType.Press, down, type = PointerType.Mouse)
                for (step in 1..12) {
                    val delta = Offset(if (edge.left) -step * 5f else if (edge.right) step * 5f else 0f,
                        if (edge.top) -step * 4f else if (edge.bottom) step * 4f else 0f)
                    scene.sendPointerEvent(PointerEventType.Move, down + delta, type = PointerType.Mouse)
                    render()
                }
                assertTrue(dragging, "${edge.name} must have a working resize target")
                val total = Offset(if (edge.left) -60f else if (edge.right) 60f else 0f,
                    if (edge.top) -48f else if (edge.bottom) 48f else 0f)
                val expected = resizeMiniPopup(start, edge, total, limits, 220)
                // The gesture's initial touch slop is allowed, but local-origin feedback is not.
                assertTrue(kotlin.math.abs(bounds.width - expected.width) <= 10, "${edge.name}: $bounds vs $expected")
                assertTrue(kotlin.math.abs(bounds.height - expected.height) <= 10, "${edge.name}: $bounds vs $expected")
                scene.sendPointerEvent(PointerEventType.Release, down + total, type = PointerType.Mouse)
                render(); assertFalse(dragging)
                val settled = bounds; repeat(5) { render() }; assertEquals(settled, bounds)
            } finally { scene.close() }
        }
    }
}
