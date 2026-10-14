package com.auralis.music.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class GlassDropdownRenderTest {
    @Test fun dropdownSamplesBackdropBelowItsAnchorAndKeepsContentSharp() {
        val scene = ImageComposeScene(500, 300, coroutineContext = Dispatchers.Unconfined) {
            val source = rememberPopupGlassSource()
            CompositionLocalProvider(LocalPopupGlassSource provides source) {
                Box {
                    Canvas(Modifier.fillMaxSize().recordPopupGlass(source)) {
                        for (x in 0..500 step 8) drawRect(if ((x / 8) % 2 == 0) Color.White else Color.Blue,
                            Offset(x.toFloat(), 0f), Size(8f, size.height))
                    }
                    Box(Modifier.padding(start = 40.dp, top = 40.dp)) {
                        Box(Modifier.size(100.dp, 40.dp)) {
                            GlassDropdownMenu(true, {}) {
                                Canvas(Modifier.size(180.dp, 80.dp)) { drawRect(Color.White, Offset(70f, 20f), Size(4f, 20f)) }
                            }
                        }
                    }
                }
            }
        }
        try {
            repeat(8) { scene.render(it * 16_666_667L).close() }
            val bytes = scene.render(200_000_000L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
            File("build/screens/glass-dropdown.png").writeBytes(bytes)
            val image = ImageIO.read(bytes.inputStream())
            fun variation(y: Int) = (70 until 150).sumOf { x -> kotlin.math.abs((image.getRGB(x, y) shr 16 and 255) - (image.getRGB(x + 1, y) shr 16 and 255)) }
            assertTrue(variation(145) < variation(30) / 3)
            assertTrue((image.getRGB(79, 115) shr 16 and 255) > 245, "Menu foreground stays sharp")
        } finally { scene.close() }
    }
}
