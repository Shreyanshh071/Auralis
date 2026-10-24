package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.*
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class BackgroundLyricsClippingTest {
    @Test fun overlappingVocalsWrapAtNarrowWidthsAndDrawTheirAnimatedInk() {
        var narrowHeight = 0
        for (width in listOf(320, 840)) {
            val position = mutableLongStateOf(2500)
            val backing = LyricLine(1000, "it's gonna feel so good", isBackground = true,
                words = listOf(LyricWord("it's ", 1000, 500, true), LyricWord("gonna ", 1500, 500, true),
                    LyricWord("feel ", 2000, 1500, true), LyricWord("so ", 3500, 500, true), LyricWord("good", 4000, 1000, true)))
            var backingHeight = 0
            val scene = ImageComposeScene(width, 520, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme { CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(lyricsAnimation = "Spicy Lyrics"),
                    LocalLyricsUi provides LyricsUiPlatform(strings = DefaultLyricsUi.strings, roundedMouseHover = true)) {
                    Column(Modifier.fillMaxSize().background(Color(0xFF39105B)).padding(12.dp)) {
                        for ((index, line) in listOf(LyricLine(1000, "Let it happen, let it happen"), backing).withIndex()) {
                            ExperimentalLyricsLine(index, line, true, true, true, false, false, position,
                                44f, 1.3f, Color.White, "Left", true, 0, SyncType.RICHSYNC,
                                onSizeChanged = { if (line.isBackground) backingHeight = it }, onClick = {}, onLongClick = {})
                        }
                    }
                } }
            }
            try {
                var frame = 0L
                fun render() = scene.render(frame++ * 16_666_667L)
                repeat(30) { render().close() }
                assertTrue(backingHeight >= 28, "Backing text must have a measured row: $backingHeight")
                if (width == 320) narrowHeight = backingHeight
                else assertTrue(narrowHeight > backingHeight, "Wrapped vocals must reserve another text row")
                val folder = File("build/screens/background-lyrics").apply { mkdirs() }
                for (time in listOf(2500L, 4500L, 5500L)) {
                    position.longValue = time
                    repeat(8) { render().close() }
                    File(folder, "backing-$width-$time.png").writeBytes(render().use {
                        it.encodeToData(EncodedImageFormat.PNG)!!.use { png -> png.bytes }
                    })
                }
                assertEquals(2000L, backing.words!![2].time)
            } finally { scene.close() }
        }
    }
}
