package com.auralis.music.windows.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.domain.model.AppearanceSettings
import com.kyant.backdrop.backdrops.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import javax.imageio.ImageIO
import kotlin.test.*

class ResumedPlayerUiTest {
    @Test fun resyncGlassIncludesMovingForegroundOverAnUnchangedBackgroundWithoutSelfReflection() {
        val stripeUnderButton = mutableStateOf(true)
        val scene = ImageComposeScene(300, 140, coroutineContext = Dispatchers.Unconfined) {
            val background = rememberLayerBackdrop()
            val foreground = rememberLayerBackdrop()
            val combined = rememberCombinedBackdrop(background, foreground)
            val source = remember(combined, foreground) { ResyncBackdrop(combined, foreground) }
            MaterialTheme {
                CompositionLocalProvider(LocalResyncBackdrop provides source) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().layerBackdrop(background)) { drawRect(Color(0xFF960808)) }
                        // The same platform hook installed on ExperimentalLyricsView's LazyColumn.
                        Canvas(Modifier.fillMaxSize().then(DesktopLyricsUi.resyncBackdropModifier())) {
                            drawRect(Color.White, Offset(if (stripeUnderButton.value) 94f else 250f, 0f), Size(22f, size.height))
                        }
                        GlassResyncButton("Re-sync", {}, Modifier.align(Alignment.Center))
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun capture(name: String): java.awt.image.BufferedImage {
                repeat(8) { scene.render(frame++ * 16_666_667L).close() }
                val png = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                java.io.File("build/screens/resync-live-content").apply { mkdirs() }.resolve("$name.png").writeBytes(png)
                return ImageIO.read(png.inputStream())
            }
            val foregroundUnderGlass = capture("foreground-under-glass")
            stripeUnderButton.value = false
            val plainBackground = capture("foreground-moved-away")
            val changes = (52..87).sumOf { y -> (98..126).count { x -> foregroundUnderGlass.getRGB(x,y) != plainBackground.getRGB(x,y) } }
            assertTrue(changes > 200, "Scrolling foreground must refract through glass even when background colours stay unchanged: $changes pixels")
            val settled = capture("settled")
            assertTrue((55..85).all { y -> (100..200).all { x -> settled.getRGB(x,y) == plainBackground.getRGB(x,y) } },
                "Repeated drawing must not accumulate the button's own icon/text in the backdrop")
        } finally { scene.close() }
    }

    @Test fun glassResyncSamplesLiveColoursAndRemainsClickable() {
        val blue = mutableStateOf(false)
        var clicks = 0
        val scene = ImageComposeScene(300, 140, coroutineContext = Dispatchers.Unconfined) {
            val backdrop = rememberLayerBackdrop()
            val source = remember(backdrop) { ResyncBackdrop(backdrop) }
            MaterialTheme {
                CompositionLocalProvider(LocalResyncBackdrop provides source) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                            for (x in 0..300 step 6) drawRect(if ((x / 6) % 2 == 0) Color.White
                                else if (blue.value) Color.Blue else Color.Red,
                                Offset(x.toFloat(), 0f), Size(6f, size.height))
                        }
                        GlassResyncButton("Re-sync", { clicks++ }, Modifier.align(Alignment.Center))
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun image(): java.awt.image.BufferedImage {
                repeat(4) { scene.render(frame++ * 16_666_667L).close() }
                val png = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                java.io.File("build/screens").mkdirs()
                java.io.File("build/screens/resync-glass-${blue.value}.png").writeBytes(png)
                return ImageIO.read(png.inputStream())
            }
            val red = image()
            blue.value = true
            val changed = image()
            assertTrue((110..190).any { red.getRGB(it, 57) != changed.getRGB(it, 57) }, "Glass must follow live backdrop colours")
            scene.sendPointerEvent(PointerEventType.Press, Offset(150f, 70f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(150f, 70f), type = PointerType.Mouse)
            assertEquals(1, clicks)
        } finally { scene.close() }
    }

    @Test fun popupHidesSidebarSmoothlyAndMainPlayerRestoresItOnReturn() {
        val main = mutableStateOf(false)
        var extraY = 0f
        val scene = ImageComposeScene(340, 1000, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(dynamicTheme = false)) {
                    NowPlayingSidebarContent(Track("fixture", "Song", "Artist"), {}, Modifier.fillMaxSize(),
                        mainPlayerOpen = main.value,
                        extraCards = { modifier -> Box(modifier.height(30.dp).onGloballyPositioned { extraY = it.positionInRoot().y }) }) {
                        Text("Current lyric")
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render() { scene.render(frame++ * 16_666_667L).close() }
            repeat(5) { render() }
            val expanded = extraY
            assertTrue(expanded > 600)
            val button = Offset(228f, expanded - 332f + 24f)
            scene.sendPointerEvent(PointerEventType.Press, button, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, button, type = PointerType.Mouse)
            val closing = List(24) { render(); extraY }
            assertTrue(closing.distinct().size > 5, "Sidebar must animate away")
            assertTrue(extraY < expanded - 300, "Popup replaces the sidebar lyrics card")
            main.value = true
            repeat(25) { render() }
            main.value = false
            repeat(25) { render() }
            assertEquals(expanded, extraY, 1f, "Returning from main player restores the sidebar without reopening the popup")
        } finally { scene.close() }
    }
}
