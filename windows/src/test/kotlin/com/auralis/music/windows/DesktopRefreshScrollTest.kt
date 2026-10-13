package com.auralis.music.windows

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.AuralisRefreshBox
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class DesktopRefreshScrollTest {
    @Test fun pageDoesNotShiftOrRefreshAtEitherScrollBoundary() {
        for (loadingOverload in listOf(false, true)) {
            var refreshCalls = 0
            var pageTop = Float.NaN
            val scroll = ScrollState(0)
            val scene = ImageComposeScene(300, 200, coroutineContext = Dispatchers.Unconfined) {
                val content: @androidx.compose.runtime.Composable BoxScope.() -> Unit = {
                    Column(Modifier.fillMaxSize().onGloballyPositioned { pageTop = it.positionInRoot().y }.verticalScroll(scroll)) {
                        repeat(30) { Text("Playlist $it", Modifier.height(40.dp)) }
                    }
                }
                if (loadingOverload) AuralisRefreshBox(true, { refreshCalls++ }, content = content)
                else AuralisRefreshBox(refresh = { refreshCalls++ }, content = content)
            }
            try {
                var frame = 0L
                fun render() { scene.render(frame++ * 16_666_667L).close() }
                repeat(4) { render() }
                for (delta in listOf(-100f, 100f, 100f, -100f, -100f)) {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(100f,100f), scrollDelta = Offset(0f,delta), type = PointerType.Mouse)
                    repeat(60) { render(); assertEquals(0f, pageTop, "Page must not be displaced by refresh") }
                }
                assertEquals(0, refreshCalls)
                assertEquals(0, scroll.value)
            } finally { scene.close() }
        }
    }
}
