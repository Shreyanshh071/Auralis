package com.auralis.music.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class GlassLensRenderTest {
    @Test fun tallSurfaceBendsTheGridBeyondTheOldNarrowRim() {
        val effects = listOf(DockGlassEffect(), DockGlassEffect())
        val scene = ImageComposeScene(480, 400, coroutineContext = Dispatchers.Unconfined) {
            Row {
                for (index in 0..1) Canvas(Modifier.size(240.dp, 400.dp).graphicsLayer {
                    renderEffect = effects[index].create(240f, 400f, 0f, 1f, 18f,
                        blurDp = 0f, lensHeightDp = 24f, lensAmountDp = if (index == 0) 0f else 24f)
                }) {
                    drawRect(Color(0xFF20202A))
                    for (x in 0..240 step 20) drawRect(Color.White, Offset(x.toFloat(), 0f), Size(2f, 400f))
                    for (y in 0..400 step 20) drawRect(Color.White, Offset(0f, y.toFloat()), Size(240f, 2f))
                }
            }
        }
        try {
            repeat(5) { scene.render(it * 16_666_667L).close() }
            val bytes = scene.render(100_000_000L).use {
                it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes }
            }
            File("build/screens/glass-lens-grid.png").apply { parentFile.mkdirs(); writeBytes(bytes) }
            val image = ImageIO.read(bytes.inputStream())
            // This region lies beyond the previous 24px rim. A simple transparent
            // or blurred copy would leave the grid identical in both panels.
            val changed = (40 until 100).sumOf { x -> (70 until 330).count { y ->
                kotlin.math.abs((image.getRGB(x, y) and 255) - (image.getRGB(x + 240, y) and 255)) > 35
            } }
            assertTrue(changed > 500, "The lens must visibly bend the background beyond a narrow edge: $changed")
        } finally { scene.close(); effects.forEach { it.close() } }
    }
}
