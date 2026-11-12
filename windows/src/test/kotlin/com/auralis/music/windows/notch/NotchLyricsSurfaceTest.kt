package com.auralis.music.windows.notch

import com.auralis.music.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Image
import org.jetbrains.skia.Bitmap
import java.io.File
import java.util.Base64
import kotlin.test.*

class NotchLyricsSurfaceTest {
    @Test fun syllableMotionChangesGlyphGeometryBeyondTheTimingHighlight() = runBlocking {
        withContext(Dispatchers.Swing) {
            val syllables = listOf(LyricWord("Shi", 1000, 600), LyricWord("ning", 1600, 2400))
            val line = LyricLine(1000, "Shining", words = listOf(LyricWord("Shining", 1000, 3000, syllables = syllables)), endTime = 4000)
            for (mode in listOf("MetroLyrics", "Spicy Lyrics")) {
                val moving = NotchLyricsSurface(360, glyphMotion = true)
                val trackingOnly = NotchLyricsSurface(360, glyphMotion = false)
                try {
                    val settings = AppearanceSettings(lyricsAnimation = mode)
                    var animated = ""
                    var still = ""
                    repeat(35) { i ->
                        val position = 1400L + i * 30
                        animated = moving.render(line, SyncType.RICHSYNC, position, settings)
                        still = trackingOnly.render(line, SyncType.RICHSYNC, position, settings)
                        delay(16)
                    }
                    fun silhouette(frame: String): IntArray = Image.makeFromEncoded(Base64.getDecoder().decode(frame)).use { image ->
                        val pixels = Bitmap.makeFromImage(image)
                        try { IntArray(image.width * image.height) { i ->
                            if (pixels.getColor(i % image.width, i / image.width) ushr 24 > 64) 1 else 0
                        } } finally { pixels.close() }
                    }
                    val a = silhouette(animated)
                    val b = silhouette(still)
                    assertTrue(a.indices.count { a[it] != b[it] } > 10,
                        "$mode must move/scale glyphs, beyond merely following syllable timing")
                } finally { moving.close(); trackingOnly.close() }
            }
        }
    }

    @Test fun sharedPlayerRendererProducesNativeFramesForEveryStyle() = runBlocking {
        withContext(Dispatchers.Swing) {
            val words = listOf(LyricWord("Stay ", 1000, 2500), LyricWord("with ", 4000, 500), LyricWord("me", 4700, 1200))
            val line = LyricLine(1000, "Stay with me", words = words, endTime = 5900)
            for (mode in listOf("Spicy Lyrics", "Auralis (Fluid)", "Apple Music (Letter by Letter)", "MetroLyrics")) {
                val surface = NotchLyricsSurface(360)
                try {
                    val settings = AppearanceSettings(lyricsAnimation = mode)
                    var frame = ""
                    repeat(15) { i ->
                        frame = surface.render(line, SyncType.RICHSYNC, 1100L + i * 40, settings)
                        delay(16)
                    }
                    val bytes = Base64.getDecoder().decode(frame)
                    assertTrue(frame.length < 110_000)
                    Image.makeFromEncoded(bytes).use { image ->
                        assertEquals(720, image.width)
                        assertEquals(44, image.height)
                        val pixels = Bitmap.makeFromImage(image)
                        try { assertTrue((0 until 44).any { y -> (0 until 720).any { x -> pixels.getColor(x,y) ushr 24 != 0 } }, mode) }
                        finally { pixels.close() }
                    }
                    val folder = File("build/screens/notch-shared-lyrics").apply { mkdirs() }
                    File(folder, "${mode.substringBefore(' ')}.png").writeBytes(bytes)
                    // Seek and line-only precision use the same persistent scene without stale state.
                    surface.render(line, SyncType.RICHSYNC, 900, settings)
                    surface.render(line.copy(words = null), SyncType.LINE_SYNC, 1200, settings)
                } finally { surface.close() }
            }
        }
    }
}
