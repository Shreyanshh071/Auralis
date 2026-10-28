package com.auralis.music.windows.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.desktop.lyrics.LyricsDiskCache
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.*
import com.auralis.music.ui.lyrics.renderers.SpicyLyricsLine
import com.auralis.music.ui.lyrics.renderers.SpicyJoinedInk
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class SpicySyllableMotionTest {
    @Test fun mixedAndSequentialSyllableSwellsRenderInBothDirectionsForAnySpelling() {
        // Three source patterns, with unfamiliar spellings: whole/letters,
        // letters/whole, and whole/whole. The same pipeline renders actual songs.
        val patterns = listOf(784L to 2167L, 1700L to 500L, 816L to 944L)
        for ((firstDuration, secondDuration) in patterns) {
            val text = "velora"
            val pieces = listOf(LyricWord("vel", 1000, firstDuration),
                LyricWord("ora", 1000 + firstDuration, secondDuration))
            val grouped = LyricWord(text, 1000, firstDuration + secondDuration, syllables = pieces)
            for (italic in listOf(false, true)) {
                fun capture(time: Long, flattened: Boolean): ByteArray {
                    val words = listOf(if (flattened) grouped.copy(syllables = null) else grouped)
                    val position = mutableLongStateOf(time)
                    val scene = ImageComposeScene(400, 140, coroutineContext = Dispatchers.Unconfined) {
                        MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF342044)).padding(24.dp)) {
                            SpicyLyricsLine(LyricLine(1000, text, words = words, isBackground = italic), words, position,
                                TextStyle(fontSize = (if (italic) 38 else 54).sp, fontWeight = FontWeight.Bold,
                                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal), true)
                        } }
                    }
                    return try {
                        repeat(8) { scene.render(it * 16_666_667L).close() }
                        scene.render(9 * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { png -> png.bytes } }
                    } finally { scene.close() }
                }
                val firstTime = 1000 + firstDuration / 2
                val secondTime = 1000 + firstDuration + secondDuration / 2
                val first = capture(firstTime, false)
                val second = capture(secondTime, false)
                assertFalse(first.contentEquals(second), "Each supplied part must move at its own time")
                assertFalse(first.contentEquals(capture(firstTime, true)), "First part must keep its separate motion")
                assertFalse(second.contentEquals(capture(secondTime, true)), "Second part must keep its separate motion")
                val folder = File("build/screens/spicy-mixed-syllables").apply { mkdirs() }
                File(folder, "$firstDuration-$secondDuration-italic-$italic-first.png").writeBytes(first)
                File(folder, "$firstDuration-$secondDuration-italic-$italic-second.png").writeBytes(second)
            }
        }
    }

    @Test fun independentlySwellingPiecesNeverIntroduceAWordGap() {
        val widths = listOf(23f, 22f, 24f, 25f, 60f) // t/o/g/e/ther
        for (frame in 0..240) {
            val scales = widths.indices.map { i -> .95f + .225f * kotlin.math.sin((frame / 240f + i / 5f) * kotlin.math.PI).toFloat() }
            val lefts = SpicyJoinedInk.leftEdges(30f, widths, scales)
            for (i in 0 until widths.lastIndex) {
                assertEquals(lefts[i] + widths[i] * scales[i], lefts[i + 1], .0001f,
                    "Syllable/letter boundaries must stay joined at frame $frame")
            }
            val right = lefts.last() + widths.last() * scales.last()
            assertEquals(30f + widths.sum() / 2, (lefts.first() + right) / 2, .0001f)
        }
    }

    @Test fun joinedTogetherKeepsItsSpellingThroughTheSwell() {
        val pieces = listOf(LyricWord("toge", 1000, 1100), LyricWord("ther", 2100, 400))
        val word = LyricWord("together", 1000, 1500, syllables = pieces)
        val position = mutableLongStateOf(500L)
        val scene = ImageComposeScene(500, 130, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF342044)).padding(24.dp)) {
                SpicyLyricsLine(LyricLine(1000, "together", words = listOf(word)), listOf(word), position,
                    TextStyle(fontSize = 54.sp, fontWeight = FontWeight.Bold), true)
            } }
        }
        try {
            val folder = File("build/screens/spicy-joined-word").apply { mkdirs() }
            var frame = 0L
            for (time in 500L..2800L step 17L) {
                position.longValue = time
                scene.render(frame++ * 17_000_000L).use { image ->
                    if (frame % 4 == 0L) File(folder, "frame-%03d.png".format(frame / 4)).writeBytes(
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes })
                }
            }
            assertEquals("together", pieces.joinToString("") { it.word })
            assertEquals(listOf(1000L, 2100L), pieces.map { it.time })
        } finally { scene.close() }
    }

    private val xml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
        <p begin="1s" end="4.1s"><span begin="1s" end="1.3s">Hea</span><span begin="1.3s" end="2.6s">ther</span> <span begin="2.7s" end="4.1s">love</span></p>
        <p begin="7.2s" end="8s"><span begin="7.2s" end="8s">Next</span></p>
        </div></body></tt>"""

    @Test fun parserRepairCacheAndOffsetRetainIndependentSungPieces() {
        val parsed = TtmlParser.parse(xml, LyricsProvider.BETTER_LYRICS)
        val line = WordTiming.splitMergedWordsInLine(parsed.lines.first())
        assertEquals("Heather love", line.text)
        val heather = line.words!!.first()
        assertEquals(listOf("Hea", "ther"), heather.syllables!!.map { it.word.trim() })
        assertEquals(listOf(1000L, 1300L), heather.syllables!!.map { it.time })
        assertEquals(listOf(300L, 1300L), heather.syllables!!.map { it.duration })
        assertNull(line.words!!.last().syllables, "An unsplit held word stays a whole letter group")
        val restored = LyricsDiskCache.decode(LyricsDiskCache.encode(parsed.copy(lines = listOf(line))))
        assertEquals(line.words, restored.lines.single().words)
        val shifted = restored.lines.single().words!!.first().shiftedBy(500)
        assertEquals(listOf(1500L, 1800L), shifted.syllables!!.map { it.time })
        assertEquals(heather.syllables!!.map { it.duration }, shifted.syllables!!.map { it.duration })
    }

    @Test fun shorterInstrumentalGapsRespectEveryOverlappingVocal() {
        val parsed = TtmlParser.parse(xml, LyricsProvider.BETTER_LYRICS)
        assertEquals(4100L to 7200L, instrumentalBreakWindows(parsed.lines, minimumRestMs = SPICY_INTERLUDE_MINIMUM_MS)[0])
        val overlap = listOf(LyricLine(1000, "Lead", endTime = 4100),
            LyricLine(3000, "Harmony", isBackground = true, endTime = 7000), LyricLine(7200, "Next", endTime = 8000))
        assertTrue(instrumentalBreakWindows(overlap, minimumRestMs = SPICY_INTERLUDE_MINIMUM_MS).isEmpty())
        assertTrue(instrumentalBreakWindows(overlap.dropLast(1) + LyricLine(9000, "Next"), minimumRestMs = SPICY_INTERLUDE_MINIMUM_MS).isEmpty())
        assertEquals(7000L to 10000L, instrumentalBreakWindows(overlap.dropLast(1) + LyricLine(10000, "Next"), minimumRestMs = SPICY_INTERLUDE_MINIMUM_MS)[1])
    }

    @Test fun dotEntryAndExitFollowReferenceScaleWithoutDelayingShortGaps() {
        assertEquals(0f, LyricsDotsTransition.scale(0, 3100))
        assertTrue(LyricsDotsTransition.scale(150, 2950) > .5f)
        assertEquals(1f, LyricsDotsTransition.scale(300, 2800))
        assertTrue(LyricsDotsTransition.scale(2800, 300) > 1f, "Exit anticipates before contracting")
        assertTrue(LyricsDotsTransition.scale(2970, 130) < .5f)
        assertEquals(0f, LyricsDotsTransition.scale(3000, 100))
        assertEquals(0f, LyricsDotsTransition.scale(3100, 0))
        assertEquals(1f, LyricsDotsTransition.alpha(500, 500))
    }

    @Test fun splitSyllablesRenderDifferentlyFromFlattenedWordsWithoutChangingItalicStyle() {
        val source = TtmlParser.parse(xml, LyricsProvider.BETTER_LYRICS).lines.first()
        val out = File("build/screens/spicy-syllable-motion").apply { mkdirs() }
        for (italic in listOf(false, true)) for (width in listOf(320, 800)) {
            fun capture(flatten: Boolean): ByteArray {
                val words = source.words!!.map { if (flatten) it.copy(syllables = null) else it }
                val position = mutableLongStateOf(1550L)
                val scene = ImageComposeScene(width, 160, coroutineContext = Dispatchers.Unconfined) {
                    MaterialTheme { Box(Modifier.fillMaxSize().padding(24.dp)) {
                        SpicyLyricsLine(source.copy(words = words, isBackground = italic), words, position,
                            TextStyle(fontSize = (if (italic) 28 else 40).sp, fontWeight = FontWeight.Bold,
                                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal), true)
                    } }
                }
                return try {
                    repeat(8) { scene.render(it * 16_666_667L).close() }
                    scene.render(9 * 16_666_667L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                } finally { scene.close() }
            }
            val split = capture(false)
            assertFalse(split.contentEquals(capture(true)), "Syllable boundaries must affect visible motion ($width, italic=$italic)")
            File(out, "split-$width-italic-$italic.png").writeBytes(split)
        }
    }
}
