package com.auralis.music.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.data.network.YouTubeMusicLibrary
import com.auralis.music.ui.screens.ImportPlaylistSelectionRow
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class ImportPlaylistAlignmentTest {
    @Test fun checkboxAndCoverShareCenterAndBothToggleSelection() {
        var selected by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        val scene = ImageComposeScene(440, 320, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Column(Modifier.fillMaxSize().background(Color(0xFF29252F))) {
                        ImportPlaylistSelectionRow(YouTubeMusicLibrary.LibraryPlaylist("LM", "Liked Music", "Auto playlist", null), selected, enabled) { selected = !selected }
                        ImportPlaylistSelectionRow(YouTubeMusicLibrary.LibraryPlaylist("one", "gili gili", "Shreyanshh • 100 tracks", null), false) {}
                        ImportPlaylistSelectionRow(YouTubeMusicLibrary.LibraryPlaylist("two", "A longer playlist title that wraps cleanly onto a second line", "Shreyanshh • 444 tracks", null), true) {}
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render(name: String) {
                repeat(4) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                File("build/screens/import-alignment").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
            }
            fun click(x: Float, y: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x,y), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(x,y), type = PointerType.Mouse)
            }
            render("aligned")
            click(24f,31f)
            render("selected")
            assertTrue(selected)
            click(75f,31f)
            render("unselected")
            assertFalse(selected)
            enabled = false
            render("disabled")
            click(24f,31f)
            assertFalse(selected)
        } finally { scene.close() }
    }
}
