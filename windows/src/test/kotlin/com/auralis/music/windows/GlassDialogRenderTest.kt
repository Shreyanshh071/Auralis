package com.auralis.music.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.LocalGlassDialogHost
import com.auralis.music.windows.library.CreatePlaylistDialog
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class GlassDialogRenderTest {
    @Test fun centeredDialogBlursPageWithoutBlurringControls() {
        val opened = mutableStateOf(true)
        lateinit var source: PopupGlassSource
        val scene = ImageComposeScene(900, 650, coroutineContext = Dispatchers.Unconfined) {
            source = rememberPopupGlassSource()
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPopupGlassSource provides source,
                    LocalGlassDialogHost provides { dismiss, content -> GlassDialog(dismiss, content) }) {
                    Canvas(Modifier.fillMaxSize().recordPopupGlass(source)) {
                        for (x in 0..900 step 8) drawRect(if (x / 8 % 2 == 0) Color.White else Color.Blue,
                            Offset(x.toFloat(), 0f), Size(8f, size.height))
                    }
                    if (opened.value) GlassDialog({ opened.value = false }) {
                        Canvas(Modifier.size(440.dp, 220.dp)) {
                            drawRect(Color.White, Offset(210f, 90f), Size(4f, 20f))
                        }
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render(): java.awt.image.BufferedImage {
                repeat(6) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use {
                    it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes }
                }
                File("build/screens/glass-dialogs").mkdirs()
                File("build/screens/glass-dialogs/backdrop.png").writeBytes(bytes)
                return ImageIO.read(bytes.inputStream())
            }
            val image = render()
            fun variation(y: Int) = (270 until 350).sumOf { x ->
                kotlin.math.abs((image.getRGB(x,y) shr 16 and 255) - (image.getRGB(x+1,y) shr 16 and 255))
            }
            assertTrue(variation(290) < variation(120) / 3, "Panel blurs actual underlying page stripes")
            assertTrue(image.getRGB(418, 310) and 255 > 245, "Foreground mark remains sharp")
            assertTrue((image.getRGB(280,290) and 255) > (image.getRGB(280,290) shr 16 and 255) + 30,
                "Panel retains the blue backdrop colour")
            opened.value = false
            render()
            assertEquals(0, source.users.intValue, "Closing dialog releases page capture")
        } finally { scene.close() }
    }

    @Test fun playlistDialogRendersWithSharedGlassHost() {
        val scene = ImageComposeScene(900, 650, coroutineContext = Dispatchers.Unconfined) {
            val source = rememberPopupGlassSource()
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPopupGlassSource provides source,
                    LocalGlassDialogHost provides { dismiss, content -> GlassDialog(dismiss, content) }) {
                    Canvas(Modifier.fillMaxSize().recordPopupGlass(source)) {
                        for (x in 0..900 step 12) drawRect(if (x / 12 % 2 == 0) Color(0xFF683951) else Color(0xFF193F72),
                            Offset(x.toFloat(), 0f), Size(12f, size.height))
                    }
                    CreatePlaylistDialog(true, "Shreyanshh", {}, { _, _ -> })
                }
            }
        }
        try {
            repeat(8) { scene.render(it * 16_666_667L).close() }
            val bytes = scene.render(9 * 16_666_667L).use {
                it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes }
            }
            File("build/screens/glass-dialogs").mkdirs()
            File("build/screens/glass-dialogs/create-playlist.png").writeBytes(bytes)
        } finally { scene.close() }
    }
}
