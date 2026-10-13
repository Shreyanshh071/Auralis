package com.auralis.music.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.SmoothAppIndication
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import javax.imageio.ImageIO
import kotlin.test.*

class SmoothHoverAnimationTest {
    @Test fun rowsIconsAndMenusFadeInAndOutWithoutDelayingClicks() {
        for (kind in listOf("row", "icon", "menu", "surface")) {
            var clicks = 0
            val scene = ImageComposeScene(200, 80, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    CompositionLocalProvider(LocalIndication provides SmoothAppIndication, LocalContentColor provides Color.White) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF242424))) {
                            when (kind) {
                                "row" -> Box(Modifier.size(180.dp, 60.dp).clickable { clicks++ })
                                "icon" -> com.auralis.music.ui.components.IconButton({ clicks++ }, Modifier.size(60.dp)) {}
                                "surface" -> com.auralis.music.ui.components.Surface(onClick = { clicks++ },
                                    modifier = Modifier.size(180.dp, 60.dp), color = Color(0xFF242424), contentColor = Color.White) {}
                                else -> com.auralis.music.ui.components.DropdownMenuItem({ Text("Action") }, { clicks++ }, Modifier.width(180.dp))
                            }
                        }
                    }
                }
            }
            try {
                var frame = 0L
                val sample = if (kind == "icon") Offset(30f, 12f) else Offset(150f, 12f)
                fun pixel(): Int {
                    val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                    return ImageIO.read(bytes.inputStream()).getRGB(sample.x.toInt(), sample.y.toInt()) shr 16 and 255
                }
                repeat(5) { pixel() }
                val idle = pixel()
                scene.sendPointerEvent(PointerEventType.Move, sample, type = PointerType.Mouse)
                val entered = pixel()
                repeat(4) { pixel() }
                val middle = pixel()
                repeat(15) { pixel() }
                val full = pixel()
                assertTrue(entered <= idle + 2, "$kind should begin near its idle color, not jump instantly")
                assertTrue(middle > idle && middle < full, "$kind should pass through intermediate highlight colors")
                assertTrue(full > idle + 10, "$kind should reach its hover color")
                scene.sendPointerEvent(PointerEventType.Move, Offset(195f, 75f), type = PointerType.Mouse)
                val exiting = pixel()
                assertTrue(exiting > idle + 5, "$kind should fade out rather than disappear instantly")
                repeat(16) { pixel() }
                assertEquals(idle, pixel(), "$kind must clear its hover state")
                scene.sendPointerEvent(PointerEventType.Move, sample, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Press, sample, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, sample, type = PointerType.Mouse)
                assertEquals(1, clicks, "$kind click should not wait for the hover animation")
            } finally { scene.close() }
        }
    }
}
