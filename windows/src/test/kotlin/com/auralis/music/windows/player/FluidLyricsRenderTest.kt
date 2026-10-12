package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.lyrics.renderers.FluidLyricLine
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class FluidLyricsRenderTest {
    @Test fun timedWrappedLineRendersAcrossHeldWordsAndSeeks() {
        val position = mutableLongStateOf(0L)
        val tokens = "Every breath you take every move you make every bond you break every step you take".split(" ")
        val words = tokens.mapIndexed { i, word -> LyricWord("$word ", i * 1000L, 1000L) }
        val line = LyricLine(0L, tokens.joinToString(" "), words = words)
        val scene = ImageComposeScene(900, 250, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
                FluidLyricLine(line, words, position, TextStyle(fontSize = 42.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold), true)
            } }
        }
        try {
            var frame = 0L
            fun render() = scene.render(frame++ * 16_666_667L)
            repeat(15) { render() }
            val samples = ArrayList<Double>()
            repeat(180) { i ->
                position.longValue = i * 60L
                val start = System.nanoTime()
                render()
                samples += (System.nanoTime() - start) / 1_000_000.0
            }
            val sorted = samples.sorted()
            println("FLUID_FRAME_MS median=${sorted[90]} p95=${sorted[171]} total=${samples.sum()}")
            val output = File("build/screens/fluid-performance").apply { mkdirs() }
            position.longValue = 3400L
            repeat(3) { render() }
            File(output, "wrapped.png").writeBytes(render().encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes })
            position.longValue = 0L
            repeat(3) { render() }
            val before = render().encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
            position.longValue = 16_000L
            repeat(3) { render() }
            val after = render().encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
            assertFalse(before.contentEquals(after), "Seeking must update the word sweep")
            fun brightness(bytes: ByteArray): Long {
                val image = javax.imageio.ImageIO.read(bytes.inputStream())
                var sum = 0L
                for (x in 0 until 180) for (y in 0 until 55) {
                    val pixel = image.getRGB(x, y)
                    sum += (pixel ushr 16) and 255
                }
                return sum
            }
            assertTrue(brightness(after) > brightness(before) * 1.7,
                "Completed words must remain bright after later words render")
            File(output, "before.png").writeBytes(before)
            File(output, "after.png").writeBytes(after)
        } finally { scene.close() }
    }
}
