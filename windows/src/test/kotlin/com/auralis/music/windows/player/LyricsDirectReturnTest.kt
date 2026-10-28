package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.*
import com.auralis.music.ui.lyrics.smoothCenterLyric
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlin.test.*

class LyricsDirectReturnTest {
    @Test fun longReturnNeverVisitsTheTopAndLandsDirectlyOnThePausedTarget() = runBlocking(Dispatchers.Swing) {
        val state = LazyListState(40)
        val sizes = mutableMapOf<Int, Int>()
        var uiScope: CoroutineScope? = null
        val scene = ImageComposeScene(420, 400, coroutineContext = Dispatchers.Swing) {
            uiScope = rememberCoroutineScope()
            LazyColumn(state = state, contentPadding = PaddingValues(top = 140.dp, bottom = 220.dp),
                modifier = Modifier.fillMaxSize().background(Color.Black)) {
                items(90) { i -> Text("Phrase $i", color = Color.White,
                    modifier = Modifier.fillMaxWidth().height((40 + i % 4 * 15).dp).onSizeChanged { sizes[i] = it.height }) }
            }
        }
        try {
            suspend fun frame() { scene.render(System.nanoTime()).close(); delay(16) }
            repeat(4) { frame() }
            val job = uiScope!!.launch { state.smoothCenterLyric(12, .48f, sizes) }
            val trace = mutableListOf<Int>()
            repeat(70) { frame(); trace += state.firstVisibleItemIndex }
            job.join()
            assertTrue(trace.all { it >= 9 }, "Returning to line 12 must never visit the top: $trace")
            assertTrue(trace.distinct().size > 8, "A long return must contain intermediate frames")
            val item = state.layoutInfo.visibleItemsInfo.first { it.index == 12 }
            val layout = state.layoutInfo
            val anchor = layout.viewportStartOffset + (layout.viewportEndOffset - layout.viewportStartOffset) * .48f
            assertTrue(kotlin.math.abs(item.offset + item.size / 2f - anchor) < 3f,
                "The first return must center the actual target; no second jump is allowed")
            val settled = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            repeat(12) { frame() }
            assertEquals(settled, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
            val interrupted = uiScope!!.launch { state.smoothCenterLyric(60, .48f, sizes) }
            repeat(6) { frame() }
            interrupted.cancelAndJoin()
            val owned = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            repeat(8) { frame() }
            assertEquals(owned, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset,
                "Cancellation must not leave a deferred snap or correction")
        } finally { scene.close() }
    }

    @Test fun miniPopupIsInsetAtTheBottomRightIndependentOfItsSidebarAnchor() {
        val provider = MiniPopupPosition(16)
        for (side in listOf(220, 340, 500)) {
            val location = provider.calculatePosition(IntRect(1100, 30, 1500, 800), IntSize(1600, 900), LayoutDirection.Ltr, IntSize(side, side))
            assertEquals(IntOffset(1600 - side - 16, 900 - side - 16), location)
        }
        assertEquals(IntOffset.Zero, provider.calculatePosition(IntRect.Zero, IntSize(200, 200), LayoutDirection.Ltr, IntSize(340, 340)))
    }
}
