package com.auralis.music.windows.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.ImageComposeScene
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.*

class SidebarWordLyricsTest {
    @Test fun sidebarUsesFullPlayerWordSweepAndDoesNotLightFutureWordsDuringARest() {
        val position = mutableLongStateOf(500L)
        val line = LyricLine(1000, "AAAA BBBB", words = listOf(
            LyricWord("AAAA ", 1000, 1000), LyricWord("BBBB", 3000, 1000)))
        val scene = ImageComposeScene(320, 80, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    SidebarLyricLine(line.text, true, true, {}, line = line, position = position, syncType = SyncType.RICHSYNC)
                }
            }
        }
        try {
            var frame = 0L
            fun render(): BufferedImage = ImageIO.read(scene.render(frame++ * 16_666_667L)
                .encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }.inputStream())
            fun brightness(image: BufferedImage, start: Int, end: Int): Long {
                var sum = 0L
                for (x in start until end) for (y in 8 until 50) {
                    val pixel = image.getRGB(x, y)
                    sum += ((pixel ushr 16) and 255) + ((pixel ushr 8) and 255) + (pixel and 255)
                }
                return sum
            }
            repeat(3) { render() }
            val before = render()
            position.longValue = 2500 // The first word finished; the second has not started.
            val rest = render()
            val images = java.io.File("build/screens/sidebar-words").apply { mkdirs() }
            ImageIO.write(before, "png", java.io.File(images, "before.png"))
            ImageIO.write(rest, "png", java.io.File(images, "rest.png"))
            assertTrue(brightness(rest, 10, 80) > brightness(before, 10, 80), "Sung word must brighten")
            assertEquals(brightness(before, 115, 180), brightness(rest, 115, 180), "Future word must stay dim during a rest")
            val paused = render()
            assertEquals(brightness(rest, 10, 180), brightness(paused, 10, 180), "Paused playback must freeze word progress")
            position.longValue = 4500
            val completed = render()
            assertTrue(brightness(completed, 115, 180) > brightness(rest, 115, 180), "Next word must brighten at its own timestamp")
            position.longValue = 500
            val seekBack = render()
            assertEquals(brightness(before, 10, 180), brightness(seekBack, 10, 180), "Seeking back must reset the word sweep")
        } finally { scene.close() }
    }
}
