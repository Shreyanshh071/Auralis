package com.auralis.music.windows.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.windows.player.ArtworkBackdrop
import com.auralis.music.windows.player.LocalArtworkBackdrop
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class SettingsArtworkSyncTest {
    private fun cover(rgb: Int): String {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) image.setRGB(x, y, rgb)
        val file = File.createTempFile("auralis-cover", ".png").apply { deleteOnExit() }
        ImageIO.write(image, "png", file)
        return file.absolutePath
    }

    private class Probe(val panel: Int, val dialog: Int)

    /** Renders the settings popup over [backdrop] until the artwork has loaded and crossfaded. */
    private fun render(backdrop: State<ArtworkBackdrop?>, scene: ImageComposeScene, from: Long, save: String? = null): Probe {
        var time = from
        var image: org.jetbrains.skia.Image? = null
        repeat(90) {
            image?.close()
            Thread.sleep(15)
            image = scene.render(time); time += 16_666_667L
        }
        val bitmap = image!!.toComposeImageBitmap()
        if (save != null) File(System.getProperty("java.io.tmpdir"), save).writeBytes(
            image!!.encodeToData()!!.bytes)
        fun pixel(x: Int, y: Int) = IntArray(1).also { bitmap.readPixels(it, startX = x, startY = y, width = 1, height = 1) }[0]
        return Probe(panel = pixel(120, 500), dialog = pixel(450, 120)).also { image!!.close() }
    }

    private fun channels(argb: Int) = Triple((argb shr 16) and 255, (argb shr 8) and 255, argb and 255)

    @Test fun settingsPanelFollowsTheCurrentSongsArtwork() {
        val red = ArtworkBackdrop(cover(0xD02020), ArtworkPalette(Color(0xFFD02020), Color(0xFFD02020), Color(0xFFD02020)))
        val blue = ArtworkBackdrop(cover(0x2040D0), ArtworkPalette(Color(0xFF2040D0), Color(0xFF2040D0), Color(0xFF2040D0)))
        val backdrop = mutableStateOf<ArtworkBackdrop?>(red)
        val scene = ImageComposeScene(900, 600, coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalArtworkBackdrop provides backdrop.value) {
                    SettingsPopupOverlay(Modifier.fillMaxSize(), {}) {
                        Column(Modifier.fillMaxSize().padding(24.dp)) {
                            Text("General")
                            Surface(Modifier.padding(start = 300.dp, top = 40.dp).size(260.dp, 120.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text("Dialog") }
                        }
                    }
                }
            }
        }
        try {
            val onRed = render(backdrop, scene, 0L, "auralis-settings-red.png")
            backdrop.value = blue
            val onBlue = render(backdrop, scene, 2_000_000_000L, "auralis-settings-blue.png")
            backdrop.value = null
            val plain = render(backdrop, scene, 4_000_000_000L, "auralis-settings-plain.png")
            val (rr, rg, rb) = channels(onRed.panel)
            val (br, bg, bb) = channels(onBlue.panel)
            assertTrue(rr > rb + 30, "Red song: panel shows the red artwork field: ${Integer.toHexString(onRed.panel)}")
            assertTrue(bb > br + 30, "Blue song: panel follows the song change: ${Integer.toHexString(onBlue.panel)}")
            assertEquals(Color(0xFF1C1B20).toArgb(), plain.panel, "No artwork background keeps the neutral panel")
            assertEquals(255, onRed.dialog ushr 24, "Dialog containers inside settings stay opaque")
            val (dr, _, db) = channels(onRed.dialog)
            assertTrue(dr > db, "Dialog containers carry a wash of the song colour: ${Integer.toHexString(onRed.dialog)}")
        } finally { scene.close() }
    }
}
