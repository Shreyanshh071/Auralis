package com.auralis.music.windows.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import javax.imageio.ImageIO
import kotlin.test.*

class RecentSongHoverTest {
    @Test fun rowHighlightsCrossFadeAndMenuClicksDoNotPlayTheSong() {
        var plays = 0
        var menus = 0
        val scene = ImageComposeScene(460, 112, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color(0xFF202020))) {
                    repeat(2) { CompactSong(Track("track$it", "A song", "Artist"), false,
                        { plays++ }, { menus++ }, Modifier.fillMaxWidth()) }
                }
            }
        }
        try {
            var frame = 0L
            fun render() = scene.render(frame++ * 16_666_667L).use {
                it.encodeToData(EncodedImageFormat.PNG)!!.use { ImageIO.read(it.bytes.inputStream()) }
            }
            fun level(y: Int) = render().getRGB(250, y) and 255
            repeat(16) { render() }
            val idle = level(6)
            scene.sendPointerEvent(PointerEventType.Move, Offset(250f, 28f), type = PointerType.Mouse)
            assertEquals(idle, level(6))
            repeat(16) { render() }
            val lit = level(6)
            assertTrue(lit > idle + 10)
            scene.sendPointerEvent(PointerEventType.Move, Offset(250f, 84f), type = PointerType.Mouse)
            assertEquals(lit, level(6), "Old row fades out rather than vanishing")
            repeat(4) { render() }
            val fading = level(6)
            assertTrue(fading in (idle + 1) until lit)
            val entering = level(62)
            assertTrue(entering in (idle + 1) until lit)
            repeat(16) { render() }
            assertEquals(idle, level(6))
            assertEquals(lit, level(62))
            fun click(x: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, 84f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, 84f), type = PointerType.Mouse)
            }
            click(250f); assertEquals(1, plays)
            click(446f); assertEquals(1, menus); assertEquals(1, plays)
        } finally { scene.close() }
    }
}
