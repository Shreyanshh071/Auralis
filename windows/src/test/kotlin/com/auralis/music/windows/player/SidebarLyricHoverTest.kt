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
            val transition = (6..25).map(::frame)
            val hovered = transition.last()
            fun opacity(image: java.awt.image.BufferedImage) = image.getRGB(300,25) ushr 24 and 255
            assertTrue(opacity(transition[3]) > opacity(before), "Hover begins fading in without a delay")
            assertTrue(opacity(transition[3]) < opacity(hovered), "Early hover is an intermediate shade, not the final highlight: ${transition.map(::opacity)}")
            assertTrue(opacity(transition[8]) >= opacity(transition[3]), "Highlight brightens smoothly")
            assertNotEquals(before.getRGB(300, 25), hovered.getRGB(300, 25), "Hover must paint a translucent tile")
            assertEquals(before.getRGB(0, 2), hovered.getRGB(0, 2), "Rounded corners must remain clear")
            assertEquals(0, seeks, "Hover must never seek")
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 25f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 25f))
            frame(26)
            assertEquals(1, seeks, "Clicking a timed lyric must seek once")
            scene.sendPointerEvent(PointerEventType.Move, Offset(450f, 25f))
            val exit = (27..47).map(::frame)
            assertTrue(opacity(exit[3]) in (opacity(before)+1) until opacity(hovered), "Leaving fades out through an intermediate shade")
            assertEquals(opacity(before), opacity(exit.last()), "Highlight fully clears after leaving")
        } finally { scene.close() }
    }
}

