package com.auralis.music.windows.wrapped

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import com.auralis.music.domain.model.SongStat
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class WrappedRenderTest {
    @Test fun phoneStoryPagesRenderWithDesktopFontAndBackground() {
        val state = WrappedState(year = 2026, isLoading = false, totalMinutes = 1058,
            topSongs = listOf(SongStat(Track("test", "The Less I Know The Better", "Tame Impala"), 21, 2_600_000)))
        val output = File("build/screens/wrapped").apply { mkdirs() }
        for (page in listOf("intro", "top-five", "minutes", "empty")) {
            val scene = ImageComposeScene(1000, 740, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    when (page) {
                        "intro" -> WrappedScreen(state, {}, { _, _, _, _ -> })
                        "top-five" -> WrappedBackground { WrappedTopFivePage("Your top songs of the year",
                            listOf(RankedRow("The Less I Know The Better", "Tame Impala", null), RankedRow("Impostor Syndrome", "Sidney Gish", null)),
                            androidx.compose.foundation.shape.RoundedCornerShape(4), true) { FloatingShapes() } }
                        "minutes" -> WrappedBackground { WrappedMinutesPage(WrappedMessages.pick(1058), 1058, true) }
                        else -> WrappedScreen(WrappedState(year = 2026, isLoading = false), {}, { _, _, _, _ -> })
                    }
                }
            }
            try {
                repeat(12) { scene.render(it * 250_000_000L).close() }
                val bytes = scene.render(3_100_000_000L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                assertTrue(bytes.size > 5000)
                File(output, "$page.png").writeBytes(bytes)
            } finally { scene.close() }
        }
    }
}
