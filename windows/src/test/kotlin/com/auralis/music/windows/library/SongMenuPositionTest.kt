package com.auralis.music.windows.library

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers

class SongMenuPositionTest {
    @Test fun recordsActualSongPressBeforeClickWithoutConsumingIt() {
        val anchor = MenuInvocationAnchor()
        var clicks = 0
        var openedAt: IntOffset? = null
        val scene = ImageComposeScene(600, 400, coroutineContext = Dispatchers.Unconfined) {
            CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                Box(Modifier.fillMaxSize().then(menuInvocationRecorder(anchor)).padding(30.dp)) {
                    Box(Modifier.fillMaxSize().clickable { clicks++; openedAt = anchor.point })
                }
            }
        }
        try {
            scene.render(0).close()
            scene.sendPointerEvent(PointerEventType.Press, Offset(210f, 120f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(210f, 120f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(500f, 300f))
            assertEquals(1, clicks)
            assertEquals(IntOffset(210, 120), openedAt)
            assertEquals(openedAt, anchor.point, "Moving the cursor after clicking must not move the menu")
            scene.sendPointerEvent(PointerEventType.Press, Offset(420f, 250f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(420f, 250f))
            assertEquals(2, clicks)
            assertEquals(IntOffset(420, 250), openedAt, "A different song must get its own click position")
        } finally { scene.close() }
    }

    @Test fun opensAtSongClick() {
        assertEquals(IntOffset(400, 120), songMenuPosition(IntOffset(400, 120), IntSize(1200, 800), IntSize(280, 560)))
    }
    @Test fun flipsAtBottomRightEdge() {
        assertEquals(IntOffset(890, 210), songMenuPosition(IntOffset(1170, 770), IntSize(1200, 800), IntSize(280, 560)))
    }
    @Test fun remainsInsideSmallWindow() {
        assertEquals(IntOffset.Zero, songMenuPosition(IntOffset(40, 40), IntSize(240, 400), IntSize(280, 560)))
    }
}
