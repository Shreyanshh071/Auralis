package com.auralis.music.windows

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.GlassActionButton
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class GlassButtonHoverTest {
    @Test fun hoverCoversPaddingAndEdgesAndDisabledButtonCannotClick() {
        var enabled by mutableStateOf(true)
        var clicks = 0
        val scene = ImageComposeScene(440, 100, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassActionButton({ clicks++ }, Modifier.width(400.dp).height(44.dp), enabled) { Text("Sign in to Spotify") }
                }
            }
        }
        try {
            var frame = 0L
            fun render(name: String): java.awt.image.BufferedImage {
                repeat(14) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                File("build/screens/import-hover").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
                return ImageIO.read(bytes.inputStream())
            }
            val before = render("normal")
            scene.sendPointerEvent(PointerEventType.Enter, Offset(220f, 50f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Move, Offset(220f, 50f), type = PointerType.Mouse)
            val hover = render("hover")
            for (x in listOf(34, 90, 220, 350, 406)) {
                val oldAlpha = before.getRGB(x, 38).ushr(24)
                val newAlpha = hover.getRGB(x, 38).ushr(24)
                assertTrue(newAlpha > oldAlpha + 10, "Hover must paint the full rounded button at x=$x")
            }
            fun click() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(220f, 50f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(220f, 50f), type = PointerType.Mouse)
            }
            click(); assertEquals(1, clicks)
            enabled = false
            render("disabled")
            click(); assertEquals(1, clicks)
        } finally { scene.close() }
    }
}
