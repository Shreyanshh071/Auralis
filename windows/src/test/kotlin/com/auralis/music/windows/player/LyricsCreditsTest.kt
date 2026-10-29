package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.LyricsCredits
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class LyricsCreditsTest {
    @Test fun sidebarOmitsSongwritersButKeepsSourceAndSyncContributors() {
        val data = LyricsData(SyncType.PLAIN, emptyList(), provider = LyricsProvider.SPICY_LYRICS, credits = listOf(
            LyricsCredit("Spicy Lyrics", "https://spicylyrics.org"), LyricsCredit("Uploaded by Uploader", "https://spicylyrics.org/uid/1"),
            LyricsCredit("Made by Maker", "https://spicylyrics.org/uid/2"), LyricsCredit("Written by Many Songwriters", "")))
        val compact = com.auralis.music.ui.lyrics.lyricsCreditsForDisplay(data, compact = true)
        assertEquals(3, compact.size)
        assertTrue(compact.none { it.label.startsWith("Written by") })
        assertEquals(data.credits.take(3).map { it.url }, compact.map { it.url })
        assertEquals(4, com.auralis.music.ui.lyrics.lyricsCreditsForDisplay(data).size)
    }

    @Test fun compactCreditsUseOneLineWhenTheyFitAndWrapWhenNeeded() {
        val heights = listOf(340, 110).map { width ->
            var height = 0
            val scene = ImageComposeScene(width, 120, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme { androidx.compose.runtime.CompositionLocalProvider(
                    com.auralis.music.ui.lyrics.LocalLyricsProviderPicker provides {},
                    com.auralis.music.ui.theme.LocalAppearanceSettings provides AppearanceSettings(
                        lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName)) {
                    LyricsCredits(LyricsData(SyncType.PLAIN, emptyList(), provider = LyricsProvider.UNISON),
                        Modifier.onGloballyPositioned { height = it.size.height }, compact = true)
                } }
            }
            try {
                repeat(5) { scene.render(it * 16_666_667L).close() }
                val folder = File("build/screens/lyrics-credits").apply { mkdirs() }
                scene.render(100_000_000L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { File(folder, "compact-adaptive-$width.png").writeBytes(it.bytes) }
                }
                height
            } finally { scene.close() }
        }
        assertTrue(heights.first() <= 24, "Short credits must fit on one row")
        assertTrue(heights.last() > heights.first(), "Narrow panels must wrap without clipping")
    }

    @Test fun wrappedCompactCreditsStayVisibleOutsideTheLyricViewport() {
        val data = LyricsData(SyncType.PLAIN, List(12) { LyricLine(0, "A full lyric line stays above the credits") },
            provider = LyricsProvider.SPICY_LYRICS, credits = listOf(
                LyricsCredit("Spicy Lyrics", "https://spicylyrics.org"),
                LyricsCredit("Uploaded by ! </Ruytha> ルイザ", "https://spicylyrics.org/uid/upload"),
                LyricsCredit("Written by Ke and another contributor", "https://example.com/writer"),
                LyricsCredit("Made by Community timing contributor", "https://example.com/timing")))
        for (width in listOf(260, 340)) {
            val settings = AppearanceSettings(autoScrollLyrics = false, standardLyricsBlur = false,
                lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName)
            var footerHeight = 0
            val footer = ImageComposeScene(width - 16, 180, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme { androidx.compose.runtime.CompositionLocalProvider(
                    com.auralis.music.ui.lyrics.LocalLyricsProviderPicker provides {},
                    com.auralis.music.ui.theme.LocalAppearanceSettings provides settings) {
                    LyricsCredits(data, Modifier.onGloballyPositioned { footerHeight = it.size.height }, compact = true)
                } }
            }
            try { repeat(5) { footer.render(it * 16_666_667L).close() } } finally { footer.close() }
            assertTrue(footerHeight > 30, "Long credits must wrap below the provider row")
            val list = androidx.compose.foundation.lazy.LazyListState()
            val scene = ImageComposeScene(width, 360, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme { androidx.compose.runtime.CompositionLocalProvider(
                    com.auralis.music.ui.lyrics.LocalLyricsProviderPicker provides {},
                    com.auralis.music.ui.theme.LocalAppearanceSettings provides settings) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF402744))) {
                        SidebarLyricsView(data, mutableStateOf(0L), {}, listState = list)
                    }
                } }
            }
            try {
                repeat(8) { scene.render(it * 16_666_667L).close() }
                assertTrue(list.layoutInfo.viewportSize.height <= 360 - footerHeight,
                    "The lyric viewport must end above ALL wrapped credits (width=$width, footer=$footerHeight)")
                val folder = File("build/screens/lyrics-credits").apply { mkdirs() }
                scene.render(160_000_000L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { File(folder, "compact-wrapped-$width.png").writeBytes(it.bytes) }
                }
            } finally { scene.close() }
        }
    }

    @Test fun providerLabelAndVisibleChangeBothOpenThePicker() {
        for (compact in listOf(false, true)) {
            var opens = 0
            val data = LyricsData(SyncType.PLAIN, listOf(LyricLine(0, "Preview")), provider = LyricsProvider.QQMUSIC)
            val scene = ImageComposeScene(440, 80, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.auralis.music.ui.lyrics.LocalLyricsProviderPicker provides { opens++ },
                        com.auralis.music.ui.theme.LocalAppearanceSettings provides
                            AppearanceSettings(lyricsAnimation = LyricsAnimationMode.AURALIS.displayName)) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) { LyricsCredits(data, compact = compact) }
                    }
                }
            }
            try {
                repeat(5) { scene.render(it * 16_666_667L).close() }
                fun click(x: Float, y: Float) {
                    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press,
                        androidx.compose.ui.geometry.Offset(x, y), type = androidx.compose.ui.input.pointer.PointerType.Mouse)
                    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release,
                        androidx.compose.ui.geometry.Offset(x, y), type = androidx.compose.ui.input.pointer.PointerType.Mouse)
                }
                click(25f, if (compact) 10f else 14f)
                assertEquals(1, opens, "Clicking the provider label opens the picker")
                click(if (compact) 70f else 150f, if (compact) 10f else 14f)
                assertEquals(2, opens, "The visible Change action opens the same picker")
            } finally { scene.close() }
        }
    }

    @Test fun compactSourceStaysAtTheSameInsetWithAndWithoutSpicyCredit() {
        val sourceTops = listOf(LyricsAnimationMode.SPICY_LYRICS, LyricsAnimationMode.AURALIS).map { mode ->
            val data = LyricsData(SyncType.PLAIN, listOf(LyricLine(0, "Preview")), provider = LyricsProvider.LRCLIB)
            val scene = ImageComposeScene(340, 320, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    androidx.compose.runtime.CompositionLocalProvider(com.auralis.music.ui.theme.LocalAppearanceSettings provides
                        AppearanceSettings(lyricsAnimation = mode.displayName)) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            SidebarLyricsView(data, mutableStateOf(0L), {})
                        }
                    }
                }
            }
            try {
                repeat(8) { scene.render(it * 16_666_667L).close() }
                val bytes = scene.render(150_000_000L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                }
                val image = javax.imageio.ImageIO.read(bytes.inputStream())
                val sourceTop = (270 until 320).firstOrNull { y ->
                    (12 until 53).any { x -> (image.getRGB(x, y) and 255) > 100 }
                }
                assertNotNull(sourceTop)
                assertTrue(320 - sourceTop >= 12, "Source credit needs a visible bottom inset")
                sourceTop
            } finally { scene.close() }
        }
        assertEquals(sourceTops.first(), sourceTops.last(), "Changing lyrics UI must not move the source credit")
    }

    @Test fun cachedProvidersWithoutCreditMetadataStillIdentifyTheirSource() {
        val unison = LyricsData(SyncType.LINE_SYNC, listOf(LyricLine(0, "Test")), provider = LyricsProvider.UNISON)
        assertEquals("Lyrics from Unison", com.auralis.music.ui.lyrics.lyricsCreditsForDisplay(unison).single().label)
        val supplied = listOf(LyricsCredit("Apple Music", "https://music.apple.com"))
        assertEquals(listOf(LyricsCredit("Lyrics from Apple Music", "https://music.apple.com"),
            LyricsCredit("via Spicy Lyrics", "https://spicylyrics.org")),
            com.auralis.music.ui.lyrics.lyricsCreditsForDisplay(unison.copy(provider = LyricsProvider.SPICY_LYRICS, credits = supplied)))
        assertTrue(com.auralis.music.ui.lyrics.lyricsCreditsForDisplay(null).isEmpty())
        assertTrue(com.auralis.music.ui.lyrics.lyricsCreditsForDisplay(unison.copy(provider = LyricsProvider.SPICY_LYRICS)).single().label.contains("Unknown source"))
    }
    @Test fun communityCreditsKeepEverySuppliedContributorAndLink() {
        val credits = listOf(LyricsCredit("Spicy Lyrics", "https://spicylyrics.org"),
            LyricsCredit("Uploaded by uploader", "https://spicylyrics.org/uid/123"),
            LyricsCredit("Made by maker", "https://spicylyrics.org/uid/456"),
            LyricsCredit("Written by Song Writer", ""))
        val data = LyricsData(SyncType.LINE_SYNC, listOf(LyricLine(0, "Test")),
            provider = LyricsProvider.SPICY_LYRICS, credits = credits)
        val displayed = com.auralis.music.ui.lyrics.lyricsCreditsForDisplay(data)
        assertEquals("Lyrics from Spicy Lyrics", displayed.first().label)
        assertEquals(credits.map { it.url }, displayed.map { it.url })
        assertEquals(credits.drop(1), displayed.drop(1), "Fullscreen retains songwriter credits")
        val sidebar = com.auralis.music.ui.lyrics.lyricsCreditsForDisplay(data, compact = true)
        assertFalse(sidebar.any { it.label.startsWith("Written by ") })
        assertEquals(credits.take(3).map { it.url }, sidebar.map { it.url },
            "Sidebar keeps provider and contributor links")
    }
    @Test fun longCreditsWrapInsideReservedFooterAndSurviveLyricChanges() {
        val lyric = mutableStateOf("First lyric")
        val data = LyricsData(SyncType.PLAIN, emptyList(), provider = LyricsProvider.SPICY_LYRICS,
            credits = listOf(LyricsCredit("Spicy Lyrics", "https://spicylyrics.org"),
                LyricsCredit("Uploaded by a-long-contributor-name", "https://spicylyrics.org/uid/123"),
                LyricsCredit("Made by another-contributor", "https://spicylyrics.org/uid/456"),
                LyricsCredit("Written by Song Writer", "")))
        for (width in listOf(320, 900)) {
            var footerHeight = 0
            var footerWidth = 0
            val scene = ImageComposeScene(width, 300, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    androidx.compose.runtime.CompositionLocalProvider(com.auralis.music.ui.theme.LocalAppearanceSettings provides
                        AppearanceSettings(lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName)) {
                    Column(Modifier.fillMaxSize().background(Color(0xFF763122)).padding(12.dp)) {
                        Box(Modifier.weight(1f)) { Text(lyric.value, color = Color.White) }
                        LyricsCredits(data, Modifier.onGloballyPositioned {
                            footerWidth = it.size.width; footerHeight = it.size.height
                        })
                    }
                    }
                }
            }
            try {
                var frame = 0L
                fun render() = scene.render(frame++ * 16_666_667L)
                repeat(8) { render().close() }
                assertTrue(footerWidth <= width - 24)
                assertTrue(footerHeight in 20..150, "Credits must remain visible in the panel")
                val previousHeight = footerHeight
                lyric.value = "A later lyric after playback or scrolling"
                repeat(4) { render().close() }
                assertEquals(previousHeight, footerHeight)
                val folder = File("build/screens/lyrics-credits").apply { mkdirs() }
                File(folder, "credits-$width.png").writeBytes(render().use {
                    it.encodeToData(EncodedImageFormat.PNG)!!.use { png -> png.bytes }
                })
            } finally { scene.close() }
        }
    }
}
