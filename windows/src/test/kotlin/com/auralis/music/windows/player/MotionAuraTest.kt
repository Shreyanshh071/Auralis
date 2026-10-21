package com.auralis.music.windows.player

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.auralis.music.ui.theme.DefaultArtworkPalette
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class MotionAuraTest {
    private fun frame(rgb: Int, width: Int = 32, height: Int = 32) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, rgb)
        }.toComposeImageBitmap()

    @Test fun backgroundFollowsVideoFramesAndReturnsToStillWhenDisabled() = runBlocking {
        val output = File("build/screens/motion-aura").apply { mkdirs() }
        val still = File(output, "still.png")
        ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until 32) for (x in 0 until 32) setRGB(x, y, 0xffcc2222.toInt())
        }, "png", still)
        loadAuraTexture(still.absolutePath)
        val feed = MotionAura()
        val collector = launch { feed.collectFrames() }
        val scene = ImageComposeScene(160, 100, coroutineContext = Dispatchers.Unconfined) {
            ArtworkAura(DefaultArtworkPalette, Modifier, still.absolutePath,
                AuraPresentation.FULL_PLAYER, motionAura = feed)
        }
        var time = 0L
        fun render(name: String): Int {
            repeat(40) { scene.render(time).close(); time += 33_333_333L }
            val bytes = scene.render(time).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
            File(output, "$name.png").writeBytes(bytes)
            return ImageIO.read(bytes.inputStream()).getRGB(80, 50)
        }
        suspend fun update(rgb: Int) {
            val old = feed.texture.value
            feed.offer(frame(rgb))
            withTimeout(5000) { while (feed.texture.value === old) delay(10) }
        }
        try {
            render("before")
            update(0xff228844.toInt())
            val green = render("green-video")
            assertTrue((green ushr 8 and 255) > (green ushr 16 and 255) + 40)
            update(0xff2244bb.toInt())
            val blue = render("blue-video")
            assertTrue((blue and 255) > (blue ushr 8 and 255) + 40)
            feed.offer(null)
            assertNull(feed.texture.value)
            val red = render("disabled-still")
            assertTrue((red ushr 16 and 255) > (red ushr 8 and 255) + 40)
        } finally { scene.close(); feed.close(); collector.cancelAndJoin() }
    }

    @Test fun queuedFramesCannotRestoreADisposedAnimation() = runBlocking<Unit> {
        val feed = MotionAura()
        feed.offer(frame(0xffff0000.toInt()))
        feed.offer(null)
        val collector = launch { feed.collectFrames() }
        try {
            delay(100)
            assertNull(feed.texture.value)
            feed.offer(frame(0xff008800.toInt()))
            withTimeout(5000) { while (feed.texture.value == null) delay(10) }
            assertNotNull(feed.texture.value)
        } finally { feed.close(); collector.cancelAndJoin() }
    }

    @Test fun tallMotionArtworkUsesTheSameCentreCropAsTheVisibleCover() {
        val image = BufferedImage(32, 96, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 96) for (x in 0 until 32)
            image.setRGB(x, y, if (y in 32..63) 0xff008800.toInt() else 0xffff0000.toInt())
        val pixels = IntArray(128 * 128)
        motionAuraTexture(image.toComposeImageBitmap()).readPixels(pixels)
        assertTrue(pixels.all { (it ushr 8 and 255) > (it ushr 16 and 255) })
    }
}
