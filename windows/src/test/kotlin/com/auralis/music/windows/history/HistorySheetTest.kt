package com.auralis.music.windows.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import com.auralis.music.domain.model.HistoryEntry
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.LocalArtworkRenderer
import com.auralis.music.ui.components.PlaceholderArtworkRenderer
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class HistorySheetTest {
    @Test
    fun historySongsRenderInShortcutCardStyleAndRespondToActions() {
        val sampleTrack1 = Track(
            id = "t1",
            title = "Love Me Not",
            artist = "Ravyn Lenae",
            thumbnail = "https://example.com/cover1.jpg"
        )
        val sampleTrack2 = Track(
            id = "t2",
            title = "Impostor Syndrome",
            artist = "Sidney Gish",
            thumbnail = "https://example.com/cover2.jpg"
        )
        val historyEntries = listOf(
            HistoryEntry(sampleTrack1, 1000L),
            HistoryEntry(sampleTrack2, 2000L)
        )

        var clickedTrack: Track? = null
        var removedId: String? = null
        var menuTrack: Track? = null

        val data = kotlin.io.path.createTempDirectory("auralis-history-test").toFile()
        val stores = com.auralis.music.desktop.settings.DesktopDataStores(File(data, "settings"))

        val scene = ImageComposeScene(800, 600, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(
                    LocalArtworkRenderer provides PlaceholderArtworkRenderer,
                    com.auralis.music.data.datastore.LocalSettingsStores provides stores
                ) {
                    HistorySheet(
                        history = historyEntries,
                        currentTrack = sampleTrack1,
                        isPlaying = true,
                        onTrackClick = { track, _ -> clickedTrack = track },
                        onRemoveFromHistory = { removedId = it },
                        onClearHistory = {},
                        onDismiss = {},
                        onTrackMenu = { menuTrack = it }
                    )
                }
            }
        }

        try {
            val dir = File("build/screens/history-shortcuts").apply { mkdirs() }
            repeat(4) { scene.render(it * 16_666_667L).close() }
            val bytes = scene.render(100_000_000L).use {
                it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes }
            }
            dir.resolve("history-cards.png").writeBytes(bytes)
            assertTrue(bytes.isNotEmpty(), "History sheet must render successfully")

            // Simulate clicking on the first card row area
            scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 100f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(200f, 100f), type = PointerType.Mouse)
            assertEquals("t1", clickedTrack?.id, "Clicking history card must trigger onTrackClick")
        } finally {
            scene.close()
        }
    }
}
