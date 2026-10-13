package com.auralis.music.windows

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class ScrollRenderingTest {
    @Test fun wheelMovesAcrossFramesAndTextUsesOneDrawPass() {
        val scroll = ScrollState(0)
        var paints = 0
        val scene = ImageComposeScene(760, 520, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DesktopPageSurface(Modifier.fillMaxSize().background(Color(0xFF18252B))) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).drawWithContent { paints++; drawContent() }) {
                        repeat(70) { index ->
                            Text("$index · Crisp scrolling text / Albums & singles", Modifier.fillMaxWidth().padding(16.dp), color = Color.White)
                        }
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render(): ByteArray {
                paints = 0
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                assertTrue(paints <= 1, "Scrolling content must never be traversed twice in one frame")
                return bytes
            }
            repeat(4) { render() }
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 240f), scrollDelta = Offset(0f, 4f), type = PointerType.Mouse)
            val offsets = mutableListOf<Int>()
            repeat(45) { render(); offsets += scroll.value }
            assertTrue(scroll.value > 0, "Wheel input must reach the page")
            assertTrue(offsets.distinct().size > 3, "Wheel scrolling must animate over multiple frames")
            val forwardEnd = scroll.value
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 240f), scrollDelta = Offset(0f, -2f), type = PointerType.Mouse)
            repeat(45) { render() }
            assertTrue(scroll.value < forwardEnd, "Direction changes must stop and reverse the animation")
            val dir = File("build/screens/scroll-rendering").apply { mkdirs() }
            val settled = render()
            dir.resolve("settled.png").writeBytes(settled)
            assertContentEquals(settled, render(), "Text must not change or leave trails once the scroll settles")
            runBlocking { scroll.scrollTo(0) }
            repeat(4) { render() }
            dir.resolve("top.png").writeBytes(render())
        } finally { scene.close() }
    }

    @Test fun unusedBackdropWasAnExtraFullPageDraw() {
        var paints = 0
        val scene = ImageComposeScene(240, 120, coroutineContext = Dispatchers.Unconfined) {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Box(Modifier.fillMaxSize().drawWithContent { paints++; drawContent() }) { Text("Before: duplicate capture") }
            }
        }
        try {
            scene.render(0).close()
            assertEquals(2, paints, "The removed unused dock capture traversed the page twice")
        } finally { scene.close() }
    }
}
