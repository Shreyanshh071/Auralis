package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.instrumentalBreakWindows
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class LyricsInterludeTest {
    @Test fun breaksFollowVocalEndsAndIncludeOutroWithoutCoveringOverlappingVocals() {
        val lines = listOf(
            LyricLine(1000, "Lead", endTime = 4000),
            LyricLine(3000, "Harmony", isBackground = true, endTime = 9000),
            LyricLine(18000, "Next", words = listOf(LyricWord("Next", 18000, 2000))))
        assertEquals(mapOf(1 to (9000L to 18000L), 2 to (20000L to 30000L)),
            instrumentalBreakWindows(lines, 30000))
        assertTrue(instrumentalBreakWindows(listOf(LyricLine(1000, "Held note", endTime = 13000),
            LyricLine(4000, "Harmony", endTime = 6000), LyricLine(15000, "Next"))).isEmpty())
        assertTrue(instrumentalBreakWindows(listOf(LyricLine(1000, "One", endTime = 4000),
            LyricLine(6000, "Two", endTime = 8000))).isEmpty(), "Ordinary breaths are not interludes")
        assertEquals(3500L to 15000L,
            instrumentalBreakWindows(listOf(LyricLine(1000, "Line only"), LyricLine(15000, "Next")))[0])
        assertTrue(instrumentalBreakWindows(listOf(LyricLine(1000, "Last", endTime = 4000))).isEmpty(),
            "An outro requires a real track duration")
    }

    @Test fun sidebarShowsLaterBreaksAutoScrollsResetsOnSeekAndSkipsToNextVocal() {
        val position = mutableLongStateOf(1500L)
        val lyrics = LyricsData(SyncType.RICHSYNC, listOf(
            LyricLine(1000, "I", endTime = 2500),
            LyricLine(12000, "I", endTime = 13500),
            LyricLine(24000, "I", endTime = 25500)), provider = LyricsProvider.AMLL, durationMs = 40000)
        var sought = -1L
        val scene = ImageComposeScene(340, 240, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
                SidebarLyricsView(lyrics, position, { sought = it })
            } }
        }
        try {
            var frame = 0L
            val output = File("build/screens/interludes").apply { mkdirs() }
            fun render(name: String): java.awt.image.BufferedImage {
                repeat(100) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use {
                    it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                }
                File(output, "$name.png").writeBytes(bytes)
                return ImageIO.read(bytes.inputStream())
            }
            fun dotRow(image: java.awt.image.BufferedImage): Pair<Int, List<IntRange>>? {
                val seen = mutableSetOf<Pair<Int, Int>>()
                val components = mutableListOf<Pair<IntRange, IntRange>>()
                for (y in 20 until 215) for (x in 0..120) {
                    if ((image.getRGB(x, y) and 255) <= 60 || !seen.add(x to y)) continue
                    val pending = ArrayDeque<Pair<Int, Int>>().apply { add(x to y) }
                    val pixels = mutableListOf<Pair<Int, Int>>()
                    while (pending.isNotEmpty()) {
                        val p = pending.removeFirst(); pixels += p
                        for (n in listOf(p.first - 1 to p.second, p.first + 1 to p.second,
                            p.first to p.second - 1, p.first to p.second + 1)) {
                            if (n.first in 0..120 && n.second in 20 until 215 &&
                                (image.getRGB(n.first, n.second) and 255) > 60 && seen.add(n)) pending.add(n)
                        }
                    }
                    val xs = pixels.minOf { it.first }..pixels.maxOf { it.first }
                    val ys = pixels.minOf { it.second }..pixels.maxOf { it.second }
                    if (xs.count() in 7..22 && ys.count() in 7..24 && pixels.size > 30) components += xs to ys
                }
                val dots = components.sortedBy { it.first.first }
                return if (dots.size == 3) dots[1].second.average().toInt() to dots.map { it.first } else null
            }
            assertNull(dotRow(render("singing")))
            position.longValue = 6500
            val (y, dots) = assertNotNull(dotRow(render("first-break")), "Show dots after the first vocal")
            val point = Offset(dots[1].average().toFloat(), y.toFloat())
            scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse)
            assertEquals(12000L, sought)
            position.longValue = 12500
            assertNull(dotRow(render("next-vocal")), "Dots disappear when singing resumes")
            position.longValue = 18000
            assertNotNull(dotRow(render("second-break")), "Later instrumental breaks must scroll into view")
            position.longValue = 32000
            assertNotNull(dotRow(render("outro")))
            position.longValue = 1500
            assertNull(dotRow(render("seek-back")), "Seeking back removes stale dots")
        } finally { scene.close() }
    }
}
