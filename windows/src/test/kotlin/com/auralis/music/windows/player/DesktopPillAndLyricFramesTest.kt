package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.renderers.SpicyLyricsLine
import com.auralis.music.windows.DesktopNotificationPill
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class DesktopPillAndLyricFramesTest {
    @Test fun desktopNotificationUsesACompactRoundedPill() {
        val scene = ImageComposeScene(800, 180, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.fillMaxSize().background(Color(0xFF201C22)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                DesktopNotificationPill("Removed Tame Impala from shortcuts")
            } }
        }
        try {
            repeat(8) { scene.render(it * 16_666_667L).close() }
            scene.render(200_000_000L).use { image ->
                val output = File("build/screens/desktop-notification-pill.png"); output.parentFile.mkdirs()
                output.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes })
                org.jetbrains.skia.Bitmap.makeFromImage(image).use { pixels ->
                    assertEquals(pixels.getColor(0, 90), pixels.getColor(100, 90), "Pill must not stretch across the page")
                    assertNotEquals(pixels.getColor(0, 90), pixels.getColor(400, 90))
                }
            }
        } finally { scene.close() }
    }

    @Test fun fastOverlappingWordsKeepDrawingWhileGlowPreparesAndPausedFramesLeaveNoTrail() = runBlocking(Dispatchers.Swing) {
        val position = mutableLongStateOf(500L)
        val lines = List(4) { line ->
            val words = List(16) { i -> LyricWord("voice$i ", line * 650L + i * 110L, if (i % 4 == 0) 1200L else 160L) }
            LyricLine(line * 650L, words.joinToString("") { it.word }, words = words, isBackground = line % 2 == 1)
        }
        val scene = ImageComposeScene(680, 440, coroutineContext = Dispatchers.Swing) {
            MaterialTheme { Column(Modifier.fillMaxSize().background(Color(0xFF202334)).padding(20.dp)) {
                lines.forEach { line -> SpicyLyricsLine(line, line.words, position,
                    TextStyle(fontSize = (if (line.isBackground) 23 else 32).sp, fontWeight = FontWeight.Bold), true) }
            } }
        }
        try {
            suspend fun frame() = scene.render(System.nanoTime()).also { delay(8) }
            repeat(25) { frame().close() }
            val samples = mutableSetOf<Int>()
            val renderTimes = mutableListOf<Double>()
            for (time in 800L..2200L step 35) {
                position.longValue = time
                val begin = System.nanoTime()
                val image = scene.render(System.nanoTime())
                renderTimes += (System.nanoTime() - begin) / 1_000_000.0
                image.use { samples += it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes.contentHashCode() } }
                delay(8)
            }
            assertTrue(samples.size > 35, "Fast overlapping syllables must keep producing new highlight frames")
            fun capture() = scene.render(System.nanoTime()).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
            // Springs intentionally settle after playback stops. Wait for that
            // bounded settling period before checking for retained-frame ink.
            repeat(120) { frame().close() }
            val paused = capture()
            repeat(12) { frame().close() }
            val after = capture()
            val beforePixels = javax.imageio.ImageIO.read(paused.inputStream())
            val afterPixels = javax.imageio.ImageIO.read(after.inputStream())
            var error = 0L
            for (y in 0 until beforePixels.height) for (x in 0 until beforePixels.width) {
                val a = beforePixels.getRGB(x, y); val b = afterPixels.getRGB(x, y)
                for (shift in listOf(0, 8, 16)) error += kotlin.math.abs((a shr shift and 255) - (b shr shift and 255))
            }
            val meanError = error.toDouble() / (beforePixels.width * beforePixels.height * 3)
            assertTrue(meanError < .25, "Paused frame difference must remain below subpixel spring rounding, not duplicate ink: $meanError")
            val sorted = renderTimes.sorted()
            println("Dense overlapping lyric render (software test backend): median=${sorted[sorted.size / 2]}ms p95=${sorted[(sorted.size * .95).toInt()]}ms")
        } finally { scene.close() }
    }
}
