package com.auralis.music.windows.player

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.auralis.music.ui.theme.DefaultArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ShortcutAuraLoadingTest {
    @Test fun webpArtworkLoadsIntoTheAura() = runBlocking {
        val directory = File("build/screens/shortcut-aura").apply { mkdirs() }
        val cover = File.createTempFile("webp-cover-", ".webp", directory)
        try {
            Surface.makeRasterN32Premul(48, 48).use { surface ->
                surface.canvas.clear(0xffc74432.toInt())
                val bytes = surface.makeImageSnapshot().use { image ->
                    image.encodeToData(EncodedImageFormat.WEBP, 95)!!.use { it.bytes }
                }
                cover.writeBytes(bytes)
            }
            assertNotNull(loadAuraTexture(cover.absolutePath), "WebP must load just like the visible cover")
            Unit
        } finally { cover.delete() }
    }

    @Test fun loadingShortcutShowsNeutralBackgroundWithoutBlueFlash() {
        val scene = ImageComposeScene(260, 56, coroutineContext = Dispatchers.Unconfined) {
            ArtworkAura(DefaultArtworkPalette, Modifier.fillMaxSize(), presentation = AuraPresentation.SHORTCUT)
        }
        try {
            val bytes = scene.render(0L).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
            }
            val frame = ImageIO.read(bytes.inputStream())
            val pixel = frame.getRGB(130, 28)
            assertEquals(0xff202024.toInt(), pixel, "No default coloured aura before cover decoding")
        } finally { scene.close() }
    }
}
