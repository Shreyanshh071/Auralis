package com.auralis.music.windows.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.*
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlin.math.abs
import kotlin.test.*

class LyricsContinuousFollowTest {
    @Test fun sidebarCanCenterTheFirstAndLastPhraseWithoutHittingTheScrollBoundary() = runBlocking(Dispatchers.Swing) {
        val position = mutableLongStateOf(0L)
        val state = LazyListState()
        val lyrics = LyricsData(SyncType.LINE_SYNC, List(12) { LyricLine(it * 2000L, "Phrase $it") }, provider = LyricsProvider.AMLL)
        val scene = ImageComposeScene(340, 400, coroutineContext = Dispatchers.Swing) {
            MaterialTheme { CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(
                lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName, standardLyricsBlur = false)) {
                SidebarLyricsView(lyrics, position, {}, listState = state)
            } }
        }
        try {
            suspend fun frames() = repeat(45) { scene.render(System.nanoTime()).close(); delay(8) }
            for (index in listOf(0, 11)) {
                position.longValue = index * 2000L
                frames()
                val info = state.layoutInfo
                val item = assertNotNull(info.visibleItemsInfo.firstOrNull { it.index == index })
                val anchor = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) * .50f
                assertTrue(abs(item.offset + item.size / 2f - anchor) < 3f,
                    "Phrase $index must reach the normal sidebar anchor, including at a list boundary")
            }
        } finally { scene.close() }
    }

    @Test fun rapidTargetsRetainVelocityButManualResetDoesNotCarryIt() {
        val spring = LyricsScrollSpring()
        assertEquals(0f, spring.frameSeconds(1_000_000_000L))
        assertEquals(.004f, spring.frameSeconds(1_004_000_000L), .00001f,
            "A 240 Hz frame must not be rounded up to an 8 ms frame")
        spring.step(120f, .016f)
        val speed = spring.velocity
        assertTrue(speed > 0f)
        val continuous = spring.step(200f, spring.frameSeconds(1_020_000_000L))
        val restarted = LyricsScrollSpring().step(200f, .016f)
        assertTrue(continuous > restarted, "A new phrase arriving mid-scroll must retain motion")
        spring.reset()
        assertEquals(0f, spring.velocity)
        assertEquals(0f, spring.frameSeconds(2_000_000_000L))
        assertEquals(.1f, spring.frameSeconds(2_220_000_000L), .0001f,
            "Repeated slow frames must still advance the scroll instead of freezing it")
    }

    @Test fun phraseMotionHasTheSamePaceOn60To240HzDisplaysAndEasesIntoTheMove() {
        fun trajectory(hz: Int): List<Float> {
            val spring = LyricsScrollSpring()
            var remaining = 180f
            return List(hz) {
                remaining -= spring.step(remaining, 1f / hz)
                remaining
            }
        }
        val reference = trajectory(60)
        assertTrue(180f - reference.first() < 9f, "A new phrase must ease in instead of jumping at full velocity")
        for (hz in listOf(60, 120, 144, 240)) {
            val trace = trajectory(hz)
            for (quarter in 1..4) assertEquals(reference[quarter * 15 - 1], trace[quarter * hz / 4 - 1], .8f,
                "The same real elapsed time must produce the same motion at $hz Hz")
            assertTrue(trace.zipWithNext().all { (a, b) -> b <= a && b >= -.01f })
            assertTrue(abs(trace.last()) < .01f)
        }
    }

    private val overlap = listOf(
        LyricLine(0, "Earlier voice", words = listOf(LyricWord("Earlier ", 0, 500), LyricWord("voice", 500, 4000)), endTime = 1000),
        LyricLine(100, "(Harmony)", words = listOf(LyricWord("(Harmony)", 100, 4800, true)), isBackground = true),
        LyricLine(1000, "Second voice", words = listOf(LyricWord("Second ", 1000, 500), LyricWord("voice", 1500, 700))),
        LyricLine(1700, "Third voice", words = listOf(LyricWord("Third ", 1700, 500), LyricWord("voice", 2200, 600))),
        LyricLine(5000, "Next phrase", endTime = 6500))

    @Test fun overlappingVocalsKeepTheirOwnHighlightsWithoutPullingTheScrollBackwards() {
        assertEquals(setOf(0, 1, 2, 3), SpicyLyricsPresentation.activeIndices(overlap, 1800),
            "A short paragraph end must not stop a genuinely timed held word")
        val anchors = (0L..4900L step 25).map { SpicyLyricsPresentation.scrollAnchor(overlap, it, 0) }
        assertTrue(anchors.zipWithNext().all { (a, b) -> b >= a },
            "Ending the newer voice must not pull the viewport back to an older held harmony")
        assertEquals(3, SpicyLyricsPresentation.scrollAnchor(overlap, 3500, 0))
        assertEquals(0, SpicyLyricsPresentation.scrollAnchor(overlap, 200, 3), "A real backward seek must still return")
        assertTrue(SpicyLyricsPresentation.activeIndices(overlap, 4950).isEmpty(), "Do not fill a genuine rest")
    }

    @Test fun mainAndFullHeightSidebarFollowConsecutiveOverlappingPhrasesWithoutReverseCorrections() = runBlocking(Dispatchers.Swing) {
        for (sidebar in listOf(true, false)) {
            val position = mutableLongStateOf(6000L)
            val lines = List(28) { i -> LyricLine(i * 1000L, "Voice number $i",
                words = listOf(LyricWord("Voice ", i * 1000L, 400),
                    LyricWord("number ", i * 1000L + 400, 300), LyricWord("$i", i * 1000L + 700, if (i % 3 == 0) 2300 else 400))) }
            val lyrics = LyricsData(SyncType.RICHSYNC, lines, provider = LyricsProvider.SPICY_LYRICS)
            var list: LazyListState? = if (sidebar) LazyListState() else null
            val ui = LyricsUiPlatform(DefaultLyricsUi.strings, manualScrollModifier = { state, _ -> list = state; Modifier })
            val scene = ImageComposeScene(if (sidebar) 340 else 700, 600, coroutineContext = Dispatchers.Swing) {
                MaterialTheme { CompositionLocalProvider(LocalLyricsUi provides ui, LocalAppearanceSettings provides AppearanceSettings(
                    lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName, standardLyricsBlur = true, autoScrollLyrics = true)) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF202334))) {
                        if (sidebar) SidebarLyricsView(lyrics, position, {}, lineGap = 10.dp, listState = list!!)
                        else ExperimentalLyricsView(lyrics, position, {}, isPlaying = true,
                            track = Track("overlapping-follow", "Fixture", "Voices", duration = 30))
                    }
                } }
            }
            try {
                suspend fun frame() { scene.render(System.nanoTime()).close(); delay(8) }
                repeat(35) { frame() }
                val state = assertNotNull(list)
                val trace = mutableListOf<Int>()
                for (time in 6000L..10500L step 50L) {
                    position.longValue = time
                    frame()
                    trace += state.firstVisibleItemIndex * 100000 + state.firstVisibleItemScrollOffset
                }
                repeat(55) { frame(); trace += state.firstVisibleItemIndex * 100000 + state.firstVisibleItemScrollOffset }
                val output = java.io.File("build/screens/overlap-follow-${if (sidebar) "sidebar" else "main"}.png")
                output.parentFile.mkdirs()
                scene.render(System.nanoTime()).use { image ->
                    image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { output.writeBytes(it.bytes) }
                }
                assertTrue(trace.last() > trace.first(), "Playback must advance the ${if (sidebar) "sidebar" else "main player"} viewport: $trace")
                assertTrue(trace.zipWithNext().all { (a, b) -> b >= a - 1 }, "Overlapping voices must not reverse the viewport: $trace")
                assertTrue(trace.distinct().size > 15, "Following must draw intermediate positions")
                assertTrue(state.layoutInfo.visibleItemsInfo.any { it.index == 10 }, "The current phrase must remain visible")
            } finally { scene.close() }
        }
    }
}
