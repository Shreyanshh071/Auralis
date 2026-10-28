package com.auralis.music.windows.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.lyrics.LyricsDots
import com.auralis.music.ui.lyrics.InstrumentalIntroIndicator
import com.auralis.music.ui.lyrics.LyricsIntervalIndicator
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class LyricsDotsMotionTest {
    @Test fun liftedDotsRemainWholeInsideIntroAndInterludeHitTargets() {
        for (compact in listOf(true, false)) {
            val position = mutableLongStateOf(0L)
            val dotSize = if (compact) 12.dp else 28.dp
            fun scene(kind: Int) = ImageComposeScene(240, 160, coroutineContext = Dispatchers.Unconfined) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    when (kind) {
                        0 -> InstrumentalIntroIndicator(position, 8000L, dotSize = dotSize,
                            compact = compact, horizontalAlignment = Alignment.CenterHorizontally)
                        1 -> Column(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp,
                            bottom = if (compact) 1.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                LyricsDots(position.longValue / 8000f, position.longValue, 8000 - position.longValue,
                                    dotSize = dotSize, compact = compact)
                            }
                        }
                        else -> LyricsIntervalIndicator(0L, 8000L, position.longValue,
                            visible = true, dotSize = dotSize, compact = compact)
                    }
                }
            }
            val intro = scene(0)
            val unclipped = scene(1)
            val interval = scene(2)
            try {
                var frame = 0L
                for (time in 0L..8000L step 40L) {
                    position.longValue = time
                    // Prime composition/layout; use opaque backgrounds so previous frames cannot accumulate.
                    repeat(2) {
                        intro.render(frame * 16_666_667L).close()
                        unclipped.render(frame * 16_666_667L).close()
                        interval.render(frame++ * 16_666_667L).close()
                    }
                    fun ink(scene: ImageComposeScene) = scene.render(frame * 16_666_667L).use { image ->
                        org.jetbrains.skia.Bitmap.makeFromImage(image).use { pixels ->
                            var total = 0L
                            for (y in 0 until pixels.height) for (x in 0 until pixels.width)
                                total += pixels.getColor(x, y) and 255
                            total
                        }
                    }
                    val expected = ink(unclipped)
                    assertEquals(expected, ink(intro), "Intro cut the raised dot at $time ms (compact=$compact)")
                    // Translation onto another pixel grid can change antialiasing slightly.
                    assertTrue(kotlin.math.abs(expected - ink(interval)) <= maxOf(200L, expected / 50),
                        "Interlude cut the raised dot at $time ms (compact=$compact)")
                    frame++
                }
            } finally { intro.close(); unclipped.close(); interval.close() }
        }
    }

    @Test fun dotsMoveEveryFrameWithoutHaloAndFreezeWhenPaused() {
        val position = mutableLongStateOf(1000L)
        val scene = ImageComposeScene(320, 120, coroutineContext = Dispatchers.Unconfined) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LyricsDots(position.longValue / 12000f, position.longValue, 12000L - position.longValue, dotSize = 24.dp)
            }
        }
        try {
            var frame = 0L
            fun render(): ByteArray = scene.render(frame++ * 16_666_667L)
                .encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
            fun settled(): ByteArray { repeat(3) { render() }; return render() }
            val output = File("build/screens/dots-motion").apply { mkdirs() }
            val samples = mutableSetOf<Int>()
            for (i in 0..60) {
                position.longValue = 1000L + i * 1000L / 60
                val image = settled()
                samples += image.contentHashCode()
                if (i % 10 == 0) File(output, "frame-$i.png").writeBytes(image)
            }
            assertTrue(samples.size > 50, "Dot motion must advance at frame rate, not in coarse clock steps")
            val paused = settled()
            assertContentEquals(paused, settled(), "Paused audio must freeze the springs")
            position.longValue = 6000L
            val middle = settled()
            File(output, "middle.png").writeBytes(middle)
            val pixels = ImageIO.read(middle.inputStream())
            // 24 dp circles centred at x=118,160,202. No glow may occupy the gaps.
            for (x in listOf(98, 138, 140, 180, 182, 222)) for (y in 0 until 120)
                assertEquals(0, pixels.getRGB(x, y) ushr 24, "No outer halo at ($x,$y)")
            position.longValue = 1000L
            assertContentEquals(File(output, "frame-0.png").readBytes(), settled(), "Seeking back must reset motion")
        } finally { scene.close() }
    }
}
