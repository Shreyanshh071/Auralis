package com.auralis.music.windows.together

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.auralis.music.windows.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class ListenTogetherPopupTest {
    @Test fun compactGlassLayoutKeepsHostAndRoomActionsUsable() {
        val state = mutableStateOf(DesktopTogether.State())
        var bounds = Rect.Zero
        var created = ""
        var closes = 0
        var leaves = 0
        val scroll = ScrollState(0)
        val scene = ImageComposeScene(800, 640, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                // Headless Skia has no GPU: exercise the surface's supported fallback
                // while verifying scrolling and hit targets instead of software blur.
                CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                            listOf(Color(0xFF164942), Color(0xFF582B45), Color(0xFF28213D)))))
                        LiquidPopupSurface(Modifier.width(488.dp).align(Alignment.Center)) {
                            Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }
                                .heightIn(max = 592.dp).verticalScroll(scroll)) {
                                ListenTogetherContent(state.value, { closes++ }, { created = it }, onLeave = { leaves++ },
                                    defaultName = "Shreyanshh")
                            }
                        }
                    }
                }
            }
        }
        try {
            var frame = 0L
            val output = File("build/screens/together-popup").apply { mkdirs() }
            fun render(name: String) {
                repeat(4) { scene.render(frame++ * 16_666_667L).close() }
                val bytes = scene.render(frame++ * 16_666_667L).use {
                    it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                }
                File(output, "$name.png").writeBytes(bytes)
            }
            fun click(x: Float, y: Float) {
                val p = Offset(x, y)
                scene.sendPointerEvent(PointerEventType.Press, p, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, p, type = PointerType.Mouse)
            }
            render("join")
            assertEquals(488f, bounds.width, .1f)
            assertTrue(bounds.height < 460f, "The entry view is a compact popup")
            click(bounds.left + 354f, bounds.top + 104f)
            render("host")
            assertTrue(bounds.height <= 592f, "Room rules stay inside the scrollable popup")
            assertTrue(scroll.maxValue > 0, "All four host rules can be reached by scrolling")
            kotlinx.coroutines.runBlocking { scroll.scrollTo(scroll.maxValue) }
            render("host-bottom")
            click(bounds.center.x, bounds.bottom - 44f)
            assertEquals("Shreyanshh", created)
            state.value = DesktopTogether.State(code = "ABC123", host = true, members = listOf("You", "Friend"), track = "A song")
            render("connected")
            kotlinx.coroutines.runBlocking { scroll.scrollTo(scroll.maxValue) }
            render("connected-bottom")
            click(bounds.center.x, bounds.bottom - 44f)
            assertEquals(1, leaves)
            kotlinx.coroutines.runBlocking { scroll.scrollTo(0) }
            render("connected-top")
            click(bounds.right - 38f, bounds.top + 38f)
            assertEquals(1, closes)
        } finally { scene.close() }
    }
}
