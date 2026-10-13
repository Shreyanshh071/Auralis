package com.auralis.music.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class GlassDropdownMenuTest {
    @Test fun menuOpensAboveBottomBarAndRemainsInsideWindow() {
        assertEquals(IntOffset(610,550),glassDropdownPosition(IntRect(850,710,890,750),IntSize(900,800),IntSize(280,160),8))
        assertEquals(IntOffset(8,40),glassDropdownPosition(IntRect(4,8,36,40),IntSize(900,800),IntSize(280,160),8))
    }
    @Test fun dropdownBlursActualBackdropAndReleasesCaptureWhenDismissed() {
        var expanded by mutableStateOf(true)
        lateinit var source: PopupGlassSource
        val scene = ImageComposeScene(900,650,coroutineContext = Dispatchers.Unconfined) {
            source = rememberPopupGlassSource()
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPopupGlassSource provides source) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().recordPopupGlass(source)) {
                            for(x in 0..900 step 8) drawRect(if(x/8%2==0) Color(0xFFDE2945) else Color(0xFF145A82),Offset(x.toFloat(),0f),Size(8f,size.height))
                        }
                        Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(32.dp)) {
                            GlassDropdownMenu(expanded,{ expanded = false }) {
                                Text("Sound output",Modifier.padding(horizontal=16.dp,vertical=8.dp))
                                DropdownMenuItem(text={ Text("Windows default") },onClick={ expanded=false })
                                DropdownMenuItem(text={ Text("Speakers (Realtek Audio)") },onClick={ expanded=false })
                            }
                        }
                    }
                }
            }
        }
        try {
            var frame=0L
            repeat(6) { scene.render(frame++*16_666_667L).close() }
            assertEquals(1,source.users.intValue)
            val bytes=scene.render(frame++*16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
            File("build/screens/glass-dropdowns").apply { mkdirs() }.resolve("sound-output.png").writeBytes(bytes)
            val image=ImageIO.read(bytes.inputStream())
            fun variation(y:Int)=(620 until 680).sumOf { x->kotlin.math.abs((image.getRGB(x,y) shr 16 and 255)-(image.getRGB(x+1,y) shr 16 and 255)) }
            assertTrue(variation(510)<variation(100)/3,"The dropdown must blur the page behind it")
            scene.sendPointerEvent(PointerEventType.Press,Offset(20f,20f),type=PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release,Offset(20f,20f),type=PointerType.Mouse)
            repeat(4) { scene.render(frame++*16_666_667L).close() }
            assertFalse(expanded)
            assertEquals(0,source.users.intValue)
        } finally { scene.close() }
    }
}
