package com.auralis.music.windows.player

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.theme.DefaultArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class PlayerBackgroundStartupTest {
    private fun purpleCover(): File {
        val directory = File("build/screens/player-background-startup").apply { mkdirs() }
        val file = File.createTempFile("purple-", ".png", directory)
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 32) for (x in 0 until 32) image.setRGB(x, y, 0xff8c40aa.toInt())
        ImageIO.write(image, "png", file)
        return file
    }

    @Test fun sidebarAndPlayerResolutionsShareThePreloadedTexture() {
        val thumbnail = "https://lh3.googleusercontent.com/test-cover=w544-h544-l90-rj"
        assertEquals(auraTextureKey(thumbnail), auraTextureKey(getHighResArtworkUrl(thumbnail)!!))
        val file = purpleCover()
        val preloaded = runBlocking { loadAuraTexture(file.absolutePath) }
        assertNotNull(preloaded)
        file.delete()
        assertSame(preloaded, runBlocking { loadAuraTexture(file.absolutePath) },
            "Opening the player should use the prepared texture without reading artwork again")
    }

    @Test fun openingPlayerRendersAlbumColoursOnTheFirstFrame() {
        val file = purpleCover()
        assertNotNull(runBlocking { loadAuraTexture(file.absolutePath) })
        val scene = ImageComposeScene(320, 180, coroutineContext = Dispatchers.Unconfined) {
            ArtworkAura(DefaultArtworkPalette, Modifier, file.absolutePath, AuraPresentation.FULL_PLAYER)
        }
        try {
            val bytes = scene.render(0L).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
            }
            File(file.parentFile, "first-frame.png").writeBytes(bytes)
            val pixel = ImageIO.read(bytes.inputStream()).getRGB(160, 90)
            val r = pixel ushr 16 and 255
            val g = pixel ushr 8 and 255
            val b = pixel and 255
            assertEquals(255, pixel ushr 24)
            assertTrue(r > g + 50 && b > g + 70,
                "The very first frame must show the purple cover, without the placeholder: $r,$g,$b")
        } finally {
            scene.close()
            file.delete()
        }
    }

    @Test fun changingSongKeepsPreviousCoverWhileReplacementIsUnavailable() {
        val file = purpleCover()
        val preloaded = runBlocking { loadAuraTexture(file.absolutePath) }
        val artwork = mutableStateOf<String?>(file.absolutePath)
        var cover: State<ImageBitmap?>? = null
        val scene = ImageComposeScene(32, 32, coroutineContext = Dispatchers.Unconfined) {
            cover = rememberAuraCover(artwork.value)
        }
        try {
            scene.render(0L).close()
            assertSame(preloaded, cover!!.value)
            artwork.value = File(file.parentFile, "missing-cover.png").toURI().toString()
            repeat(10) { scene.render((it + 1) * 16_666_667L).close() }
            assertSame(preloaded, cover!!.value,
                "A song change must not reset the background to its placeholder")
        } finally {
            scene.close()
            file.delete()
        }
    }
}
