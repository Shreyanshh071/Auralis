package com.auralis.music.windows.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.domain.model.*
import com.auralis.music.ui.components.LocalOptionsSheetHost
import com.auralis.music.ui.library.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class PlaylistShortcutMenuTest {
    @Test fun localPlaylistsAndSavedAlbumsKeepTheirOpeningIdentity() {
        assertEquals("local:p1", playlistShortcutTarget(Playlist("p1", "Mix")).id)
        val album = SavedAlbum("MPREalbum", "Album", "Artist", null)
        val entry = desktopLibraryItems(emptyList(), listOf(album)).single()
        assertEquals("MPREalbum", playlistShortcutTarget(entry).id)
        assertEquals("Artist", playlistShortcutTarget(entry).author)
    }
    @Test fun menuCanPinAndUnpinTheSamePlaylist() {
        com.auralis.music.ui.i18n.DesktopStrings.install(java.util.Locale.ENGLISH)
        var pinned by mutableStateOf(false)
        var toggles = 0
        var dismisses = 0
        val scene = ImageComposeScene(400, 760, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalOptionsSheetHost provides { _, content ->
                    Box(Modifier.fillMaxSize().background(Color(0xFF22202B))) { content() }
                }, LocalPlaylistShortcutActions provides PlaylistShortcutActions({ pinned }, { pinned = !pinned; toggles++ })) {
                    PlaylistOptionsBottomSheet(Playlist("p1", "Angrezi Hip Hop"), onDismiss = { dismisses++ })
                }
            }
        }
        try {
            var frame = 0L
            fun render(name: String) {
                repeat(4) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                File("build/screens/playlist-pin").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
            }
            fun click() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(170f,185f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(170f,185f), type = PointerType.Mouse)
            }
            render("pin")
            click(); render("unpin")
            assertTrue(pinned)
            click(); render("removed")
            assertFalse(pinned)
            assertEquals(2,toggles)
            assertEquals(2,dismisses)
        } finally { scene.close() }
    }
}
