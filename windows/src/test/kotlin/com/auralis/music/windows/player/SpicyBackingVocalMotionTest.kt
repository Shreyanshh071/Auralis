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
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.renderers.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class SpicyBackingVocalMotionTest {
    @Test fun nestedBackingMarkersNeverEraseBoundaryWordsDuringAnimation() {
        val clean = listOf(LyricWord("Be ", 1000, 422, true), LyricWord("my, ", 1422, 953, true),
            LyricWord("be ", 2375, 299, true), LyricWord("my ", 2674, 486, true), LyricWord("baby", 3160, 724, true))
        val wrappedLeaves = clean.mapIndexed { index, word -> word.copy(syllables = listOf(word.copy(
            word = (if (index == 0) "(" else "") + word.word + (if (index == clean.lastIndex) ")" else "")))) }
        val text = "Be my, be my baby"
        for (time in listOf(1100L, 1800L, 2800L, 3500L)) {
            val expected = capture(text, clean, time)
            val actual = capture(text, wrappedLeaves, time)
            assertContentEquals(expected, actual, "Nested boundary markers must not erase either end at $time")
        }
    }

    @Test fun backingWrappersMapWithoutChangingSyllableClocksOrLiteralParentheses() {
        val pieces = listOf(LyricWord("(vel", 1000, 784, true), LyricWord("ora)", 1900, 2167, true))
        val source = listOf(LyricWord("(velora)", 1000, 3067, true, pieces))
        val normalized = SpicyBackingText.displayWords("velora", source, true)!!
        assertEquals("velora", normalized.single().word)
        assertEquals(listOf("vel", "ora"), normalized.single().syllables!!.map { it.word })
        assertEquals(pieces.map { it.time to it.duration }, normalized.single().syllables!!.map { it.time to it.duration })
        assertEquals(source.single().time to source.single().duration, normalized.single().time to normalized.single().duration)
        assertEquals("(velora)", source.single().word, "Source payload must remain immutable")
        assertEquals("say (yes) softly", SpicyBackingText.displayText("(say (yes) softly)", true))
        assertSame(source, SpicyBackingText.displayWords("velora", source, false), "Lead text remains literal")
        assertSame(source, SpicyBackingText.displayWords("a different phrase", source, true), "Never guess which text to remove")
    }

    @Test fun multiWordAndMarkupOnlyBoundaryTokensKeepTheirOriginalTimes() {
        val words = listOf(LyricWord("( ", 900, 50, true), LyricWord("stay ", 1000, 800, true),
            LyricWord("here", 2100, 1400, true), LyricWord(" )", 3600, 20, true))
        val normalized = SpicyBackingText.displayWords("stay here", words, true)!!
        assertEquals(words.map { it.time to it.duration }, normalized.map { it.time to it.duration })
        assertEquals("stay here", normalized.joinToString("") { it.word }.trim())
        assertTrue(normalized.all { it.isBackground })
    }

    private fun capture(text: String, words: List<LyricWord>, time: Long, active: Boolean = true): ByteArray {
        val position = mutableLongStateOf(time)
        val scene = ImageComposeScene(400, 135, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF202334)).padding(28.dp)) {
                // Keep the existing 70% size and italic writing, including on held notes.
                SpicyLyricsLine(LyricLine(1000, text, words = words, isBackground = true), words, position,
                    TextStyle(fontSize = (40f * .70f).sp, lineHeight = 38.sp,
                        fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold), active,
                    inactiveColor = Color.White.copy(alpha = .35f))
            } }
        }
        return try {
            repeat(8) { scene.render(it * 16_666_667L).close() }
            scene.render(9 * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { png -> png.bytes } }
        } finally { scene.close() }
    }

    @Test fun italicWholeWordAndHeldLetterSwellsRenderWithOrWithoutSourceWrappersAcrossLanguages() {
        for ((text, duration) in listOf("Oh" to 832L, "Ah" to 1800L, "ओ" to 1600L, "λά" to 2200L)) {
            val wrapped = listOf(LyricWord("($text)", 1000, duration, true))
            val clean = listOf(LyricWord(text, 1000, duration, true))
            val frames = listOf(.15, .65, .95).map { portion ->
                val time = 1000 + (duration * portion).toLong()
                val expected = capture(text, clean, time)
                assertContentEquals(expected, capture(text, wrapped, time), "Fullscreen's stripped text must still animate: $text at $portion")
                assertContentEquals(expected, capture("($text)", wrapped, time), "Sidebar's source text must use the same motion: $text at $portion")
                expected
            }
            assertFalse(frames.first().contentEquals(frames[1]), "A held italic vocal must visibly swell/highlight")
        }
    }

    @Test fun mixedSyllableMotionSurvivesWrappersAndShortItalicVocalActuallyPopsAndGlows() {
        val pieces = listOf(LyricWord("(vel", 1000, 784, true), LyricWord("ora)", 1784, 2167, true))
        val wrapped = listOf(LyricWord("(velora)", 1000, 2951, true, pieces))
        val clean = listOf(LyricWord("velora", 1000, 2951, true,
            listOf(pieces[0].copy(word = "vel"), pieces[1].copy(word = "ora"))))
        for (time in listOf(1300L, 2400L, 3600L))
            assertContentEquals(capture("velora", clean, time), capture("velora", wrapped, time))

        val shortVocal = listOf(LyricWord("(Oh)", 1000, 832, true))
        val before = capture("Oh", shortVocal, 500, active = false)
        val peak = capture("Oh", shortVocal, 1540)
        fun bounds(png: ByteArray): Pair<Int, Int> {
            val image = ImageIO.read(png.inputStream())
            var left = image.width; var right = -1; var top = image.height; var bottom = -1
            for (y in 0 until image.height) for (x in 0 until image.width) if ((image.getRGB(x, y) shr 16 and 255) > 80) {
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
            return (right - left + 1) to (bottom - top + 1)
        }
        val initialBounds = bounds(before); val peakBounds = bounds(peak)
        assertTrue(peakBounds.first > initialBounds.first || peakBounds.second > initialBounds.second,
            "The sung italic word must visibly pop, rather than merely changing its text colour: $initialBounds to $peakBounds")
        val image = ImageIO.read(peak.inputStream())
        val brightest = (0 until image.height).maxOf { y -> (0 until image.width).maxOf { x -> image.getRGB(x, y) shr 16 and 255 } }
        assertTrue(brightest > 240, "Backing vocal must reach the active glow/highlight")
        val folder = File("build/screens/spicy-backing-motion").apply { mkdirs() }
        File(folder, "italic-before.png").writeBytes(before)
        File(folder, "italic-swell.png").writeBytes(peak)
    }
}
