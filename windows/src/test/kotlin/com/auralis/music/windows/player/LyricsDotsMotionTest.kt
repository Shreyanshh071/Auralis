package com.auralis.music.windows.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.lyrics.LyricsDots
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class LyricsDotsMotionTest {
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
