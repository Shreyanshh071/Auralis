package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.domain.model.*
import com.auralis.music.ui.theme.DesktopTypography
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class PlayerPlaylistPickerTest {
    @Test fun artworkRowsUseLibraryTypographyAndKeepCreateAndAddActions() {
        val dir=Files.createTempDirectory("picker-artwork").toFile()
        val cover=java.awt.image.BufferedImage(44,44,java.awt.image.BufferedImage.TYPE_INT_RGB)
        for(y in 0 until 44) for(x in 0 until 44) cover.setRGB(x,y,if(x<22) 0xDD7925 else 0x176D83)
        val file=File(dir,"cover.png"); ImageIO.write(cover,"png",file)
        val artwork="data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(file.readBytes())
        val track=Track("selected","SPIT IN MY FACE!","ThxSoMch",thumbnail=artwork)
        val playlists=listOf(Playlist("one","Evening favourites",coverUrl=artwork,tracks=emptyList()),
            Playlist("two","Already saved",tracks=listOf(track)))
        var added:String?=null; var creates=0
        val scene=ImageComposeScene(380,430,coroutineContext=Dispatchers.Unconfined) {
            MaterialTheme(colorScheme=darkColorScheme(),typography=DesktopTypography) {
                CompositionLocalProvider(LocalContentColor provides Color.White,
                    com.auralis.music.ui.components.LocalArtworkRenderer provides com.auralis.music.ui.components.DesktopArtworkRenderer) {
                Box(Modifier.fillMaxSize().background(Color(0xFF29262D))) {
                    PlayerPlaylistPickerContent(track,playlists,false,null,{creates++},{added=it.id})
                }
                }
            }
        }
        try {
            var frame=0L
            repeat(20) {scene.render(frame++*16_666_667L).close(); Thread.sleep(10)}
            scene.render(frame++*16_666_667L).use { image ->
                val bytes=image.encodeToData(EncodedImageFormat.PNG)!!.use{it.bytes}
                File("build/screens/playlist-picker").apply{mkdirs()}.resolve("artwork-picker.png").writeBytes(bytes)
                val rendered=ImageIO.read(bytes.inputStream())
                val orangePixels=(180..216).sumOf { y -> (18..58).count { x ->
                    val rgb=rendered.getRGB(x,y); (rgb shr 16 and 255)>140 && (rgb and 255)<100
                } }
                assertTrue(orangePixels>150,"The playlist's actual cover must render, not only an empty artwork slot")
            }
            fun click(y:Float) {
                scene.sendPointerEvent(PointerEventType.Press,Offset(190f,y),type=PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release,Offset(190f,y),type=PointerType.Mouse)
                scene.render(frame++*16_666_667L).close()
            }
            click(126f); assertEquals(1,creates,"Create playlist remains the first action")
            click(180f); assertEquals("one",added)
            added=null; click(225f); assertNull(added,"A playlist already containing the song must not add it again")
        } finally {scene.close();dir.deleteRecursively()}
    }
}
