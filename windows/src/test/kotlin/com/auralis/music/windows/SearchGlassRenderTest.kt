package com.auralis.music.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.windows.search.SearchDropdownSurface
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class SearchGlassRenderTest {
    @Test fun recommendationsRefractLivePageWhileForegroundStaysSharp() {
        val blue = mutableStateOf(false)
        val scene = ImageComposeScene(500, 300, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val source = rememberPopupGlassSource()
                CompositionLocalProvider(LocalPopupGlassSource provides source) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().recordPopupGlass(source)) {
                            for (x in 0..500 step 6) drawRect(
                                if (x % 12 == 0) Color.White else if (blue.value) Color.Blue else Color.Red,
                                Offset(x.toFloat(), 0f), Size(6f, size.height))
                        }
                        SearchDropdownSurface(false, null, Modifier.offset(50.dp, 50.dp).size(400.dp, 200.dp)) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(Color.White, Offset(150f, 90f), Size(30f, 20f))
                            }
                        }
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render(): java.awt.image.BufferedImage {
                repeat(5) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use {
                    it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes }
                }
                File("build/screens/search-glass-${if (blue.value) "blue" else "red"}.png")
                    .apply { parentFile.mkdirs(); writeBytes(bytes) }
                return ImageIO.read(bytes.inputStream())
            }
            val red = render()
            blue.value = true
            val changed = render()
            fun variation(y: Int) = (100 until 180).sumOf { x ->
                kotlin.math.abs((red.getRGB(x, y) and 255) - (red.getRGB(x + 1, y) and 255))
            }
            assertTrue(variation(110) < variation(20) / 3, "Suggestions must blur the page beneath them")
            assertNotEquals(red.getRGB(140, 110), changed.getRGB(140, 110), "Glass must follow page changes")
            assertEquals(0xFFFFFF, red.getRGB(205, 145) and 0xFFFFFF, "Recommendation content must remain sharp")
            assertTrue((red.getRGB(199, 145) and 255) < 240, "Foreground edges must not blur into the glass")
        } finally { scene.close() }
    }
}
