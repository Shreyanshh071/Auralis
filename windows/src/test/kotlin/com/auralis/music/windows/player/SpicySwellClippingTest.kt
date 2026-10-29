package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.lyrics.lyricsBlurLayer
import com.auralis.music.ui.lyrics.renderers.SpicyLyricsLine
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*

class SpicySwellClippingTest {
    @Test fun activeHeldWordsKeepTheSameInkAsAnUnboundedRow() {
        var reproducedClipping = false
        for (backing in listOf(false, true)) for (time in listOf(1200L, 1500L, 1900L)) {
            val expected = render(time, backing, layer = 0)
            val fixed = render(time, backing, layer = 1)
            val clipped = render(time, backing, layer = 2)
            assertContentEquals(expected, fixed, "Swelling ink must survive the active-row layer at $time (backing=$backing)")
            if (!expected.contentEquals(clipped)) reproducedClipping = true
        }
        assertTrue(reproducedClipping, "The regression scene must reproduce clipping with the old offscreen layer")
    }

    private fun render(time: Long, backing: Boolean, layer: Int): IntArray {
        val word = LyricWord("Oh", 1000, 1800, backing)
        val line = LyricLine(1000, "Oh", words = listOf(word), isBackground = backing)
        val position = mutableLongStateOf(time)
        val scene = ImageComposeScene(320, 180, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(40.dp)) {
                    val modifier = if (layer == 0) Modifier else Modifier.lyricsBlurLayer(true, layer == 1) { 0f }
                    Column(modifier) {
                        SpicyLyricsLine(line, line.words, position,
                            TextStyle(fontSize = 70.sp, lineHeight = 70.sp, fontWeight = FontWeight.Bold,
                                fontStyle = if (backing) FontStyle.Italic else FontStyle.Normal), true)
                    }
                }
            }
        }
        return try {
            repeat(10) { scene.render(it * 16_666_667L).close() }
            scene.render(11 * 16_666_667L).use { image ->
                val folder = java.io.File("build/screens/spicy-swell-clipping").apply { mkdirs() }
                java.io.File(folder, "$time-$backing-$layer.png").writeBytes(
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes })
                Bitmap.makeFromImage(image).use { bitmap ->
                    IntArray(bitmap.width * bitmap.height) { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
                }
            }
        } finally { scene.close() }
    }
}
