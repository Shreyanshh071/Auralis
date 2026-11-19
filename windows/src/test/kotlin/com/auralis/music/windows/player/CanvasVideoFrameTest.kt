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
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasVideoFrameTest {
    @Test fun stageBackdropKeepsTheFrameFramingAndSoftensDetails() {
        val frame = org.jetbrains.skia.Surface.makeRasterN32Premul(200, 200).use { surface ->
            surface.canvas.clear(0xff30243c.toInt())
            org.jetbrains.skia.Paint().use { paint ->
                paint.color = 0xffeeeeee.toInt()
                surface.canvas.drawCircle(130f, 116f, 14f, paint)
            }
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
        val backdrop = stageBackdropTexture(frame, 45, previous = null)
        assertEquals(80, backdrop.width)
        assertEquals(45, backdrop.height)
        val pixels = backdrop.toPixelMap()
        assertTrue(pixels[55, 30].red > pixels[25, 30].red + .10f, "The highlight stays to the right of centre")
        assertTrue(pixels[55, 30].red < .84f, "The highlight is blurred into the background")
        assertTrue(pixels[61, 30].red > pixels[25, 30].red + .04f, "Blur spreads the highlight beyond its sharp boundary")
        assertEquals(1f, pixels[0, 0].alpha)
    }

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
        fun pixel(x: Int = 24, y: Int = 32): Int {
            repeat(40) { scene.render(time.also { time += 16_666_667L }).close() }
            return scene.render(time.also { time += 16_666_667L }).use { rendered ->
                Bitmap.makeFromImage(rendered).use { it.getColor(x, y) }
            }
        }
        try {
            repeat(4) {
                frame.value = null
                pixel()
                frame.value = red
                val redStrength = pixel() shr 16 and 255
                assertTrue(redStrength in (if (canvas.value) 120..165 else 231..255), "First frame fades in with the stage grade")
                frame.value = blue
                val blueStrength = pixel() and 255
                assertTrue(blueStrength > 120, "Later decoded frames replace the first")
                if (canvas.value) {
                    assertTrue(pixel(53) and 255 < blueStrength / 3, "Sharp artwork fades by the start of the lyrics")
                    assertTrue(pixel(24, 2) and 255 < blueStrength / 2, "The top feathers into the backdrop")
                    assertTrue(pixel(24, 56) and 255 < blueStrength * .6f, "The dock has a separate shade")
                }
                canvas.value = !canvas.value
                assertTrue(pixel() and 255 > 120, "Changing the stage layout retains playback")
                visible.value = false
                pixel()
                player.intValue++
                visible.value = true
                assertTrue(pixel() and 255 > 120, "Reopening the player starts a fresh fade")
            }
        } finally { scene.close() }
    }
}
