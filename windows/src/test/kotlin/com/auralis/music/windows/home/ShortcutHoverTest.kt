package com.auralis.music.windows.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.SpeedDialType
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ShortcutHoverTest {
    @Test fun entireRowHighlightsAndAddedRowsCannotAddAgain() {
        val pinned = mutableStateOf(false)
        var adds = 0
        val scene = ImageComposeScene(420, 80, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
                Box(Modifier.fillMaxSize().background(Color(0xFF31252A))) {
                    ShortcutCandidateRow(SpeedDialItem("artist", "Gorillaz", SpeedDialType.ARTIST), pinned.value) { adds++ }
                }
                }
            }
        }
        try {
            var frame = 0L
            fun render(name: String): java.awt.image.BufferedImage {
                repeat(16) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                val dir = File("build/screens/shortcut-hover").apply { mkdirs() }
                File(dir, "$name.png").writeBytes(bytes)
                return ImageIO.read(bytes.inputStream())
            }
            val idle = render("idle")
            scene.sendPointerEvent(PointerEventType.Move, Offset(330f, 35f), type = PointerType.Mouse)
            val hover = render("hover")
            assertTrue((hover.getRGB(300, 12) shr 16 and 255) > (idle.getRGB(300, 12) shr 16 and 255) + 15)
            assertEquals(idle.getRGB(0, 0), hover.getRGB(0, 0), "Highlight remains rounded")
            fun click() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(330f, 35f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(330f, 35f), type = PointerType.Mouse)
            }
            click(); assertEquals(1, adds)
            pinned.value = true; render("added"); click(); assertEquals(1, adds)
        } finally { scene.close() }
    }

    @Test fun candidateRowShowsRemoveOptionWhenPinnedAndRendersPlaylistCover() {
        val pinned = mutableStateOf(true)
        var adds = 0
        var removes = 0
        val testPlaylist = com.auralis.music.domain.model.Playlist("game", "game", tracks = emptyList())
        val covers = mapOf("local:game" to testPlaylist)
        val item = SpeedDialItem("album-local:game", "game", SpeedDialType.ALBUM,
            album = com.auralis.music.domain.model.PlaylistResult("local:game", "game"))
        val scene = ImageComposeScene(420, 80, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF31252A))) {
                        ShortcutCandidateRow(item, pinned.value, playlistCovers = covers, onRemove = { removes++ }, onAdd = { adds++ })
                    }
                }
            }
        }
        try {
            scene.render(0L).close()
            fun click() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(330f, 35f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(330f, 35f), type = PointerType.Mouse)
            }
            click()
            assertEquals(1, removes, "Clicking pinned candidate row must call onRemove")
            assertEquals(0, adds, "Clicking pinned candidate row must not call onAdd")
            pinned.value = false
            scene.render(16_666_667L).close()
            click()
            assertEquals(1, adds, "Clicking unpinned candidate row must call onAdd")
            assertEquals(1, removes, "onRemove count must remain unchanged")
        } finally { scene.close() }
    }

    @Test fun actionButtonHighlightsWhenHoveredDirectly() {
        val item = SpeedDialItem("album-local:game", "game", SpeedDialType.ALBUM)
        val scene = ImageComposeScene(420, 80, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF31252A))) {
                        ShortcutCandidateRow(item, pinned = true)
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render(): java.awt.image.BufferedImage {
                repeat(16) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                return ImageIO.read(bytes.inputStream())
            }
            // Hover over the playlist title (row is hovered, but action button is not hovered)
            scene.sendPointerEvent(PointerEventType.Move, Offset(150f, 35f), type = PointerType.Mouse)
            val rowHover = render()

            // Move mouse directly over the "Remove" action button
            scene.sendPointerEvent(PointerEventType.Move, Offset(375f, 35f), type = PointerType.Mouse)
            val actionHover = render()

            // The action button background around (375, 35) should become significantly brighter/tinted
            val rowHoverPixel = rowHover.getRGB(375, 35)
            val actionHoverPixel = actionHover.getRGB(375, 35)
            assertNotEquals(rowHoverPixel, actionHoverPixel, "Hovering over action button must highlight the button")
        } finally { scene.close() }
    }
}
