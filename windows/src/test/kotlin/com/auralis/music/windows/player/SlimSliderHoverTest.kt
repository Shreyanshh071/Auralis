package com.auralis.music.windows.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.AuralisSlider
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import javax.imageio.ImageIO
import kotlin.test.*

class SlimSliderHoverTest {
    @Test fun compactTrackAcceptsSmallSeeksAndKeepsDragTargetAcrossPlaybackReports() {
        var position by mutableStateOf(.2f)
        var preview by mutableStateOf<Float?>(null)
        val seeks = mutableListOf<Float>()
        val scene = ImageComposeScene(300, 70, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                AuralisSlider(preview ?: position, { preview = it }, {
                    preview?.let { position = it; seeks += it }
                    preview = null
                }, false, 0, 214_000, sliderStyle = "Slim", showTimestamps = false,
                    slimThicknessRest = 3.dp, slimThicknessHover = 5.dp)
            }
        }
        try {
            var frame = 0L
            fun render() { scene.render(frame++ * 16_666_667L).close() }
            fun pointer(type: PointerEventType, x: Float) {
                scene.sendPointerEvent(type, Offset(x, 35f), type = PointerType.Mouse)
                render()
            }
            render()
            pointer(PointerEventType.Press, 150f)
            pointer(PointerEventType.Release, 150f)
            assertEquals(.5f, seeks.last(), .001f)
            pointer(PointerEventType.Press, 153f)
            pointer(PointerEventType.Release, 153f)
            assertEquals(.51f, seeks.last(), .001f, "A three-pixel click advances about two seconds")
            pointer(PointerEventType.Press, 156f)
            pointer(PointerEventType.Move, 249f)
            position = .1f
            render()
            pointer(PointerEventType.Release, 249f)
            assertEquals(.83f, seeks.last(), .001f, "An incoming playback report cannot replace the dragged target")
            assertEquals(3, seeks.size)
        } finally { scene.close() }
    }

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
