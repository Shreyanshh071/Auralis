package com.auralis.music.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class PopupGlassRenderTest {
    @Test fun popupBlursCorrectPageRegionAndTracksChanges() {
        val changed = mutableStateOf(false)
        val opened = mutableStateOf(true)
        lateinit var source: PopupGlassSource
        val scene = ImageComposeScene(700, 500, coroutineContext = Dispatchers.Unconfined) {
            source = rememberPopupGlassSource()
            CompositionLocalProvider(LocalPopupGlassSource provides source) {
                Canvas(Modifier.fillMaxSize().recordPopupGlass(source)) {
                    for (x in 0..700 step 8) drawRect(
                        if ((x / 8) % 2 == 0) Color.White else if (changed.value) Color.Green else Color.Blue,
                        Offset(x.toFloat(), 200f), Size(8f, 300f))
                    drawRect(Color.Red, size = Size(size.width, 200f))
                }
                if (opened.value) Popup(popupPositionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                        layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(150, 240)
                }) {
                    LiquidPopupSurface(Modifier.size(380.dp, 200.dp)) {
                        // Foreground detail must remain sharp through the backdrop filter.
                        Canvas(Modifier.fillMaxSize()) { drawRect(Color.White, Offset(180f, 90f), Size(4f, 20f)) }
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render(): java.awt.image.BufferedImage {
                repeat(6) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
                File("build/screens/popup-glass").mkdirs()
                File("build/screens/popup-glass/${if(changed.value) "green" else "blue"}.png").writeBytes(bytes)
                return ImageIO.read(bytes.inputStream())
            }
            val first = render()
            fun variation(y: Int) = (200 until 290).sumOf { x -> kotlin.math.abs((first.getRGB(x,y) shr 16 and 255) - (first.getRGB(x+1,y) shr 16 and 255)) }
            assertTrue(variation(290) < variation(220) / 3, "Popup should blur underlying stripes")
            val pixel = first.getRGB(250, 290)
            assertTrue((pixel and 255) > ((pixel shr 16) and 255) + 30, "Popup must sample its blue location, not red page origin")
            assertTrue((first.getRGB(331, 335) and 255) > 245, "Foreground remains sharp")
            changed.value = true
            val second = render()
            assertTrue((second.getRGB(250,290) shr 8 and 255) > (second.getRGB(250,290) and 255) + 30, "Popup should follow page updates")
            opened.value = false
            render()
            assertEquals(0, source.users.intValue, "Recording stops when popups close")
        } finally { scene.close() }
    }
}
