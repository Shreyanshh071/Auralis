package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.domain.model.*
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*

class SidebarLyricsScrollReturnTest {
    @Test fun pausedSidebarReturnsAfterIdleAndCanBeInterruptedAndReturnAgain() = runBlocking(Dispatchers.Swing) {
        val position = mutableLongStateOf(50000L)
        val lyrics = LyricsData(SyncType.LINE_SYNC, List(90) { LyricLine(it * 2000L, "Phrase number $it") },
            provider = LyricsProvider.AMLL)
        val scene = ImageComposeScene(340, 300, coroutineContext = Dispatchers.Swing) {
            MaterialTheme { CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(
                lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName, autoScrollLyrics = true,
                standardLyricsBlur = false)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF243447))) { SidebarLyricsView(lyrics, position, {}) }
            } }
        }
        try {
            var frame = 0L
            suspend fun frames(count: Int) = repeat(count) { scene.render(System.nanoTime()).close(); delay(16) }
            fun capture() = scene.render(System.nanoTime()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
            }
            fun wheel(delta: Float, time: Long) = scene.sendPointerEvent(PointerEventType.Scroll,
                Offset(160f, 150f), scrollDelta = Offset(0f, delta), timeMillis = time, type = PointerType.Mouse)
            frames(70)
            val current = capture()
            wheel(12f, 5000L)
            // Capture during the gesture, before the real idle timer can expire
            // on a slow headless renderer.
            frames(12)
            val browsed = capture()
            assertFalse(current.contentEquals(browsed), "The sidebar must accept manual browsing")
            delay(1700L)
            frames(20)
            val returning = capture()
            assertFalse(browsed.contentEquals(returning), "The paused sidebar must start returning after idle")
            wheel(3f, 7000L)
            frames(40)
            assertFalse(current.contentEquals(capture()), "New input must interrupt the return")
            delay(1700L)
            frames(100)
            scene.sendPointerEvent(PointerEventType.Exit, Offset(-1f, -1f), type = PointerType.Mouse)
            frames(30)
            fun brightInkCenter(bytes: ByteArray): Double {
                val image = javax.imageio.ImageIO.read(bytes.inputStream())
                var sum = 0L; var count = 0L
                for (y in 0 until image.height - 35) for (x in 0 until image.width) {
                    // At the paused line's start Spicy draws .85 glyph opacity
                    // inside its .85 active-line layer (~184 on black). Unsung
                    // neighbouring rows remain below this threshold.
                    if ((image.getRGB(x, y) shr 16 and 255) > 150) { sum += y; count++ }
                }
                assertTrue(count > 0, "The paused lyric must stay highlighted")
                return sum.toDouble() / count
            }
            assertTrue(kotlin.math.abs(brightInkCenter(current) - brightInkCenter(capture())) <= 2.0,
                "A second idle period must center the same paused lyric within pixel rounding")
            assertEquals(50000L, position.longValue)
        } finally { scene.close() }
    }
}
