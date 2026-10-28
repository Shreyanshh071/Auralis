package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.ui.lyrics.renderers.SpicyLyricsLine
import com.auralis.music.ui.lyrics.renderers.SpicyLyricsMotion
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class SpicyLineSyncRenderTest {
    @Test fun upcomingLinesBecomeBrightAndAnimateAcrossDifferentTextsAndDurations() {
        val examples = listOf(
            "Gorakhpur, Gaya, Chhapra, Mau में" to 4000L,
            "The night carries us home" to 1600L,
            "यह रोशनी केवल अक्षरों में" to 7000L)
        for ((text, duration) in examples) for (width in listOf(320, 1000)) {
            val active = mutableStateOf(false)
            val position = mutableLongStateOf(500L)
            val line = LyricLine(1000, text)
            val scene = ImageComposeScene(width, 330, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF0641B8)).padding(24.dp)) {
                    SpicyLyricsLine(line, null, position,
                        TextStyle(fontSize = 40.sp, lineHeight = 54.sp, fontWeight = FontWeight.Bold),
                        active.value, nextLineTime = 1000 + duration,
                        inactiveColor = Color.White.copy(alpha = SpicyLyricsMotion.UPCOMING_LINE_ALPHA))
                } }
            }
            try {
                var frame = 0L
                fun capture(time: Long): java.awt.image.BufferedImage {
                    position.longValue = time
                    repeat(20) { scene.render(frame++ * 16_666_667L).close() }
                    return scene.render(frame++ * 16_666_667L).use {
                        it.encodeToData(EncodedImageFormat.PNG)!!.use { png ->
                            javax.imageio.ImageIO.read(png.bytes.inputStream())
                        }
                    }
                }
                fun brightest(image: java.awt.image.BufferedImage) =
                    (0 until image.height).maxOf { y -> (0 until image.width).maxOf { x ->
                        (image.getRGB(x, y) shr 16) and 255
                    } }
                assertTrue(brightest(capture(500)) < 90, "Upcoming line should start dim")
                active.value = true
                val early = capture(1000 + duration / 40)
                val peak = capture(1000 + duration / 2)
                assertTrue(brightest(peak) > 245, "Active line inherited inactive opacity: $text at $width")
                var brightened = 0
                for (y in 0 until peak.height) for (x in 0 until peak.width) {
                    val before = (early.getRGB(x, y) shr 16) and 255
                    val after = (peak.getRGB(x, y) shr 16) and 255
                    if (before > 100 && after > before + 20) brightened++
                }
                assertTrue(brightened > 100, "Line glow must progress with its own interval: $text")
                active.value = false
                assertTrue(brightest(capture(1000 + duration)) < 90, "Inactive drawing should return to the caller's colour")
            } finally { scene.close() }
        }
    }

    @Test fun lrcWithoutEndTimesHasVisibleWholeLineGlowOnBlueBackground() {
        for (width in listOf(320, 1000)) {
            val position = mutableLongStateOf(1100L)
            val line = LyricLine(1000, "गइनी Ghazipur, Gonda, Lucknow में")
            val scene = ImageComposeScene(width, 330, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF0641B8)).padding(24.dp)) {
                    SpicyLyricsLine(line, null, position,
                        TextStyle(fontSize = 40.sp, lineHeight = 54.sp, fontWeight = FontWeight.Bold),
                        true, nextLineTime = 5000L,
                        inactiveColor = Color.White.copy(alpha = SpicyLyricsMotion.UPCOMING_LINE_ALPHA))
                } }
            }
            try {
                var frame = 0L
                fun capture(time: Long): ByteArray {
                    position.longValue = time
                    repeat(20) { scene.render(frame++ * 16_666_667L).close() }
                    return scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
                }
                val early = capture(1100)
                val peak = capture(3000)
                val earlyPixels = javax.imageio.ImageIO.read(early.inputStream())
                val peakPixels = javax.imageio.ImageIO.read(peak.inputStream())
                var inkPixels = 0
                var visiblyBrighter = 0
                var changedBackground = 0
                for (y in 0 until 330) for (x in 0 until width) {
                    val redBefore = (earlyPixels.getRGB(x, y) shr 16) and 255
                    val redPeak = (peakPixels.getRGB(x, y) shr 16) and 255
                    if (redBefore > 100) {
                        inkPixels++
                        if (redPeak > redBefore + 25) visiblyBrighter++
                    }
                    if (redBefore == 6 && redPeak != 6) changedBackground++
                }
                assertTrue(inkPixels > 100)
                assertTrue(visiblyBrighter > inkPixels / 2, "Glow must visibly brighten most of the active line at width $width")
                assertEquals(0, changedBackground, "Glow must stay out of the background and letter counters")
                assertContentEquals(early, capture(1100), "Seeking must reproduce the glow")
                val folder = File("build/screens/spicy-line-sync").apply { mkdirs() }
                File(folder, "blue-lrc-early-$width.png").writeBytes(early)
                File(folder, "blue-lrc-glow-$width.png").writeBytes(peak)
            } finally { scene.close() }
        }
    }

    @Test fun innerGlowNeverPaintsOutsideLetterInkOrInsideLetterHoles() {
        for (text in listOf("Glow only in these letters", "यह रोशनी केवल अक्षरों में")) {
            for (width in listOf(260, 800)) {
                val position = mutableLongStateOf(6000L)
                val line = LyricLine(1000, text, endTime = 5000)
                val scene = ImageComposeScene(width, 320, coroutineContext = Dispatchers.Unconfined) {
                    MaterialTheme { Box(Modifier.fillMaxSize().background(Color.Black).padding(25.dp)) {
                        SpicyLyricsLine(line, null, position,
                            TextStyle(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold), true)
                    } }
                }
                try {
                    var frame = 0L
                    fun capture(time: Long): org.jetbrains.skia.Bitmap {
                        position.longValue = time
                        repeat(20) { scene.render(frame++ * 16_666_667L).close() }
                        return scene.render(frame++ * 16_666_667L).use { org.jetbrains.skia.Bitmap.makeFromImage(it) }
                    }
                    capture(6000).use { ink -> capture(3000).use { glowing ->
                        var brighterInk = 0
                        var outsideInk = 0
                        for (y in 0 until ink.height) for (x in 0 until ink.width) {
                            val baseline = ink.getColor(x, y) and 255
                            val animated = glowing.getColor(x, y) and 255
                            if (baseline == 0 && animated > 2) outsideInk++
                            if (baseline > 100 && animated > baseline + 8) brighterInk++
                        }
                        assertEquals(0, outsideInk, "Glow escaped the glyph mask at width $width for $text")
                        assertTrue(brighterInk > 10, "The glow must brighten the letters themselves")
                    } }
                } finally { scene.close() }
            }
        }
    }

    @Test fun lineIntervalsKeepUnknownEndsAndVocalRestsHonest() {
        assertEquals(0f, SpicyLyricsMotion.lineProgress(1000, 5000, 900))
        assertEquals(.5f, SpicyLyricsMotion.lineProgress(1000, 5000, 3000))
        assertEquals(1f, SpicyLyricsMotion.lineProgress(1000, 5000, 6000))
        assertEquals(1f, SpicyLyricsMotion.lineProgress(1000, null, 1000))
        assertEquals(0f, SpicyLyricsMotion.lineGlow(0f))
        assertEquals(1f, SpicyLyricsMotion.lineGlow(.5f))
        assertEquals(0f, SpicyLyricsMotion.lineGlow(1f))
    }

    @Test fun wrappedLineAnimatesWithoutWordTimingsAndSeekingRestoresItsDrawing() {
        for (width in listOf(260, 800)) {
            val position = mutableLongStateOf(1100L)
            val line = LyricLine(1000, "Stay with me through the night", endTime = 5000)
            val scene = ImageComposeScene(width, 310, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme { Box(Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
                    SpicyLyricsLine(line, null, position,
                        TextStyle(fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace), true)
                } }
            }
            try {
                var frame = 0L
                fun capture(time: Long): ByteArray {
                    position.longValue = time
                    repeat(20) { scene.render(frame++ * 16_666_667L).close() }
                    return scene.render(frame++ * 16_666_667L).use {
                        it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes }
                    }
                }
                val early = capture(1100)
                val late = capture(4700)
                assertFalse(early.contentEquals(late), "Line-only lyrics must animate at width $width")
                assertContentEquals(early, capture(1100), "A backward seek must restore the same line highlight")
                assertNull(line.words, "Line animation must not fabricate word timestamps")
                val folder = File("build/screens/spicy-line-sync").apply { mkdirs() }
                File(folder, "early-$width.png").writeBytes(early)
                File(folder, "late-$width.png").writeBytes(late)
            } finally { scene.close() }
        }
    }
}
