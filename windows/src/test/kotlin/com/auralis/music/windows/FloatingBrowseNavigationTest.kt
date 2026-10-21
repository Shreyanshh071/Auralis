package com.auralis.music.windows

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import com.auralis.music.windows.browse.FloatingBrowseNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class FloatingBrowseNavigationTest {
    @Test fun backRemainsClickableAtTheSamePositionAfterScrolling() {
        val scroll = ScrollState(0)
        var back = 0
        var rowClicks = 0
        val scene = ImageComposeScene(600, 400, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                FloatingBrowseNavigation({ back++ }) {
                    Column(Modifier.fillMaxSize().background(Color(0xFF35303F)).verticalScroll(scroll)) {
                        repeat(30) { row ->
                            Box(Modifier.fillMaxWidth().height(80.dp).clickable { rowClicks++ }.padding(24.dp)) {
                                Text("Album / artist content $row", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
        try {
            scene.render(0L).close()
            runBlocking { scroll.scrollTo(1200) }
            repeat(3) { scene.render((it + 1) * 16_666_667L).close() }
            assertEquals(1200, scroll.value)
            val output = File("build/screens/floating-browse-back.png").apply { parentFile.mkdirs() }
            scene.render(66_666_668L).use { image -> image.encodeToData()!!.use { output.writeBytes(it.bytes) } }
            scene.sendPointerEvent(PointerEventType.Press, Offset(32f, 28f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(32f, 28f))
            assertEquals(1, back, "Back must remain in the viewport after the hero scrolls away")
            assertEquals(0, rowClicks, "The floating button must consume clicks above the song rows")
        } finally { scene.close() }
    }
}
