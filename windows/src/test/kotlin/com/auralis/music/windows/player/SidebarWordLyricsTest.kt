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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.ui.theme.LocalAppearanceSettings

class SidebarWordLyricsTest {
    @Test fun spicySidebarDimsEndedPhrasesAndRestoresTheirStateOnSeek() {
        val position = mutableLongStateOf(1500L)
        val data = com.auralis.music.domain.model.LyricsData(SyncType.RICHSYNC, listOf(
            LyricLine(1000, "AAAA", words = listOf(LyricWord("AAAA", 1000, 800))),
            // Keep the breath below the interlude threshold: this test samples
            // lyric rows, while dots have their own layout/visibility checks.
            LyricLine(4000, "BBBB", words = listOf(LyricWord("BBBB", 4000, 1000)))),
            provider = com.auralis.music.domain.model.LyricsProvider.AMLL)
        val scene = ImageComposeScene(360, 220, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(
                    lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName,
                    lyricsTextPosition = "Left", standardLyricsBlur = false, autoScrollLyrics = false)) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) { SidebarLyricsView(data, position, {}) }
                }
            }
        }
        try {
            var frame = 0L
            val folder = java.io.File("build/screens/sidebar-highlight").apply { mkdirs() }
            fun capture(time: Long, name: String): BufferedImage {
                position.longValue = time
                repeat(24) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use {
                    it.encodeToData(EncodedImageFormat.PNG)!!.use { encoded -> encoded.bytes }
                }
                java.io.File(folder, "$name.png").writeBytes(bytes)
                return ImageIO.read(bytes.inputStream())
            }
            fun brightest(image: BufferedImage, top: Int, bottom: Int) =
                (top until bottom).maxOf { y -> (20 until 120).maxOf { x -> image.getRGB(x, y) and 255 } }
            capture(1500, "singing")
            val rest = capture(3000, "between-phrases")
            assertTrue(brightest(rest, 12, 43) in 80..130, "Completed phrase dims to Spicy's sung state during the breath")
            assertTrue(brightest(rest, 50, 80) < 150, "Next phrase must remain dim before its start")
            val next = capture(4800, "next-phrase")
            assertTrue(brightest(next, 12, 43) < 150, "Previous phrase dims when the next starts")
            assertTrue(brightest(next, 50, 80) > 200, "Next phrase takes highlight ownership")
            val back = capture(3000, "seek-back")
            assertTrue(brightest(back, 12, 43) in 80..130, "Seeking back restores the previous phrase's sung state")
            assertTrue(brightest(back, 50, 80) < 150, "Seeking back clears the future phrase highlight")
        } finally { scene.close() }
    }

    @Test fun duetSingersKeepToTheirSidesAsInTheFullPlayer() {
        val position = mutableLongStateOf(1500L)
        val data = com.auralis.music.domain.model.LyricsData(SyncType.RICHSYNC, listOf(
            LyricLine(1000, "AAAA", words = listOf(LyricWord("AAAA", 1000, 800)), agent = "v1"),
            LyricLine(5000, "BBBB", words = listOf(LyricWord("BBBB", 5000, 1000)), agent = "v2")),
            provider = com.auralis.music.domain.model.LyricsProvider.AMLL)
        val scene = ImageComposeScene(360, 220, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                // The regular position is Left: the second singer's line must still go right.
                CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(
                    lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName,
                    lyricsTextPosition = "Left", standardLyricsBlur = false, autoScrollLyrics = false)) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) { SidebarLyricsView(data, position, {}) }
                }
            }
        }
        try {
            repeat(24) { scene.render(it * 16_666_667L).close() }
            val bytes = scene.render(25 * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { e -> e.bytes } }
            java.io.File("build/screens/sidebar-duet").apply { mkdirs() }.resolve("duet.png").writeBytes(bytes)
            val image = ImageIO.read(bytes.inputStream())
            fun inked(top: Int, bottom: Int) = (0 until image.width).filter { x -> (top until bottom).any { y -> (image.getRGB(x, y) and 255) > 40 } }
            val first = inked(12, 43)
            val second = inked(50, 80)
            assertTrue(first.isNotEmpty() && second.isNotEmpty())
            assertTrue(first.first() < 60, "The first singer's line hugs the left (starts at x=${first.first()})")
            assertTrue(second.last() > 300 && second.first() > 180, "The second singer's line hugs the right (x=${second.first()}..${second.last()})")
        } finally { scene.close() }
    }

    @Test fun compactCardFollowsLiveStoreEvenWhenParentHasDefaultStyle() {
        val settings = kotlinx.coroutines.flow.MutableStateFlow(AppearanceSettings())
        val store = object : com.auralis.music.ui.theme.AppearanceSettingsStore {
            override fun getInitialSettings() = settings.value
            override val settingsFlow = settings
            override suspend fun updateSettings(value: AppearanceSettings) { settings.value = value }
            override suspend fun hideMostPlayedPlaylist(playlistId: String) {}
        }
        val position = mutableLongStateOf(1600L)
        val data = com.auralis.music.domain.model.LyricsData(SyncType.RICHSYNC, listOf(
            LyricLine(1000, "Sometimes she holds", words = listOf(LyricWord("Sometimes ", 1000, 1000), LyricWord("she holds", 3000, 1000))),
            LyricLine(5000, "her hand too tight", words = listOf(LyricWord("her hand ", 5000, 1000), LyricWord("too tight", 6500, 1000)))),
            provider = com.auralis.music.domain.model.LyricsProvider.AMLL)
        val scene = ImageComposeScene(340, 240, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(com.auralis.music.ui.theme.LocalAppearanceSettingsStore provides store,
                    LocalAppearanceSettings provides AppearanceSettings()) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) { SidebarLyricsView(data, position, {}) }
                }
            }
        }
        try {
            var frame = 0L
            val output = java.io.File("build/screens/compact-card-styles").apply { mkdirs() }
            val images = listOf(LyricsAnimationMode.SPICY_LYRICS, LyricsAnimationMode.METRO_LYRICS, LyricsAnimationMode.FLUID, LyricsAnimationMode.APPLE_MUSIC_V2).map { mode ->
                settings.value = AppearanceSettings(lyricsAnimation = mode.displayName)
                repeat(30) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                java.io.File(output, "${mode.name}.png").writeBytes(bytes)
                bytes
            }
            for (i in images.indices) for (j in i + 1 until images.size)
                assertFalse(images[i].contentEquals(images[j]), "The whole compact card must follow live store style changes")
        } finally { scene.close() }
    }

    @Test fun sidebarChangesRendererWhenTheSelectedStyleChanges() {
        val mode = mutableStateOf(LyricsAnimationMode.METRO_LYRICS)
        val position = mutableLongStateOf(1600L)
        val line = LyricLine(1000, "AAAA BBBB", words = listOf(
            LyricWord("AAAA ", 1000, 1000), LyricWord("BBBB", 3000, 1000)))
        val scene = ImageComposeScene(320, 90, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(lyricsAnimation = mode.value.displayName)) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        SidebarLyricLine(line.text, true, true, {}, line = line, position = position, syncType = SyncType.RICHSYNC)
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render(): ByteArray = scene.render(frame++ * 16_666_667L)
                .encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
            fun settled(): ByteArray { repeat(4) { render() }; return render() }
            val output = java.io.File("build/screens/sidebar-styles").apply { mkdirs() }
            val previews = listOf(LyricsAnimationMode.SPICY_LYRICS, LyricsAnimationMode.METRO_LYRICS, LyricsAnimationMode.FLUID, LyricsAnimationMode.APPLE_MUSIC_V2).map { selected ->
                mode.value = selected
                position.longValue = 1600L
                val first = settled()
                java.io.File(output, "${selected.name}.png").writeBytes(first)
                position.longValue = 3800L
                assertFalse(first.contentEquals(settled()), "$selected must follow the playback clock")
                position.longValue = 1600L
                assertContentEquals(first, settled(), "$selected must reset on seek")
                first
            }
            for (i in previews.indices) for (j in i + 1 until previews.size)
                assertFalse(previews[i].contentEquals(previews[j]), "Changing styles must select a different renderer without reopening the panel")
        } finally { scene.close() }
    }

    @Test fun sidebarUsesFullPlayerWordSweepAndDoesNotLightFutureWordsDuringARest() {
        val position = mutableLongStateOf(500L)
        val line = LyricLine(1000, "AAAA BBBB", words = listOf(
            LyricWord("AAAA ", 1000, 1000), LyricWord("BBBB", 3000, 1000)))
        val scene = ImageComposeScene(320, 80, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(lyricsTextPosition = "Left")) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    SidebarLyricLine(line.text, true, true, {}, line = line, position = position, syncType = SyncType.RICHSYNC)
                }
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
            repeat(3) { render() } // Flush the scene's snapshot/draw invalidations after changing the clock.
            val rest = render()
            val images = java.io.File("build/screens/sidebar-words").apply { mkdirs() }
            ImageIO.write(before, "png", java.io.File(images, "before.png"))
            ImageIO.write(rest, "png", java.io.File(images, "rest.png"))
            assertTrue(brightness(rest, 10, 80) > brightness(before, 10, 80), "Sung word must brighten")
            assertEquals(brightness(before, 115, 180), brightness(rest, 115, 180), "Future word must stay dim during a rest")
            val paused = render()
            assertEquals(brightness(rest, 10, 180), brightness(paused, 10, 180), "Paused playback must freeze word progress")
            position.longValue = 4500
            repeat(3) { render() }
            val completed = render()
            assertTrue(brightness(completed, 115, 180) > brightness(rest, 115, 180), "Next word must brighten at its own timestamp")
            position.longValue = 500
            repeat(3) { render() }
            val seekBack = render()
            assertEquals(brightness(before, 10, 180), brightness(seekBack, 10, 180), "Seeking back must reset the word sweep")
        } finally { scene.close() }
    }
}
