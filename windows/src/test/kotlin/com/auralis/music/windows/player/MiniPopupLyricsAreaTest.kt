package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.*
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class MiniPopupLyricsAreaTest {
    @Test fun hidingControlsRevealsTheLowerLyricsAndMovesCreditsWithoutResizingTheScrollViewport() {
        val shown = mutableStateOf(true)
        val list = LazyListState()
        var inset = 0f
        val lyrics = LyricsData(SyncType.PLAIN, List(20) { LyricLine(0,"Fixture lyric number $it") }, provider = LyricsProvider.LRCLIB)
        val scene = ImageComposeScene(340,340,coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalAppearanceSettings provides AppearanceSettings(autoScrollLyrics=false,standardLyricsBlur=false)) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        MiniPopupLyricsArea(shown.value,120.dp) {
                            inset = LocalMiniPopupControlsInset.current!!.value.value
                            SidebarLyricsView(lyrics,remember { mutableStateOf(0L) },{},listState=list)
                        }
                    }
                }
            }
        }
        try {
            var frame=0L
            fun render(count:Int=1) { repeat(count) {scene.render(frame++*16_666_667L).close()} }
            fun tailPixels(name:String):Int = scene.render(frame++*16_666_667L).use { image ->
                val bytes=image.encodeToData(EncodedImageFormat.PNG)!!.use{it.bytes}
                File("build/screens/mini-popup-lyrics").apply{mkdirs()}.resolve("$name.png").writeBytes(bytes)
                val bitmap=ImageIO.read(bytes.inputStream())
                (230..285).sumOf { y -> (8..325).count { x -> (bitmap.getRGB(x,y) and 255)>35 } }
            }
            render(8)
            val height=list.layoutInfo.viewportSize.height
            val covered=tailPixels("controls-shown")
            assertEquals(120f,inset,.1f)
            shown.value=false;render(9)
            assertTrue(inset in 1f..119f,"Credits must travel through intermediate positions")
            assertEquals(height,list.layoutInfo.viewportSize.height,"Control fading must not restart lyric following with a new viewport")
            render(20)
            assertEquals(0f,inset,.1f)
            assertTrue(tailPixels("controls-hidden")>covered+100,"The region previously occupied by controls must show lyrics")
            shown.value=true;render(9)
            assertTrue(inset in 1f..119f,"The reverse transition must be smooth too")
            render(20);assertEquals(120f,inset,.1f)
            assertEquals(height,list.layoutInfo.viewportSize.height)
        } finally {scene.close()}
    }
}
