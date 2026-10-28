package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.lyrics.fadingEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*

class LyricsLayerClearingTest {
    @Test fun scrollingTranslucentBlurredLyricsBackLeavesTheSamePixelsAsACleanFrame() {
        val state = LazyListState()
        val scene = ImageComposeScene(500, 360, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF243447))) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().fadingEdge()) {
                    items(80) { row ->
                        Text("$row · Words moving through the lyrics", color = Color.White.copy(alpha = .4f),
                            modifier = Modifier.fillMaxWidth().padding(12.dp).graphicsLayer {
                                renderEffect = BlurEffect(1.5f, 1.5f, TileMode.Decal)
                                clip = false
                            })
                    }
                }
            } }
        }
        try {
            var frame = 0L
            fun capture(): ByteArray {
                repeat(4) { scene.render(frame++ * 16_666_667L).close() }
                return scene.render(frame++ * 16_666_667L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                }
            }
            val original = capture()
            for (row in listOf(18, 4, 39, 7)) {
                runBlocking { state.scrollToItem(row) }
                capture()
            }
            runBlocking { state.scrollToItem(0) }
            assertContentEquals(original, capture(), "Old text must not accumulate behind the current viewport")
            assertContentEquals(original, capture(), "Idle frames must not retain scrolling trails")
        } finally { scene.close() }
    }
}
