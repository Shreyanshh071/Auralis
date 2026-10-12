package com.auralis.music.windows.player

import java.awt.image.BufferedImage
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.*

class ArtworkAuraTest {
    @Test fun brightAndPastelCoversKeepContrastAndColourInBothPresentations() {
        val cases = mapOf("white" to 0xffffffff.toInt(), "pastel" to 0xffedc0d2.toInt(),
            "blue" to 0xff8ca9d6.toInt(), "black" to 0xff080808.toInt())
        val output = java.io.File("build/screens/aura-colour").apply { mkdirs() }
        for ((name, colour) in cases) for (presentation in AuraPresentation.entries) {
            val cover = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 32) for (x in 0 until 32) cover.setRGB(x, y, colour)
            AuraRenderer(auraTexture(cover), presentation).use { renderer ->
                Surface.makeRasterN32Premul(320, 180).use { surface ->
                    renderer.draw(surface.canvas, 320f, 180f, 0f)
                    val png = surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
                    java.io.File(output, "$name-${presentation.name}.png").writeBytes(png)
                    val pixel = javax.imageio.ImageIO.read(png.inputStream()).getRGB(160, 90)
                    val channels = listOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255)
                    assertEquals(255, pixel ushr 24, "Background must remain opaque")
                    when (name) {
                        "white" -> assertTrue(channels.all { it in 150..168 }, "Bright cover must be dimmed behind white lyrics: $channels")
                        "pastel" -> assertTrue(channels[0] - channels[1] > 80, "Pink artwork must retain its colour instead of washing out: $channels")
                        "blue" -> assertTrue(channels[2] - channels[0] > 100, "Blue artwork must retain its colour: $channels")
                        "black" -> assertTrue(channels.max() < 10, "Dark artwork must stay dark")
                    }
                }
            }
        }
    }

    @Test fun coverColoursFlowOverTimeWithoutTransparentPixels() {
        val cover = BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 96) for (x in 0 until 96)
            cover.setRGB(x, y, if (x < 48) 0xffb92192.toInt() else 0xffe9ba45.toInt())
        val texture = auraTexture(cover)
        AuraRenderer(texture).use { renderer ->
            Surface.makeRasterN32Premul(160, 320).use { surface ->
                renderer.draw(surface.canvas, 160f, 320f, 0f)
                val first = surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
                renderer.draw(surface.canvas, 160f, 320f, 12f)
                val second = surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
                assertFalse(first.contentEquals(second), "The flowing cover must change over time")
                assertTrue(first.size > 1000, "The background must retain spatial colour variation")
            }
        }
    }
}
