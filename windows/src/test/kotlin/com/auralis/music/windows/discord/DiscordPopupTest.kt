package com.auralis.music.windows.discord

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.domain.model.Track
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class DiscordPopupTest {
    @Test fun popupShowsRealCardSettingsAndToggleChangesAreInteractive() {
        for (width in listOf(960,640)) {
            var cfg by mutableStateOf(DiscordCardSettings(enabled=true))
            var closes=0
            val now=DiscordNow(DiscordTrack(Track("preview", "The current song", "The current artist",album="The current album")),true,true,30000,210000)
            val scene=ImageComposeScene(width,720) {
                MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFC9A7FF))) {
                    Surface(Modifier.fillMaxSize()) {
                        DiscordSettingsContent(cfg,now,"Connected to Discord desktop",{cfg=it(cfg)}, {cfg=DiscordCardSettings(enabled=cfg.enabled)}, {closes++})
                    }
                }
            }
            try {
                fun render(time:Long) = scene.render(time)
                repeat(3) { render(it*16_666_667L).close() }
                render(50_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    File("build/screens/discord-popup-$width.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                } }
                fun click(x:Float,y:Float) {
                    scene.sendPointerEvent(PointerEventType.Press,Offset(x,y),type=PointerType.Mouse)
                    scene.sendPointerEvent(PointerEventType.Release,Offset(x,y),type=PointerType.Mouse)
                }
                click(100f,130f)
                render(70_000_000L).close()
                assertFalse(cfg.enabled,"Enable switch changes the saved setting callback")
                click(width-44f,44f)
                render(90_000_000L).close()
                assertEquals(1,closes)
            } finally {scene.close()}
        }
    }
}
