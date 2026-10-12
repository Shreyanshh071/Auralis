package com.auralis.music.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class DockGlassRenderTest {
    @Test fun dockBlursLivePageWithoutBlurringItsIcons() {
        val blue = mutableStateOf(false)
        val scene = ImageComposeScene(600, 180, coroutineContext = Dispatchers.Unconfined) {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    for (x in 0..600 step 8) drawRect(
                        if ((x / 8) % 2 == 0) Color.White else if (blue.value) Color(0xFF004CFF) else Color(0xFFFF3A00),
                        Offset(x.toFloat(), 0f), Size(8f, size.height))
                }
                GlassDock(DesktopDestination.HOME, DockTabs, {}, Modifier.align(Alignment.Center), backdrop)
            }
        }
        try {
            var frame = 0L
            fun render(): ByteArray {
                repeat(4) { scene.render(frame++ * 16_666_667L).close() }
                return scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
            }
            val red = render()
            File("build/screens/dock-glass").mkdirs()
            File("build/screens/dock-glass/red.png").writeBytes(red)
            blue.value = true
            val changed = render()
            File("build/screens/dock-glass/blue.png").writeBytes(changed)
            val r = ImageIO.read(red.inputStream())
            val b = ImageIO.read(changed.inputStream())
            // Above the dock, stripes stay sharp; inside its upper surface they melt together.
            fun variation(y: Int): Int = (230 until 370).sumOf { x ->
                kotlin.math.abs((r.getRGB(x, y) and 255) - (r.getRGB(x + 1, y) and 255))
            }
            assertTrue(variation(75) < variation(30) / 3, "Live page must be blurred inside the dock")
            assertTrue((230 until 370).any { x -> r.getRGB(x, 75) != b.getRGB(x, 75) }, "Backdrop must update when page content changes")
            assertFalse(DesktopDestination.HISTORY in DockTabs)
            assertFalse(DesktopDestination.PROFILE in DockTabs)
            assertTrue(DesktopDestination.SETTINGS in DockTabs)
        } finally { scene.close() }
    }
}
