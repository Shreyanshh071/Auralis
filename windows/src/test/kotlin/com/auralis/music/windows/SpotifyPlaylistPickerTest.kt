package com.auralis.music.windows

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import com.auralis.music.data.network.SpotifyLibrary
import com.auralis.music.ui.screens.SpotifyAccountPlaylistPicker
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class SpotifyPlaylistPickerTest {
    @Test fun importsOnlyCheckedPlaylistsAndDisablesImportDuringRefresh() {
        com.auralis.music.ui.i18n.DesktopStrings.install(java.util.Locale.ENGLISH)
        val playlists = listOf(
            SpotifyLibrary.LibraryPlaylist("private", "My private playlist", "My account • 12 tracks", null),
            SpotifyLibrary.LibraryPlaylist("other", "Another playlist", "My account • 8 tracks", null)
        )
        val selected = mutableStateListOf<String>()
        var loading by mutableStateOf(false)
        var imported = emptyList<SpotifyLibrary.LibraryPlaylist>()
        var refreshes = 0
        val scene = ImageComposeScene(488, 650, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Box(Modifier.width(488.dp).padding(24.dp)) {
                    SpotifyAccountPlaylistPicker(playlists, loading, false, selected, false, null,
                        onRefresh = { refreshes++; loading = true }, onSignInAgain = {}, onBack = {},
                        onImport = { imported = it })
                }
                }
            }
        }
        var frame = 0L
        fun render() { repeat(4) { scene.render(frame++ * 16_666_667L).close() } }
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Mouse)
            render()
        }
        try {
            render()
            val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
            File("build/screens/spotify-playlist-picker").apply { mkdirs() }.resolve("picker.png").writeBytes(bytes)
            click(120f, 200f)
            assertEquals(listOf("private"), selected.toList())
            click(220f, 352f)
            assertEquals(listOf("private"), imported.map { it.id })
            click(60f, 161f)
            assertEquals(setOf("private", "other"), selected.toSet())
            click(220f, 352f)
            assertEquals(2, imported.size)
            imported = emptyList()
            click(150f, 161f)
            assertEquals(1, refreshes)
            click(220f, 384f)
            assertTrue(imported.isEmpty(), "A library refresh must not import stale selections")
        } finally { scene.close() }
    }
}
