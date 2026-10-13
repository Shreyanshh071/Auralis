package com.auralis.music.windows

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.*
import com.auralis.music.windows.home.HomeShortcuts
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class SidebarShortcutsTest {
    @Test fun pinsSitBelowNavigationOpenAndAnimateWithoutHover() {
        var bounds = Rect.Zero
        var opened = ""
        var menu = ""
        var collapsed by mutableStateOf(false)
        val pins = listOf(SpeedDialItem("one", "Love Me Not", SpeedDialType.TRACK, isPinned = true),
            SpeedDialItem("two", "Impostor Syndrome", SpeedDialType.ALBUM, isPinned = true))
        val scene = ImageComposeScene(280, 760, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    NavigationSidebar(SidebarPage.HOME, collapsed, { collapsed = !collapsed }, {}, shortcuts = {
                        Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
                            HomeShortcuts(pins, pins, { opened = it.id }, {}, {}, { menu = it.id }, sidebar = true, collapsed = collapsed)
                        }
                    })
                }
            }
        }
        try {
            var frame = 0L
            val dir = File("build/screens/sidebar-shortcuts").apply { mkdirs() }
            fun render(name: String): ByteArray {
                repeat(4) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                dir.resolve("$name.png").writeBytes(bytes)
                return bytes
            }
            fun click(x: Float, y: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x,y), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(x,y), type = PointerType.Mouse)
            }
            val before = render("expanded")
            assertTrue(bounds.top > 300, "Shortcuts must follow New playlist")
            assertTrue(bounds.right <= 280)
            click(bounds.left + 85, bounds.top + 84)
            render("opened")
            assertEquals("one", opened)
            click(bounds.right - 15, bounds.top + 84)
            render("options")
            assertEquals("one", menu)
            scene.sendPointerEvent(PointerEventType.Exit, Offset.Zero, type = PointerType.Mouse)
            frame += 120
            val later = render("flow")
            assertFalse(before.contentEquals(later), "Aura must move even without hover or playback")
            collapsed = true
            render("collapsed")
        } finally { scene.close() }
    }
}
