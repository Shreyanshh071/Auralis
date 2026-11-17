package com.auralis.music.windows.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.HandCursor
import com.auralis.music.ui.components.clickable
import com.auralis.music.ui.components.handCursorRoot
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerScreenOverlayTest {

    @Test
    fun overlayBlocksClicksHoversDragsAndScrollsBehindPlayerWhileKeepingOwnControlsInteractive() {
        var playlistClicks = 0
        var miniPlayerClicks = 0
        var draggedBehind = false
        var overlayButtonClicks = 0
        var sliderValue by mutableFloatStateOf(0.2f)
        var showPlayer by mutableStateOf(true)
        var selectedPanel by mutableStateOf(PlayerPanel.LYRICS)
        var tabsBounds = androidx.compose.ui.geometry.Rect.Zero
        val behindListState = androidx.compose.foundation.lazy.LazyListState()
        val overlayListState = androidx.compose.foundation.lazy.LazyListState()

        val scene = ImageComposeScene(1200, 800, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().handCursorRoot()) {
                    Column(Modifier.fillMaxSize()) {
                        // Underlying playlist row area
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clickable { playlistClicks++ }
                        ) {
                            Text("Playlist Song Row")
                        }
                        // Underlying scrollable playlist area
                        LazyColumn(
                            state = behindListState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectDragGestures { _, _ -> draggedBehind = true }
                                }
                        ) {
                            items(50) { index ->
                                Box(Modifier.fillMaxWidth().height(56.dp)) {
                                    Text("Track $index")
                                }
                            }
                        }
                        // Underlying mini-player bar at bottom
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .clickable { miniPlayerClicks++ }
                        ) {
                            Text("Mini Player")
                        }
                    }

                    AnimatedVisibility(
                        visible = showPlayer,
                        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF050505))
                                .blockUnderlyingPointerEvents()
                        ) {
                            PlayerPanelTabs(
                                selected = selectedPanel,
                                onSelect = { selectedPanel = it },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 24.dp)
                                    .onGloballyPositioned { tabsBounds = it.boundsInRoot() }
                            )
                            LazyColumn(
                                state = overlayListState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .size(300.dp, 300.dp)
                            ) {
                                items(40) { index ->
                                    Box(Modifier.fillMaxWidth().height(48.dp)) {
                                        Text("Queue item $index")
                                    }
                                }
                            }
                            HoverSlider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(32.dp)
                                    .size(width = 240.dp, height = 24.dp)
                            )
                            IconButton(
                                onClick = { overlayButtonClicks++ },
                                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).size(48.dp)
                            ) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }

        try {
            var frame = 0L
            fun render(count: Int = 4) {
                repeat(count) { scene.render(frame++ * 16_666_667L).close() }
            }
            render()

            // 1. Move and click over the hidden playlist row at top-center (600, 100)
            scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 100f), type = PointerType.Mouse)
            render(2)
            assertFalse(HandCursor.active, "Hovering non-interactive player area must not trigger hand cursor from hidden playlist")
            scene.sendPointerEvent(PointerEventType.Press, Offset(600f, 100f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(600f, 100f), type = PointerType.Mouse)
            render(2)
            assertEquals(0, playlistClicks, "Clicking on player overlay must not click playlist row behind it")

            // 2. Move and click over the hidden mini-player at the bottom (300, 760)
            scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 760f), type = PointerType.Mouse)
            render(2)
            assertFalse(HandCursor.active, "Hovering over bottom of player must not trigger hand cursor from hidden mini-player")
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 760f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 760f), type = PointerType.Mouse)
            render(2)
            assertEquals(0, miniPlayerClicks, "Clicking bottom of player must not click mini-player behind it")

            // 3. Scroll and drag over the empty left/center area of the overlay (400, 400)
            scene.sendPointerEvent(
                PointerEventType.Scroll,
                Offset(400f, 400f),
                scrollDelta = Offset(0f, 5f),
                type = PointerType.Mouse
            )
            scene.sendPointerEvent(PointerEventType.Press, Offset(400f, 400f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Move, Offset(400f, 300f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(400f, 300f), type = PointerType.Mouse)
            render(2)
            assertEquals(0, behindListState.firstVisibleItemIndex, "Scrolling over player must not scroll underlying playlist")
            assertEquals(0, behindListState.firstVisibleItemScrollOffset)
            assertFalse(draggedBehind, "Dragging over player must not trigger drag gestures behind it")

            // 4. Overlay's own controls remain interactive (close button, Queue tab, Queue scroll, HoverSlider)
            scene.sendPointerEvent(PointerEventType.Press, Offset(36f, 36f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(36f, 36f), type = PointerType.Mouse)
            render(2)
            assertEquals(1, overlayButtonClicks, "Player's own close button must still receive clicks")

            val queueTabPoint = Offset(tabsBounds.right - 30f, tabsBounds.center.y)
            scene.sendPointerEvent(PointerEventType.Move, queueTabPoint, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, queueTabPoint, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, queueTabPoint, type = PointerType.Mouse)
            render(2)
            assertEquals(PlayerPanel.QUEUE, selectedPanel, "Player's Queue tab must still be clickable")

            // Queue LazyColumn inside overlay still scrolls
            scene.sendPointerEvent(
                PointerEventType.Scroll,
                Offset(1050f, 400f),
                scrollDelta = Offset(0f, 4f),
                type = PointerType.Mouse
            )
            render(2)
            assertTrue(
                overlayListState.firstVisibleItemIndex > 0 || overlayListState.firstVisibleItemScrollOffset > 0,
                "Queue list inside player must still scroll"
            )

            // Slider inside overlay still updates on click/drag (at bottom-start: x=32..272, y=744..768)
            scene.sendPointerEvent(PointerEventType.Move, Offset(152f, 756f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, Offset(152f, 756f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Move, Offset(212f, 756f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(212f, 756f), type = PointerType.Mouse)
            render(2)
            assertTrue(sliderValue > 0.6f, "HoverSlider inside player must still respond to drag: $sliderValue")

            // 5. Closing the player restores clicks to the underlying playlist and mini-player,
            // and re-opening over a hovered underlying button prevents clicking it even without moving
            showPlayer = false
            render(20)
            scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 100f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, Offset(600f, 100f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(600f, 100f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 760f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 760f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 760f), type = PointerType.Mouse)
            render(2)
            assertEquals(1, playlistClicks, "Playlist must be clickable again once player closes")
            assertEquals(1, miniPlayerClicks, "Mini-player must be clickable again once player closes")

            // Re-open player directly over stationary cursor at (300, 760)
            showPlayer = true
            render(20)
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 760f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 760f), type = PointerType.Mouse)
            render(2)
            assertEquals(1, miniPlayerClicks, "Re-opened player must immediately block clicks over stationary cursor")
        } finally {
            scene.close()
        }
    }

    @Test
    fun canvasStagePersistsAcrossLyricsAndQueueTabsWithoutRestartingCanvasSlot() {
        val prefs = CanvasPrefs(enabled = true, source = CanvasSource.AUTO, spotifyCanvas = true)
        assertTrue(shouldShowCanvasStage(prefs, 1200.dp, stagePlayer = true, tabbedPlayer = true))
        assertFalse(shouldShowCanvasStage(prefs, 900.dp, stagePlayer = true, tabbedPlayer = true))
        assertFalse(shouldShowCanvasStage(prefs, 1200.dp, stagePlayer = false, tabbedPlayer = true))
        assertFalse(shouldShowCanvasStage(prefs, 1200.dp, stagePlayer = true, tabbedPlayer = false))
        assertFalse(
            shouldShowCanvasStage(
                CanvasPrefs(enabled = false, spotifyCanvas = false),
                1200.dp,
                stagePlayer = true,
                tabbedPlayer = true
            )
        )

        var panel by mutableStateOf(PlayerPanel.LYRICS)
        var canvasMountCount by mutableIntStateOf(0)
        var canvasDisposeCount by mutableIntStateOf(0)

        val scene = ImageComposeScene(1200, 800, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                val stageActive = shouldShowCanvasStage(prefs, 1200.dp, stagePlayer = true, tabbedPlayer = true)
                Box(Modifier.fillMaxSize()) {
                    if (stageActive) {
                        DisposableEffect(Unit) {
                            canvasMountCount++
                            onDispose { canvasDisposeCount++ }
                        }
                    }
                    when (panel) {
                        PlayerPanel.LYRICS -> Text("Lyrics")
                        PlayerPanel.QUEUE -> Text("Queue")
                    }
                }
            }
        }

        try {
            var frame = 0L
            repeat(4) { scene.render(frame++ * 16_666_667L).close() }
            assertEquals(1, canvasMountCount)
            assertEquals(0, canvasDisposeCount)

            panel = PlayerPanel.QUEUE
            repeat(4) { scene.render(frame++ * 16_666_667L).close() }
            assertEquals(1, canvasMountCount)
            assertEquals(0, canvasDisposeCount, "Switching to Queue must not dispose the canvas")

            panel = PlayerPanel.LYRICS
            repeat(4) { scene.render(frame++ * 16_666_667L).close() }
            assertEquals(1, canvasMountCount)
            assertEquals(0, canvasDisposeCount, "Switching back to Lyrics must keep the same canvas instance")
        } finally {
            scene.close()
        }
    }
}
