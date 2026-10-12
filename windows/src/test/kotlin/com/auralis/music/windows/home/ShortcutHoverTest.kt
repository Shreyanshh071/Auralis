package com.auralis.music.windows.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.SpeedDialType
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ShortcutHoverTest {
    @Test fun entireRowHighlightsAndAddedRowsCannotAddAgain() {
        val pinned = mutableStateOf(false)
        var adds = 0
        val scene = ImageComposeScene(420, 80, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
                Box(Modifier.fillMaxSize().background(Color(0xFF31252A))) {
                    ShortcutCandidateRow(SpeedDialItem("artist", "Gorillaz", SpeedDialType.ARTIST), pinned.value) { adds++ }
                }
                }
            }
        }
        try {
            var frame = 0L
            fun render(name: String): java.awt.image.BufferedImage {
                repeat(6) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                val dir = File("build/screens/shortcut-hover").apply { mkdirs() }
                File(dir, "$name.png").writeBytes(bytes)
                return ImageIO.read(bytes.inputStream())
            }
            val idle = render("idle")
            scene.sendPointerEvent(PointerEventType.Move, Offset(330f, 35f), type = PointerType.Mouse)
            val hover = render("hover")
            assertTrue((hover.getRGB(300, 12) shr 16 and 255) > (idle.getRGB(300, 12) shr 16 and 255) + 15)
            assertEquals(idle.getRGB(0, 0), hover.getRGB(0, 0), "Highlight remains rounded")
            fun click() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(330f, 35f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(330f, 35f), type = PointerType.Mouse)
            }
            click(); assertEquals(1, adds)
            pinned.value = true; render("added"); click(); assertEquals(1, adds)
        } finally { scene.close() }
    }
}
