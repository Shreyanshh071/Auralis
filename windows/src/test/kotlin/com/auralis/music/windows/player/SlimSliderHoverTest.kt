package com.auralis.music.windows.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import com.auralis.music.ui.components.AuralisSlider
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import javax.imageio.ImageIO
import kotlin.test.*

class SlimSliderHoverTest {
    @Test fun slimTrackThickensSmoothlyOnHoverAndShrinksOnExitWithImmediateSeeking() {
        var value = 0f
        var finishes = 0
        val scene = ImageComposeScene(760, 70, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { AuralisSlider(.5f, { value = it }, { finishes++ }, false, 1000, 10000,
                sliderStyle = "Slim", showTimestamps = false) }
        }
        try {
            var frame = 0L
            fun thickness(): Int {
                val image = scene.render(frame++ * 16_666_667L).use {
                    it.encodeToData(EncodedImageFormat.PNG)!!.use { ImageIO.read(it.bytes.inputStream()) }
                }
                return (0 until 44).count { (image.getRGB(100, it) ushr 24) > 200 }
            }
            repeat(15) { thickness() }
            val idle = thickness()
            scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 22f), type = PointerType.Mouse)
            assertEquals(idle, thickness(), "Hover must begin at the old thickness")
            repeat(5) { thickness() }
            val middle = thickness()
            repeat(15) { thickness() }
            val hovered = thickness()
            assertTrue(hovered >= idle + 2)
            assertTrue(middle > idle && middle < hovered, "Thickness passes through an intermediate value")
            scene.sendPointerEvent(PointerEventType.Press, Offset(380f, 22f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(380f, 22f), type = PointerType.Mouse)
            assertEquals(.5f, value, .02f)
            assertEquals(1, finishes)
            scene.sendPointerEvent(PointerEventType.Exit, Offset(-1f, -1f), type = PointerType.Mouse)
            assertEquals(hovered, thickness())
            repeat(20) { thickness() }
            assertEquals(idle, thickness())
        } finally { scene.close() }
    }
}
