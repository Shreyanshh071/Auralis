package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.CompositionLocalProvider
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
    @Test fun wrappedLyricsStillAnimateHeldLetters() {
        val text = "green watering can"
        val words = listOf(LyricWord("green ", 1000, 2200), LyricWord("watering ", 3200, 1200),
            LyricWord("can", 4400, 600))
        fun capture(width: Int, motion: Boolean): IntArray {
            val scene = ImageComposeScene(width, 220, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    CompositionLocalProvider(com.auralis.music.ui.lyrics.LocalLyricsGlyphMotion provides motion) {
                        Box(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
                            SpicyLyricsLine(LyricLine(1000, text, words = words), words, mutableLongStateOf(1800L),
                                TextStyle(fontSize = 42.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold), true)
                        }
                    }
                }
            }
            return try {
                repeat(8) { scene.render(it * 16_666_667L).close() }
                scene.render(9 * 16_666_667L).use { image ->
                    Bitmap.makeFromImage(image).use { bitmap ->
                        IntArray(bitmap.width * bitmap.height) { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
                    }
                }
            } finally { scene.close() }
        }
        val still = capture(200, false)
        val moving = capture(200, true)
        assertTrue(still.indices.any { it / 200 > 58 && (still[it] and 255) > 32 }, "The fixture must wrap")
        assertTrue(still.indices.any { it / 200 < 58 && (still[it] and 255) < 12 && (moving[it] and 255) > 100 },
            "The active letters should move beyond their resting ink")
    }

    @Test fun fullySungWordsKeepOverhangsAndDescenders() {
        for (text in listOf("right through", "right through f", "Know", "night by", "Dracula")) {
            fun capture(active: Boolean): IntArray {
                val words = text.split(" ").mapIndexed { index, word ->
                    if (word == "Dracula") LyricWord(word, 1000L, 1800L, true, listOf(
                        LyricWord("Dra", 1000L, 500L), LyricWord("cu", 1500L, 300L), LyricWord("la", 1800L, 1000L)
                    )) else LyricWord(word + if (index < text.count { it == ' ' }) " " else "", 1000L + index * 600, 1800, true)
                }
                val line = LyricLine(1000, text, words = words, isBackground = true)
                val scene = ImageComposeScene(640, 180, coroutineContext = Dispatchers.Unconfined) {
                    MaterialTheme {
                        CompositionLocalProvider(com.auralis.music.ui.lyrics.LocalLyricsGlyphMotion provides false) {
                            Box(Modifier.fillMaxSize().background(Color.Black).padding(40.dp)) {
                                SpicyLyricsLine(line, words, mutableLongStateOf(6000L),
                                    TextStyle(fontSize = 48.sp, lineHeight = if (text == "night by") 44.sp else 56.sp,
                                        fontStyle = if (text == "night by") FontStyle.Normal else FontStyle.Italic,
                                        fontWeight = FontWeight.Bold), active)
                            }
                        }
                    }
                }
                return try {
                    repeat(10) { scene.render(it * 16_666_667L).close() }
                    scene.render(11 * 16_666_667L).use { image ->
                        val folder = java.io.File("build/screens/spicy-italic-overhang").apply { mkdirs() }
                        java.io.File(folder, "${text.replace(' ', '-')}-${active}.png").writeBytes(
                            image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes })
                        Bitmap.makeFromImage(image).use { bitmap ->
                            IntArray(bitmap.width * bitmap.height) { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
                        }
                    }
                } finally { scene.close() }
            }
            val reference = capture(false)
            val animated = capture(true)
            val rightEdge = reference.indices.filter { (reference[it] and 255) > 128 }.maxOf { it % 640 }
            val edgeInk = reference.indices.filter { it % 640 >= rightEdge - 2 && (reference[it] and 255) > 128 }
            assertTrue(edgeInk.isNotEmpty())
            assertTrue(edgeInk.all { (animated[it] and 255) > 128 }, "Final glyph was clipped: $text")
            val bottomEdge = reference.indices.filter { (reference[it] and 255) > 128 }.maxOf { it / 640 }
            val bottomInk = reference.indices.filter { it / 640 >= bottomEdge - 2 && (reference[it] and 255) > 128 }
            assertTrue(bottomInk.all { (animated[it] and 255) > 128 }, "Bottom of the glyph was clipped: $text")
        }
    }

    // Blur glow masks inline, so whether a glow is drawn never depends on a background thread.
    private val glowDispatcher = com.auralis.music.ui.lyrics.renderers.SpicyGlowPreparation.dispatcher
    @BeforeTest fun prepareGlowInline() { com.auralis.music.ui.lyrics.renderers.SpicyGlowPreparation.dispatcher = Dispatchers.Unconfined }
    @AfterTest fun restoreGlowDispatcher() { com.auralis.music.ui.lyrics.renderers.SpicyGlowPreparation.dispatcher = glowDispatcher }

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

    @Test fun backingGlyphsRetainTheirInkInsideExpandedBlurBounds() {
        var reproduced = false
        for (time in listOf(1200L, 1500L, 1900L)) {
            val expected = render(time, backing = true, layer = 0, text = "Know")
            val expanded = render(time, backing = true, layer = 3, text = "Know")
            val original = render(time, backing = true, layer = 2, text = "Know")
            assertContentEquals(expected, expanded, "Backing glyphs were clipped at $time")
            if (!expected.contentEquals(original)) reproduced = true
        }
        assertTrue(reproduced, "The original blur layer must reproduce the backing-row clipping")
    }

    private fun render(time: Long, backing: Boolean, layer: Int, text: String = "Oh"): IntArray {
        val word = LyricWord(text, 1000, 1800, backing)
        val line = LyricLine(1000, text, words = listOf(word), isBackground = backing)
        val position = mutableLongStateOf(time)
        val scene = ImageComposeScene(320, 180, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(40.dp)) {
                    val modifier = if (layer == 0) Modifier else Modifier.lyricsBlurLayer(true, layer == 1, overflow = if (layer == 3) 40.dp else 0.dp) { 0f }
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
                java.io.File(folder, "$text-$time-$backing-$layer.png").writeBytes(
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes })
                Bitmap.makeFromImage(image).use { bitmap ->
                    IntArray(bitmap.width * bitmap.height) { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
                }
            }
        } finally { scene.close() }
    }
}
