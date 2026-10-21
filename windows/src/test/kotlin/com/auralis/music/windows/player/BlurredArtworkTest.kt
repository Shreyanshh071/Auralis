package com.auralis.music.windows.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class BlurredArtworkTest {
    /** Four bold quadrants, so a blur shows as soft gradients across the middle. */
    private fun cover(): File {
        val directory = File("build/screens/blurred-artwork").apply { mkdirs() }
        val image = BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 512) for (x in 0 until 512)
            image.setRGB(x, y, if (x < 256) (if (y < 256) 0xd02030 else 0x2050d0) else (if (y < 256) 0x30c060 else 0xe0c020))
        return File(directory, "cover.png").also { ImageIO.write(image, "png", it) }
    }

    @Test fun backgroundIsABlurredCoverAndCheaperPerFrameThanARenderEffectBlur() {
        val file = cover()
        assertNotNull(runBlocking { loadBlurredArtwork(file.absolutePath, 48f / (1280f * 1.1f)) })
        fun timeFrames(content: @androidx.compose.runtime.Composable () -> Unit): Pair<Long, ByteArray> {
            val scene = ImageComposeScene(1280, 820, coroutineContext = Dispatchers.Unconfined) { content() }
            try {
                repeat(5) { scene.render(it * 16_666_667L).close() }
                val started = System.nanoTime()
                repeat(30) { scene.render((it + 5) * 16_666_667L).close() }
                val elapsed = (System.nanoTime() - started) / 30
                val bytes = scene.render(40 * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { d -> d.bytes } }
                return elapsed to bytes
            } finally { scene.close() }
        }
        val (cached, cachedPng) = timeFrames { BlurredArtworkBackground(file.absolutePath, blurRadius = 48.dp, brightness = 1f) }
        val source = ImageIO.read(file).toComposeImageBitmap()
        val (effect, effectPng) = timeFrames {
            Box(Modifier.fillMaxSize()) {
                Image(source, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(.5f)
                    .graphicsLayer { scaleX = 2.2f; scaleY = 2.2f }.blur(24.dp))
            }
        }
        File(file.parentFile, "cached.png").writeBytes(cachedPng)
        File(file.parentFile, "effect.png").writeBytes(effectPng)
        println("BLUR per frame: cached ${cached / 1000}us, render effect ${effect / 1000}us")
        val picture = ImageIO.read(cachedPng.inputStream())
        // Soft: across the vertical colour edge in the middle the colour changes gradually.
        val row = 200
        val reds = (560 until 720 step 20).map { picture.getRGB(it, row) shr 16 and 255 }
        assertTrue(reds.zipWithNext().count { (a, b) -> a != b } >= 4, "Edge must be blurred, not sharp: $reds")
        assertTrue(cached < effect, "Cached blur must cost less per frame than re-blurring: $cached vs $effect")
    }
}
