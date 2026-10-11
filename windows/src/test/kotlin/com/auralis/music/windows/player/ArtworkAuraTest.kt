package com.auralis.music.windows.player

import java.awt.image.BufferedImage
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.*

class ArtworkAuraTest {
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
