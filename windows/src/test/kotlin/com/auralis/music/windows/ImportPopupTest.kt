package com.auralis.music.windows

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.*
import com.auralis.music.ui.screens.ImportPlaylistsScreen
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class ImportPopupTest {
    @Test fun compactTabsClipboardImportAndCloseWork() {
        com.auralis.music.ui.i18n.DesktopStrings.install(java.util.Locale.ENGLISH)
        var bounds = Rect.Zero
        var closed = 0
        var imported = ""
        var pasted = 0
        val actions = object : PlatformActions by NoPlatformActions {
            override fun readClipboardText(): String { pasted++; return "https://music.youtube.com/playlist?list=PLtest" }
        }
        val scene = ImageComposeScene(800, 700, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                CompositionLocalProvider(LocalPlatformActions provides actions, LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxSize()) {
                        LiquidPopupSurface(Modifier.width(488.dp).align(Alignment.Center)) {
                            Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }.heightIn(max = 652.dp).verticalScroll(ScrollState(0))) {
                                ImportPlaylistsScreen({ closed++ }, {}, {}, false, null, {}, { imported = it }, {},
                                    isImportingSpotify = false, spotifyImportMessage = null, onClearSpotifyImportMessage = {}, compact = true)
                            }
                        }
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render(name: String) {
                repeat(4) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                File("build/screens/import-popup").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
            }
            fun click(x: Float, y: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Mouse)
            }
            render("accounts")
            assertEquals(488f, bounds.width, .1f)
            assertTrue(bounds.height <= 652f)
            click(bounds.left + 352f, bounds.top + 116f)
            render("youtube-link")
            click(bounds.right - 62f, bounds.top + 352f)
            render("pasted")
            assertEquals(1, pasted)
            click(bounds.center.x, bounds.top + 412f)
            render("imported")
            assertEquals("https://music.youtube.com/playlist?list=PLtest", imported)
            click(bounds.left + 215f, bounds.top + 194f)
            render("spotify-link")
            click(bounds.right - 38f, bounds.top + 38f)
            assertEquals(1, closed)
        } finally { scene.close() }
    }
}
