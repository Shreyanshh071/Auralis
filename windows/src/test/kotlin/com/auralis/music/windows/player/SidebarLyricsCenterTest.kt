package com.auralis.music.windows.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.auralis.music.domain.model.*
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.math.abs
import kotlin.test.*

class SidebarLyricsCenterTest {
    @Test fun introRemovalLiftsTheFirstPhraseInsteadOfAddingBlankSpaceAboveIt() = runBlocking(Dispatchers.Swing) {
        val position = mutableLongStateOf(2000L)
        val list = LazyListState()
        val lyrics = LyricsData(SyncType.LINE_SYNC, List(8) { index ->
            LyricLine(4000L + index * 2000L, if (index % 2 == 0) "Every breath you take" else "And every move you make")
        }, provider = LyricsProvider.AMLL)
        val scene = ImageComposeScene(340, 272, coroutineContext = Dispatchers.Swing) {
            MaterialTheme { CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(
                lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName, autoScrollLyrics = true,
                standardLyricsBlur = false, lyricsTextPosition = "Left")) {
                Box(Modifier.fillMaxSize().background(Color(0xFF243447))) {
                SidebarLyricsView(lyrics, position, {}, lineGap = androidx.compose.ui.unit.Dp(4f), listState = list,
                    followAnchorFraction = .50f, introAnchorFraction = .12f)
                }
            } }
        }
        try {
            var frame = 0L
            suspend fun render() { scene.render(frame++ * 16_666_667L).close(); delay(1) }
            fun capture(name: String) {
                val output = java.io.File("build/screens/sidebar-intro-$name.png")
                output.parentFile.mkdirs()
                scene.render(frame++ * 16_666_667L).use { image ->
                    image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { output.writeBytes(it.bytes) }
                }
            }
            fun screenTop(index: Int): Int {
                val info = list.layoutInfo
                return assertNotNull(info.visibleItemsInfo.firstOrNull { it.index == index }).offset - info.viewportStartOffset
            }
            repeat(65) { render() }
            val before = screenTop(1)
            capture("before")
            position.longValue = 4000L
            val tops = mutableListOf<Int>()
            repeat(80) { render(); tops += screenTop(1) }
            capture("after")
            assertTrue(tops.last() < before - 8,
                "Removing the intro dots must lift the first lyric, not push it down: before=$before, after=${tops.last()}")
            assertTrue(tops.max() <= before + 2, "The intro transition must not first jump down: $tops")
            val info = list.layoutInfo
            assertTrue(tops.last() <= (info.viewportEndOffset - info.viewportStartOffset) * .15f,
                "The first phrase must use the space below the header, without a half-card blank gap")
            position.longValue = 10000L
            repeat(80) { render() }
            capture("next-phrase")
            val nextInfo = list.layoutInfo
            val row = assertNotNull(nextInfo.visibleItemsInfo.firstOrNull { it.index == 4 })
            val center = nextInfo.viewportStartOffset + (nextInfo.viewportEndOffset - nextInfo.viewportStartOffset) * .5f
            assertTrue(abs(row.offset + row.size / 2f - center) < 2f, "Later sung phrases must still follow in the middle")
        } finally { scene.close() }
    }

    @Test fun sungPhrasesStayCenteredIncludingWrappedRowsAndTextResize() = runBlocking(Dispatchers.Swing) {
        val position = mutableLongStateOf(0L)
        val textSize = mutableFloatStateOf(22f)
        val list = LazyListState()
        val lyrics = LyricsData(SyncType.LINE_SYNC, List(8) { index ->
            LyricLine(index * 2000L, if (index % 2 == 0) "Someone said they left together" else "I ran out the door to get her")
        }, provider = LyricsProvider.AMLL)
        val scene = ImageComposeScene(340, 340, coroutineContext = Dispatchers.Swing) {
            MaterialTheme { CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(
                lyricsTextSize = textSize.floatValue, autoScrollLyrics = true, standardLyricsBlur = false)) {
                Box(Modifier.fillMaxSize()) { SidebarLyricsView(lyrics, position, {}, listState = list) }
            } }
        }
        try {
            var frame = 0L
            suspend fun settle() = repeat(65) { scene.render(frame++ * 16_666_667L).close(); delay(1) }
            fun assertCentered(index: Int) {
                val info = list.layoutInfo
                val row = assertNotNull(info.visibleItemsInfo.firstOrNull { it.index == index })
                val center = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 2f
                assertTrue(abs(row.offset + row.size / 2f - center) < 2f,
                    "Sung row $index must stay centered: row=${row.offset + row.size / 2f}, center=$center")
            }
            for (index in listOf(0, 1, 2, 7)) {
                position.longValue = index * 2000L
                settle()
                assertCentered(index)
            }
            // The same sung line must remain centered when wrapping changes, even while paused.
            textSize.floatValue = 34f
            settle()
            assertCentered(7)
        } finally { scene.close() }
    }
}
