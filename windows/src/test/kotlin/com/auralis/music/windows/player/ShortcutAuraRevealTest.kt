package com.auralis.music.windows.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import com.auralis.music.ui.theme.DefaultArtworkPalette
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** A shortcut's aura sweeps in from the right once its cover loads, instead of popping out of grey. */
class ShortcutAuraRevealTest {
    @Test fun auraSweepsInFromTheRightAfterLoading() {
        val directory = File("build/screens/shortcut-reveal").apply { mkdirs() }
        // A new file each run: the aura cache must not already hold it.
        val cover = File.createTempFile("red-cover-", ".png", directory).apply { deleteOnExit() }
        Surface.makeRasterN32Premul(48, 48).use { surface ->
            surface.canvas.clear(0xffd02a2a.toInt())
            cover.writeBytes(surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } })
        }
        val scene = ImageComposeScene(260, 56, coroutineContext = Dispatchers.Unconfined) {
            ArtworkAura(DefaultArtworkPalette, Modifier.fillMaxSize(), cover.absolutePath, AuraPresentation.SHORTCUT)
        }
        try {
            var frame = 0L
            fun render(name: String? = null) = scene.render(frame++ * 16_666_667L).use { image ->
                val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                if (name != null) File(directory, "$name.png").writeBytes(bytes)
                ImageIO.read(bytes.inputStream())
            }
            fun redness(image: java.awt.image.BufferedImage, x: Int): Int {
                val p = image.getRGB(x, 28)
                return ((p shr 16) and 255) - (p and 255)
            }
            // The cover decodes off the render thread: wait for the sweep to begin.
            var started = render("loading")
            var waited = 0
            while (redness(started, 240) < 20 && waited++ < 400) { Thread.sleep(5); started = render() }
            assertTrue(waited < 400, "The aura never started to appear")
            assertTrue(redness(started, 240) > redness(started, 20), "The sweep reaches the right of the card first")
            val frames = List(80) { index -> render(if (index % 10 == 0) "reveal-$index" else null) }
            val middle = frames[25]
            assertTrue(redness(middle, 240) >= redness(middle, 20), "Still sweeping right to left midway")
            val done = frames.last()
            assertTrue(redness(done, 20) > 40 && redness(done, 240) > 40, "Fully revealed across the card")
        } finally { scene.close() }
    }
}
