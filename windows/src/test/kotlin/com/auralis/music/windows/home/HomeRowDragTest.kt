package com.auralis.music.windows.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class HomeRowDragTest {
    @Test fun draggingCardsScrollsWithoutClickingButNormalClicksStillWork() {
        lateinit var list: LazyListState
        var clicks = 0
        val scene = ImageComposeScene(400, 120, coroutineContext = Dispatchers.Unconfined) {
            list = rememberLazyListState()
            LazyRow(state = list, modifier = Modifier.fillMaxSize().mouseDragScroll(list)) {
                items(10) { Box(Modifier.size(150.dp, 100.dp).clickable { clicks++ }) }
            }
        }
        try {
            var frame = 0L
            fun settle() { repeat(3) { scene.render(frame++ * 16_666_667L).close() } }
            settle()
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 50f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(280f, 50f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(160f, 50f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(160f, 50f))
            settle()
            assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 80, "Mouse hold and drag should scroll")
            assertEquals(0, clicks, "Dragging a cover must not play/open it")
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 50f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 50f))
            settle()
            assertEquals(1, clicks, "Normal clicks should still open/play cards")
            val before = list.firstVisibleItemIndex * 150 + list.firstVisibleItemScrollOffset
            scene.sendPointerEvent(PointerEventType.Press, Offset(160f, 50f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(270f, 50f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(270f, 50f))
            settle()
            assertTrue(list.firstVisibleItemIndex * 150 + list.firstVisibleItemScrollOffset < before, "Dragging right scrolls back")
            assertEquals(1, clicks)
        } finally { scene.close() }
    }
}
