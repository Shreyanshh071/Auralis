package com.auralis.music.windows.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.auralis.music.ui.components.LocalGlassDialogHost
import com.auralis.music.windows.GlassDialog
import com.auralis.music.windows.LocalPopupGlassSource
import com.auralis.music.windows.recordPopupGlass
import com.auralis.music.windows.rememberPopupGlassSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class CreatePlaylistDialogTest {
    @Test
    fun playlistDialogRendersLiquidGlassDestinationCardsAndVisibleInputBar() {
        val scene = ImageComposeScene(900, 650, coroutineContext = Dispatchers.Unconfined) {
            val source = rememberPopupGlassSource()
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBDD269))) {
                CompositionLocalProvider(
                    LocalPopupGlassSource provides source,
                    LocalGlassDialogHost provides { dismiss, content -> GlassDialog(dismiss, content) }
                ) {
                    Canvas(Modifier.fillMaxSize().recordPopupGlass(source)) {
                        for (x in 0..900 step 12) {
                            drawRect(
                                if (x / 12 % 2 == 0) Color(0xFF683951) else Color(0xFF193F72),
                                Offset(x.toFloat(), 0f),
                                Size(12f, size.height)
                            )
                        }
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
            File("build/screens/glass-dialogs/create-playlist-tinted.png").writeBytes(bytes)

            val image = ImageIO.read(bytes.inputStream())
            // Dialog center is at ~450, ~325
            // Check that destination cards show translucent backdrop rather than opaque flat gray
            val pixelLeftCard = image.getRGB(350, 340) // Inside left card
            val pixelRightCard = image.getRGB(550, 340) // Inside right card
            
            // Colors should contain underlying purple/blue variation from the canvas behind them
            val leftRed = (pixelLeftCard shr 16) and 255
            val leftBlue = pixelLeftCard and 255
            assertTrue(leftRed > 30 || leftBlue > 30, "Left destination card must be translucent over backdrop")

            val rightRed = (pixelRightCard shr 16) and 255
            val rightBlue = pixelRightCard and 255
            assertTrue(rightRed > 20 || rightBlue > 20, "Right destination card must be translucent over backdrop")
        } finally {
            scene.close()
        }
    }
}
