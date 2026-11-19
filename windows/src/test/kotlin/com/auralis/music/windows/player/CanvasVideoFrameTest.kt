package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

class CanvasVideoFrameTest {
    @Test fun framesKeepUpdatingAcrossFadesAndPlayerReplacement() {
        fun image(color: Int): ImageBitmap = org.jetbrains.skia.Surface.makeRasterN32Premul(16, 16).use { surface ->
            surface.canvas.clear(color)
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
        val red = image(0xffff0000.toInt())
        val blue = image(0xff0000ff.toInt())
        val frame = mutableStateOf<ImageBitmap?>(null)
        val player = mutableIntStateOf(0)
        val canvas = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val scene = ImageComposeScene(64, 64, coroutineContext = Dispatchers.Unconfined) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (visible.value) key(player.intValue) { CanvasVideoFrame(frame.value, canvas.value) }
            }
        }
        var time = 0L
        fun pixel(): Int {
            repeat(40) { scene.render(time.also { time += 16_666_667L }).close() }
            return scene.render(time.also { time += 16_666_667L }).use { rendered ->
                Bitmap.makeFromImage(rendered).use { it.getColor(8, 32) }
            }
        }
        try {
            repeat(4) {
                frame.value = null
                pixel()
                frame.value = red
                assertTrue(pixel() shr 16 and 255 > 230, "First frame fades in")
                frame.value = blue
                assertTrue(pixel() and 255 > 230, "Later decoded frames replace the first")
                canvas.value = !canvas.value
                assertTrue(pixel() and 255 > 230, "Changing the stage layout retains playback")
                visible.value = false
                pixel()
                player.intValue++
                visible.value = true
                assertTrue(pixel() and 255 > 230, "Reopening the player starts a fresh fade")
            }
        } finally { scene.close() }
    }
}
