package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.*
import com.auralis.music.domain.model.Track
import com.auralis.music.desktop.library.DesktopLibraryRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class MiniPopupControlsTest {
    @Test fun controlsStayCenteredAndTheSlimSeekbarRetainsItsFullClickTargetAtSmallAndLargeSizes() {
        val dir = Files.createTempDirectory("popup-control-library").toFile()
        try {
            val library = DesktopLibraryRepository(File(dir,"library.json"))
            for (width in listOf(220,340,720,1280)) {
                val scale = (minOf(width,720) / 340f).coerceIn(.85f,1.25f)
                var toggles = 0; var fraction = .3f; var seekPosition = Offset.Zero; var seekSize = IntSize.Zero
                val scene = ImageComposeScene(width,200, coroutineContext = Dispatchers.Unconfined) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        CompositionLocalProvider(LocalPlayerPlaylists provides library) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF634A3C))) {
                                MiniPopupControls(Track("fixture","Babydoll","Dominic Fike"),scale,true,{}, {}, {toggles++}, {}) {
                                    HoverSlider(fraction, {fraction=it}, thin=2.5.dp,thick=4.dp,hitSize=22.dp,
                                        modifier=Modifier.onGloballyPositioned { seekPosition=it.positionInWindow();seekSize=it.size })
                                }
                            }
                        }
                    }
                }
                try {
                    var frame=0L
                    fun render(save: Boolean=false) {
                        repeat(4) {scene.render(frame++ * 16_666_667L).close()}
                        scene.render(frame++ * 16_666_667L).use { image -> if(save) {
                            val bytes=image.encodeToData(EncodedImageFormat.PNG)!!.use{it.bytes}
                            File("build/screens/popup-responsive-controls").apply{mkdirs()}.resolve("$width.png").writeBytes(bytes)
                        } }
                    }
                    render(true)
                    assertEquals(22,seekSize.height,"The thin visual track must retain a usable pointer target")
                    val center=Offset(width/2f,seekPosition.y - 20 * scale)
                    scene.sendPointerEvent(PointerEventType.Press,center,type=PointerType.Mouse)
                    scene.sendPointerEvent(PointerEventType.Release,center,type=PointerType.Mouse)
                    render()
                    assertEquals(1,toggles,"Play/pause must be centered above the seekbar at width $width")
                    val point=Offset(seekPosition.x+seekSize.width*.25f,seekPosition.y+11)
                    scene.sendPointerEvent(PointerEventType.Press,point,type=PointerType.Mouse)
                    scene.sendPointerEvent(PointerEventType.Release,point,type=PointerType.Mouse)
                    render(); assertEquals(.25f,fraction,.01f)
                } finally {scene.close()}
            }
        } finally {dir.deleteRecursively()}
    }
}
