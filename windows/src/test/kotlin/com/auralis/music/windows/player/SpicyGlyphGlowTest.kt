package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.lyrics.renderers.SpicyLyricsLine
import com.auralis.music.ui.lyrics.renderers.SpicyLyricsMotion
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import kotlin.test.*

class SpicyGlyphGlowTest {
    @Test fun emphasizedLettersKeepOpenCountersAtTheBloomPeak() {
        val position = mutableLongStateOf(1550L)
        val word = LyricWord("OOOO", 1000L, 1800L)
        val scene = ImageComposeScene(380, 170, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color.Black).padding(30.dp)) {
                SpicyLyricsLine(LyricLine(1000L, "OOOO", words = listOf(word)), listOf(word), position,
                    TextStyle(fontSize = 70.sp, lineHeight = 90.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace), true)
            } }
        }
        try {
            repeat(8) { scene.render(it * 16_666_667L).close() }
            scene.render(9 * 16_666_667L).use { image ->
                val folder = java.io.File("build/screens/spicy-glyph-bloom").apply { mkdirs() }
                java.io.File(folder, "letters-peak.png").writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { it.bytes })
                Bitmap.makeFromImage(image).use { bitmap ->
                    val seen = BooleanArray(bitmap.width * bitmap.height)
                    var holes = 0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val first = y * bitmap.width + x
                        if (seen[first] || (bitmap.getColor(x, y) and 255) >= 80) continue
                        val pending = ArrayDeque<Int>().apply { add(first) }
                        seen[first] = true
                        var area = 0; var touchesEdge = false
                        while (pending.isNotEmpty()) {
                            val p = pending.removeFirst(); val px = p % bitmap.width; val py = p / bitmap.width
                            area++
                            if (px == 0 || py == 0 || px == bitmap.width - 1 || py == bitmap.height - 1) touchesEdge = true
                            for ((nx, ny) in listOf(px - 1 to py, px + 1 to py, px to py - 1, px to py + 1)) {
                                if (nx !in 0 until bitmap.width || ny !in 0 until bitmap.height) continue
                                val i = ny * bitmap.width + nx
                                if (!seen[i] && (bitmap.getColor(nx, ny) and 255) < 80) { seen[i] = true; pending.add(i) }
                            }
                        }
                        if (!touchesEdge && area > 20) holes++
                    }
                    assertEquals(4, holes, "Bloom must preserve every O counter instead of filling letters with bright blobs")
                }
            }
        } finally { scene.close() }
    }

    @Test fun swellingGlowFollowsGlyphContoursWithoutRectangularHaze() {
        for (text in listOf("OO OO", "गोंडा")) {
            val position = mutableLongStateOf(1250L)
            val style = TextStyle(fontSize = 70.sp, lineHeight = 90.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            val word = LyricWord(text, 1000, 500)
            val line = LyricLine(1000, text, words = listOf(word), endTime = 1500)
            val pose = SpicyLyricsMotion.pose(1000, 1500, 1250)
            fun scene(reference: Boolean) = ImageComposeScene(400, 180, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme { Box(Modifier.fillMaxSize().background(Color.Black).padding(30.dp)) {
                    if (!reference) SpicyLyricsLine(line, line.words, position, style, true)
                    else {
                        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                        Text(text, style = style, color = Color.White, onTextLayout = { layout = it },
                            modifier = Modifier.fillMaxWidth().drawWithContent {
                                val paragraph = layout
                                if (paragraph == null) drawContent() else {
                                    val pivot = paragraph.getPathForRange(0, text.length).getBounds().center
                                    val lift = pose.liftEm * style.fontSize.toPx()
                                    withTransform({
                                        translate(top = lift)
                                        scale(pose.scale, pose.scale, pivot)
                                    }) { drawText(paragraph, color = Color.White, shadow = Shadow.None) }
                                }
                            })
                    }
                } }
            }
            val maskScene = scene(true)
            val glowScene = scene(false)
            try {
                repeat(10) { frame ->
                    maskScene.render(frame * 16_666_667L).close()
                    glowScene.render(frame * 16_666_667L).close()
                }
                val mask = maskScene.render(11 * 16_666_667L).use { Bitmap.makeFromImage(it) }
                val glowing = glowScene.render(11 * 16_666_667L).use { image ->
                    val folder = java.io.File("build/screens/spicy-glyph-bloom").apply { mkdirs() }
                    java.io.File(folder, if (text == "OO OO") "latin-peak.png" else "hindi-peak.png")
                        .writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { it.bytes })
                    Bitmap.makeFromImage(image)
                }
                mask.use { ink -> glowing.use { actual ->
                    var escaped = 0
                    var brightest = 0
                    var haloPixels = 0
                    val stride = ink.width + 1
                    val prefix = IntArray(stride * (ink.height + 1))
                    for (y in 0 until ink.height) for (x in 0 until ink.width) {
                        val i = (y + 1) * stride + x + 1
                        prefix[i] = (if ((ink.getColor(x, y) and 255) > 0) 1 else 0) +
                            prefix[i - 1] + prefix[i - stride] - prefix[i - stride - 1]
                    }
                    fun hasInkNear(x: Int, y: Int, radius: Int): Boolean {
                        val l = (x - radius).coerceAtLeast(0); val r = (x + radius + 1).coerceAtMost(ink.width)
                        val t = (y - radius).coerceAtLeast(0); val b = (y + radius + 1).coerceAtMost(ink.height)
                        return prefix[b * stride + r] - prefix[b * stride + l] - prefix[t * stride + r] + prefix[t * stride + l] > 0
                    }
                    for (y in 2 until ink.height - 2) for (x in 2 until ink.width - 2) {
                        val light = actual.getColor(x, y) and 255
                        brightest = maxOf(brightest, light)
                        if (!hasInkNear(x, y, 2) && light > 2) haloPixels++
                        if (!hasInkNear(x, y, 14) && light > 2) escaped++
                    }
                    assertEquals(0, escaped, "A tight glyph halo must not turn into a rectangular haze for $text")
                    assertTrue(haloPixels > 10, "Swelling must produce visible bloom around the glyph contour")
                    assertTrue(brightest > 220, "Sung letters must remain bright and sharp")
                } }
            } finally { maskScene.close(); glowScene.close() }
        }
    }
}
