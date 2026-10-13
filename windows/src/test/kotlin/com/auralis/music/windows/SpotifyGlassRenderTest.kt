package com.auralis.music.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.windows.profile.BrowserYouTubeLogin
import com.auralis.music.windows.profile.DesktopSpotifySignIn
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class SpotifyGlassRenderTest {
    @Test fun spotifyDialogCapturesBackdropAndReleasesItOnDismiss() {
        var opened by mutableStateOf(true)
        lateinit var source: PopupGlassSource
        val profiles = listOf("Firefox", "Zen").map { BrowserYouTubeLogin.Profile(it, "Your profile", File("unused-preview.sqlite"), true) }
        val scene = ImageComposeScene(900, 800, coroutineContext = Dispatchers.Unconfined) {
            source = rememberPopupGlassSource()
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPopupGlassSource provides source) {
                    Canvas(Modifier.fillMaxSize().recordPopupGlass(source)) {
                        for (x in 0..900 step 12) drawRect(if (x / 12 % 2 == 0) Color(0xFF934F72) else Color(0xFF194F82), Offset(x.toFloat(), 0f), Size(12f, size.height))
                    }
                    if (opened) DesktopSpotifySignIn({}, { opened = false }, profiles)
                }
            }
        }
        try {
            var frame = 0L
            fun render() {
                repeat(4) { scene.render(frame++ * 16_666_667L).close() }
            }
            render()
            assertEquals(1, source.users.intValue, "Spotify sign-in must use the real glass capture")
            val bytes = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
            File("build/screens/import-hover").apply { mkdirs() }.resolve("spotify-glass.png").writeBytes(bytes)
            scene.sendPointerEvent(PointerEventType.Press, Offset(30f, 30f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(30f, 30f), type = PointerType.Mouse)
            render()
            assertFalse(opened)
            assertEquals(0, source.users.intValue)
        } finally { scene.close() }
    }
}
