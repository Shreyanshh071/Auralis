package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.data.network.SpicyRecording
import com.auralis.music.data.parser.SpicyLyricsParser
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.*
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.*

class SpicyPresentationTest {
    @Test fun lineSyncedSongsHighlightWithoutEndTimestamps() {
        val lines = listOf(LyricLine(35670, "First"), LyricLine(39660, "Second"), LyricLine(46740, "Last"))
        assertTrue(SpicyLyricsPresentation.activeIndices(lines, 35669).isEmpty())
        assertEquals(setOf(0), SpicyLyricsPresentation.activeIndices(lines, 35670))
        assertEquals(setOf(0), SpicyLyricsPresentation.activeIndices(lines, 39659))
        assertEquals(setOf(1), SpicyLyricsPresentation.activeIndices(lines, 39660))
        assertEquals(1, SpicyLyricsPresentation.scrollAnchor(lines, 40000, 0))
        assertEquals(setOf(2), SpicyLyricsPresentation.activeIndices(lines, 50000))
        assertEquals(setOf(0), SpicyLyricsPresentation.activeIndices(lines, 36000), "Seeking restores the earlier line")
        val timed = listOf(LyricLine(0, "Timed", words = listOf(LyricWord("Timed", 0, 200))), LyricLine(1000, "Next"))
        assertTrue(SpicyLyricsPresentation.activeIndices(timed, 500).isEmpty(), "Keep genuine timed rests")
    }

    @Test fun neighbouringLinesStayReadableAndBackingVocalsDoNotStealTheAnchor() {
        assertEquals(.625f, SpicyLyricsPresentation.blurSigmaPx(4, 3))
        assertEquals(3.415625f, SpicyLyricsPresentation.blurSigmaPx(50, 3))
        assertEquals(0f, SpicyLyricsPresentation.blurSigmaPx(0, -1))
        val lines = listOf(LyricLine(0, "Lead"), LyricLine(100, "Backing", isBackground = true),
            LyricLine(200, "Harmony", isBackground = true), LyricLine(500, "Next lead"))
        assertEquals(0, SpicyLyricsPresentation.leadIndex(lines, 2))
        assertEquals(3, SpicyLyricsPresentation.leadIndex(lines, 3))
        val overlap = listOf(LyricLine(0, "Lead", endTime = 300),
            LyricLine(100, "Long backing tail", isBackground = true, endTime = 900),
            LyricLine(400, "Next lead", endTime = 800), LyricLine(1000, "Following lead", endTime = 1200))
        assertEquals(2, SpicyLyricsPresentation.scrollAnchor(overlap, 500, 1), "Old background tail must not pull the anchor backward")
        assertEquals(0, SpicyLyricsPresentation.scrollAnchor(overlap, 200, 1))
        assertEquals(setOf(1, 2), SpicyLyricsPresentation.activeIndices(overlap, 500))
        assertTrue(SpicyLyricsPresentation.activeIndices(overlap, 950).isEmpty())
    }

    /** Optional developer visual check. Response is local, timestamped, never a shipped fixture. */
    @Test fun renderTheActualCommunityResponseAtReferenceVideoPositions() {
        val input = File("../scratch/let-it-happen-reference.json")
        assumeTrue("Fetch a local API reference to run this visual comparison", input.exists())
        val envelope = JSONObject(input.readText())
        assumeTrue("Discard references after 30 days", System.currentTimeMillis() - envelope.getLong("fetchedAtMs") < 30L * 86400000)
        val lyrics = SpicyLyricsParser.parse(envelope.getJSONObject("response"),
            SpicyRecording("2X485T9Z5Ly0xyaghN73ed", "Let It Happen", "Tame Impala", 467000))!!
        val position = mutableLongStateOf(44000)
        val scene = ImageComposeScene(1000, 900, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(
                lyricsAnimation = "Spicy Lyrics", lyricsTextSize = 30f, lyricsTextPosition = "Left"),
                LocalLyricsUi provides LyricsUiPlatform(strings = DefaultLyricsUi.strings, roundedMouseHover = true)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF3D2356)).padding(20.dp)) {
                    ExperimentalLyricsView(lyrics, position, {}, isPlaying = false)
                }
            } }
        }
        try {
            var frame = 0L
            val output = File("build/screens/spicy-reference").apply { mkdirs() }
            for (time in listOf(44000L, 47000L, 51000L)) {
                position.longValue = time
                repeat(90) { scene.render(frame++ * 16_666_667L).close() }
                File(output, "community-$time.png").writeBytes(scene.render(frame++ * 16_666_667L).use {
                    it.encodeToData(EncodedImageFormat.PNG)!!.use { png -> png.bytes }
                })
            }
            assertTrue(lyrics.credits.size >= 2)
        } finally { scene.close() }
    }
}
