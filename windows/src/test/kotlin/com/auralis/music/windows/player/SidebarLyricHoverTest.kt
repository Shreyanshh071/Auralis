package com.auralis.music.windows.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import javax.imageio.ImageIO
import kotlin.test.*

class SidebarLyricHoverTest {
    @Test fun mouseHoverPaintsARoundedTileWithoutSeeking() {
        var seeks = 0
        val scene = ImageComposeScene(400, 100, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { SidebarLyricLine("A lyric line", false, true, { seeks++ }) }
        }
        try {
            fun frame(index: Int) = ImageIO.read(scene.render(index * 16_666_667L)
                .encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }.inputStream())
            repeat(5) { frame(it) }
            val before = frame(5)
            scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 25f))
            val hovered = (6..25).map(::frame).last()
            assertNotEquals(before.getRGB(300, 25), hovered.getRGB(300, 25), "Hover must paint a translucent tile")
            assertEquals(before.getRGB(0, 2), hovered.getRGB(0, 2), "Rounded corners must remain clear")
            assertEquals(0, seeks, "Hover must never seek")
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 25f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 25f))
            frame(26)
            assertEquals(1, seeks, "Clicking a timed lyric must seek once")
        } finally { scene.close() }
    }
}
