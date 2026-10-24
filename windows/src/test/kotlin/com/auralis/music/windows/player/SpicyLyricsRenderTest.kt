package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.ui.lyrics.renderers.SpicyLyricsLine
import com.auralis.music.ui.lyrics.renderers.SpicyLetterTime
import com.auralis.music.ui.lyrics.renderers.SpicyLyricsMotion
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class SpicyLyricsRenderTest {
    @Test fun completedWrappedRowCannotHighlightTopsOfUnsungRow() {
        val position = mutableLongStateOf(2000L)
        val words = listOf(LyricWord("MMMMMMMM ", 0, 500), LyricWord("MMMMMMMM", 3000, 500))
        val line = LyricLine(0, "MMMMMMMM MMMMMMMM", words = words, endTime = 3500)
        val scene = ImageComposeScene(250, 150, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                SpicyLyricsLine(line, words, position,
                    TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), true)
            } }
        }
        try {
            repeat(8) { scene.render(it * 16_666_667L).close() }
            scene.render(9 * 16_666_667L).use { image ->
                val pixels = org.jetbrains.skia.Bitmap.makeFromImage(image)
                try {
                    val brightest = (65 until 108).maxOf { y -> (18 until 210).maxOf { x -> pixels.getColor(x, y) and 255 } }
                    assertTrue(brightest in 50..110, "Unsung wrapped row received the preceding row's highlight: $brightest")
                    val folder = File("build/screens/spicy-animation").apply { mkdirs() }
                    File(folder, "wrapped-unsung-row.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes })
                } finally { pixels.close() }
            }
        } finally { scene.close() }
    }

    @Test fun activatingWordSyncedLineNeverFlashesFutureWordsWhite() {
        // Inspect every activation frame: settling the scene first hid the white layout fallback.
        for (width in listOf(320, 800)) {
            val active = mutableStateOf(false)
            val position = mutableLongStateOf(500L)
            val words = listOf(LyricWord("AAAA ", 1000, 1000), LyricWord("BBBB", 3000, 1000))
            val line = LyricLine(1000, "AAAA BBBB", words = words)
            val scene = ImageComposeScene(width, 100, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme { Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                    SpicyLyricsLine(line, words, position, TextStyle(fontSize = 40.sp, lineHeight = 56.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), active.value,
                        inactiveColor = Color.White.copy(alpha = SpicyLyricsMotion.UPCOMING_LINE_ALPHA))
                } }
            }
            try {
                var frame = 0L
                fun render() = scene.render(frame++ * 16_666_667L)
                repeat(4) { render().close() }
                repeat(2) { activation ->
                    position.longValue = 1250
                    active.value = true
                    repeat(8) { step ->
                        val brightestFuture = render().use { image ->
                            val pixels = org.jetbrains.skia.Bitmap.makeFromImage(image)
                            try { (16 until 80).maxOf { y -> (160 until 225).maxOf { x -> pixels.getColor(x, y) and 255 } } }
                            finally { pixels.close() }
                        }
                        assertTrue(brightestFuture in 20..160,
                            "Unsung word flashed on activation $activation frame $step at width $width: $brightestFuture")
                    }
                    active.value = false
                    position.longValue = 500
                    repeat(4) { render().close() }
                }
            } finally { scene.close() }
        }
    }

    @Test fun genuineTimingKeepsRestsAndMissingEndsHonest() {
        val early = LyricWord("stay ", 1000, 500)
        val next = LyricWord("with ", 3000, 500)
        assertEquals(1f, SpicyLyricsMotion.progress(early, 2200))
        assertEquals(0f, SpicyLyricsMotion.progress(next, 2200))
        val unknown = LyricWord("me", 4000)
        assertEquals(0f, SpicyLyricsMotion.progress(unknown, 3999))
        assertEquals(1f, SpicyLyricsMotion.progress(unknown, 4000))
        assertEquals(0f, SpicyLyricsMotion.pose(4000, null, 4200).glow)
        assertEquals(1f, SpicyLyricsMotion.pose(4000, null, 4200).scale)
    }

    @Test fun heldLettersSwellOneAfterAnotherAndSeekResetsThem() {
        val letters = (0 until 4).map { SpicyLetterTime(1000L + it * 750L, 1750L + it * 750L) }
        val active = SpicyLyricsMotion.letterPose(letters, 1, 2100)
        val sung = SpicyLyricsMotion.letterPose(letters, 0, 2100)
        val waiting = SpicyLyricsMotion.letterPose(letters, 3, 2100)
        assertTrue(active.scale > sung.scale, "Only the letter being sung swells fully")
        assertTrue(sung.scale > waiting.scale, "A sung neighbour eases back; later letters wait")
        assertTrue(active.glow > 0f && sung.glow > 0f)
        assertEquals(0f, waiting.glow)
        assertEquals(.95f, SpicyLyricsMotion.letterPose(letters, 0, 500).scale, 1e-4f)
        assertEquals(1f, SpicyLyricsMotion.letterPose(letters, 0, 4000).scale, 1e-4f)
        assertEquals(active, SpicyLyricsMotion.letterPose(letters, 1, 2100))
        assertEquals(-.2f, SpicyLyricsMotion.letterFill(letters[2], 2100))
        assertEquals(1f, SpicyLyricsMotion.letterFill(letters[0], 2100))
        assertEquals(LyricsAnimationMode.SPICY_LYRICS, LyricsAnimationMode.fromDisplayName("Spicy Lyrics"))
    }

    @Test fun nativeCanvasRendersHeldNotesSeeksAndComplexScripts() {
        val position = mutableLongStateOf(500L)
        val original = listOf(LyricWord("Stay ", 1000, 3000), LyricWord("with ", 4400, 500), LyricWord("me", 5200, 1500))
        val line = LyricLine(1000, "Stay with me", words = original, endTime = 6700)
        val style = TextStyle(fontSize = 54.sp, lineHeight = 66.sp, fontWeight = FontWeight.Bold)
        val scene = ImageComposeScene(850, 390, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Column(Modifier.fillMaxSize().background(Color(0xFF271A22)).padding(30.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                androidx.compose.material3.Text("If you just", style = style, color = Color.White.copy(alpha = .51f))
                SpicyLyricsLine(line, original, position, style, true)
                androidx.compose.material3.Text("I'll hold you close", style = style, color = Color.White.copy(alpha = .51f))
                val indic = LyricLine(1000, "तुम ही हो", words = listOf(LyricWord("तुम ", 1000, 1000), LyricWord("ही ", 2200, 1000), LyricWord("हो", 3400, 1800)))
                SpicyLyricsLine(indic, indic.words, position, style.copy(fontSize = 32.sp, lineHeight = 40.sp), true)
            } }
        }
        try {
            var frame = 0L
            fun render() = scene.render(frame++ * 16_666_667L)
            repeat(12) { render().close() }
            val folder = File("build/screens/spicy-animation").apply { mkdirs() }
            fun capture(time: Long): ByteArray {
                position.longValue = time
                repeat(3) { render().close() }
                val bytes = render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { image -> image.bytes } }
                File(folder, "frame-$time.png").writeBytes(bytes)
                return bytes
            }
            val before = capture(500)
            val during = capture(2500)
            capture(4100) // A real vocal rest, before the next word starts.
            capture(5500)
            val after = capture(7000)
            assertFalse(before.contentEquals(during))
            assertFalse(during.contentEquals(after))
            assertContentEquals(before, capture(500), "Seeking backward must restore the unsung drawing exactly")
            assertEquals(original, line.words, "Animation must not rewrite source timestamps")
            assertEquals(4400L, line.words!![1].time)
            println("SPICY_RENDER_FRAMES=${folder.absolutePath}")
        } finally { scene.close() }
    }

    @Test fun linesNotBeingSungDrawDimmed() {
        val position = mutableLongStateOf(0L)
        val words = listOf(LyricWord("Slow ", 5000, 400), LyricWord("down", 5400, 400))
        val dim = Color.White.copy(alpha = SpicyLyricsMotion.UPCOMING_LINE_ALPHA)
        val scene = ImageComposeScene(500, 100, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color.Black).padding(10.dp)) {
                SpicyLyricsLine(LyricLine(5000, "Slow down", words = words), words, position,
                    TextStyle(fontSize = 54.sp, lineHeight = 66.sp, fontWeight = FontWeight.Bold), false, inactiveColor = dim)
            } }
        }
        try {
            val brightest = scene.render(0L).use { image ->
                val pixels = org.jetbrains.skia.Bitmap.makeFromImage(image)
                (0 until pixels.width step 2).maxOf { x -> (0 until pixels.height step 2).maxOf { y -> pixels.getColor(x, y) and 0xFF } }
            }
            assertTrue(brightest in 20..(255 * .3f).toInt(), "An upcoming line must be dim, not white (was $brightest)")
        } finally { scene.close() }
    }

    @Test fun heldWordPlaysContinuouslyWithSprings() {
        val position = mutableLongStateOf(0L)
        val words = listOf(LyricWord("Come ", 0, 300), LyricWord("back ", 300, 300), LyricWord("to ", 600, 250),
            LyricWord("the ", 850, 250), LyricWord("moment", 1100, 2600))
        val line = LyricLine(0, "Come back to the moment", words = words, endTime = 3700)
        val style = TextStyle(fontSize = 54.sp, lineHeight = 66.sp, fontWeight = FontWeight.Bold)
        val scene = ImageComposeScene(900, 120, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF8D6F92)).padding(horizontal = 24.dp, vertical = 20.dp)) {
                SpicyLyricsLine(line, words, position, style, true)
            } }
        }
        try {
            val folder = File("build/screens/spicy-animation").apply { mkdirs() }
            var frames = 0
            for (frame in 0..240) {
                position.longValue = frame * 1000L / 60
                val image = scene.render(frame * 16_666_667L)
                if (frame % 9 == 0 && frame >= 54) {
                    File(folder, "held-%03d.png".format(frames++)).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
                image.close()
            }
            assertTrue(frames > 10)
        } finally { scene.close() }
    }

    @Test fun wrappedArabicAndLineOnlyLyricsRenderWithoutSyntheticWords() {
        val position = mutableLongStateOf(2500)
        val scene = ImageComposeScene(390, 330, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Column(Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
                val text = "مرحبا بك في عالم الموسيقى"
                val words = text.split(' ').mapIndexed { index, word -> LyricWord("$word ", 1000L + index * 600, 500) }
                SpicyLyricsLine(LyricLine(1000, text, words = words), words, position,
                    TextStyle(fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold), true)
                SpicyLyricsLine(LyricLine(1000, "A line with no word timestamps"), null, position,
                    TextStyle(fontSize = 30.sp, lineHeight = 40.sp), true)
            } }
        }
        try {
            repeat(10) { scene.render(it * 16_666_667L).close() }
            val dir = File("build/screens/spicy-animation").apply { mkdirs() }
            File(dir, "wrapped-rtl.png").writeBytes(scene.render(200_000_000L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } })
        } finally { scene.close() }
    }
}
