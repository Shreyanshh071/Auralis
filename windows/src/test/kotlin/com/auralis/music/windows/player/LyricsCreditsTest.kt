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
            LyricsCredit("Made by maker", "https://spicylyrics.org/uid/456"))
        val data = LyricsData(SyncType.LINE_SYNC, listOf(LyricLine(0, "Test")),
            provider = LyricsProvider.SPICY_LYRICS, credits = credits)
        val displayed = com.auralis.music.ui.lyrics.lyricsCreditsForDisplay(data)
        assertEquals("Lyrics from Spicy Lyrics", displayed.first().label)
        assertEquals(credits.map { it.url }, displayed.map { it.url })
        assertEquals(credits.drop(1), displayed.drop(1))
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
