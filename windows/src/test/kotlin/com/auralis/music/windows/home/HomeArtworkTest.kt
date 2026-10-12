package com.auralis.music.windows.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.*
import com.auralis.music.desktop.library.DesktopDownloads
import com.auralis.music.ui.components.*
import com.auralis.music.windows.library.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.*

class HomeArtworkTest {
    @Test fun collageHasFourCoversAndDownloadPlaylistUsesItsOwnCover() = runBlocking {
        val dir = File("build/screens/queued-ui").apply { mkdirs() }
        val colours = listOf(java.awt.Color.RED, java.awt.Color.GREEN, java.awt.Color.BLUE, java.awt.Color.YELLOW)
        val tracks = colours.mapIndexed { i, colour ->
            val cover = BufferedImage(100,100,BufferedImage.TYPE_INT_RGB)
            cover.createGraphics().apply { color = colour; fillRect(0,0,100,100); dispose() }
            val path = File(dir, "cover-$i.png").also { ImageIO.write(cover,"png",it) }.absolutePath
            Track("cover-$i", "Song $i", "Artist $i", thumbnail = path)
        }
        val collage = assertNotNull(playlistCollage(tracks))
        val combined = ImageIO.read(File(collage))
        listOf(150 to 150,450 to 150,150 to 450,450 to 450).forEachIndexed { i, (x,y) -> assertEquals(colours[i].rgb, combined.getRGB(x,y)) }
        val entries = tracks.mapIndexed { i,t -> DesktopDownloads.Entry("$i","individual","Individual songs","Individual songs",t,i) }
        val scene = ImageComposeScene(400,200, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalArtworkRenderer provides DesktopArtworkRenderer) {
                    Row {
                        Box(Modifier.size(200.dp)) { DownloadFolderArtwork(entries, null) }
                        Box(Modifier.size(200.dp)) { DownloadFolderArtwork(entries, tracks[2].thumbnail) }
                    }
                }
            }
        }
        try {
            repeat(100) { scene.render(it * 16_666_667L).close(); delay(15) }
            val bytes = scene.render(2_000_000_000L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { d -> d.bytes } }
            File(dir,"folder-artwork.png").writeBytes(bytes)
            val image = ImageIO.read(bytes.inputStream())
            listOf(50 to 50,150 to 50,50 to 150,150 to 150).forEachIndexed { i,(x,y) -> assertEquals(colours[i].rgb, image.getRGB(x,y),"Individual songs quadrant $i") }
            assertEquals(java.awt.Color.BLUE.rgb, image.getRGB(250,50))
            assertEquals(java.awt.Color.BLUE.rgb, image.getRGB(350,150),"Playlist uses its single cover instead of a collage")
        } finally { scene.close() }
    }

    @Test fun shortcutHasMenuAndRemovalAndItsCoverBackgroundMoves() {
        val dir = File("build/screens/queued-ui").apply { mkdirs() }
        val cover = BufferedImage(200,200,BufferedImage.TYPE_INT_RGB)
        cover.createGraphics().apply {
            color = java.awt.Color.BLUE; fillRect(0,0,200,200)
            color = java.awt.Color.RED; fillOval(20,10,130,130)
            color = java.awt.Color.GREEN; fillOval(75,90,100,110)
            dispose()
        }
        val path = File(dir,"flow-cover.png").also { ImageIO.write(cover,"png",it) }.absolutePath
        val item = SpeedDialItem("album-local:p", "gili gili", SpeedDialType.ALBUM, path,
            album = PlaylistResult("local:p", "gili gili", path, "Playlist · 100 songs"), isPinned = true)
        var menus = 0; var removes = 0; var opens = 0
        val scene = ImageComposeScene(600,180, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeShortcuts(listOf(item),listOf(item), { opens++ }, {}, { removes++ }, onMenu = { menus++ })
            }
        }
        try {
            var frame = 0L
            fun render(): ByteArray = scene.render(frame++ * 16_666_667L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { d -> d.bytes } }
            repeat(100) { render(); Thread.sleep(10) }
            val first = render()
            repeat(120) { render() }
            val second = render()
            File(dir,"shortcuts.png").writeBytes(second)
            val a = ImageIO.read(first.inputStream()); val b = ImageIO.read(second.inputStream())
            assertTrue((80 until 230).any { x -> a.getRGB(x,115) != b.getRGB(x,115) }, "Cover-derived glow should animate behind the label")
            scene.sendPointerEvent(PointerEventType.Press,Offset(254f,92f)); scene.sendPointerEvent(PointerEventType.Release,Offset(254f,92f))
            render(); assertEquals(1,menus); assertEquals(0,opens)
            scene.sendPointerEvent(PointerEventType.Press,Offset(280f,92f)); scene.sendPointerEvent(PointerEventType.Release,Offset(280f,92f))
            render(); assertEquals(1,removes); assertEquals(0,opens)
        } finally { scene.close() }
    }
}
