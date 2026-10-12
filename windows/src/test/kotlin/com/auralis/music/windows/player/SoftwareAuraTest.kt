package com.auralis.music.windows.player

import java.awt.image.BufferedImage
import org.jetbrains.skia.Surface
import kotlin.test.*

class SoftwareAuraTest {
    @Test fun cachedSoftwareWarpKeepsMovingColoursAndFitsTheFrameBudget() {
        val previous = System.getProperty("skiko.renderApi")
        System.setProperty("skiko.renderApi","SOFTWARE_FAST")
        try {
            val source = BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB)
            for(y in 0 until 64) for(x in 0 until 64) source.setRGB(x,y,if(x<32) 0xff2255ee.toInt() else 0xffed2288.toInt())
            AuraRenderer(auraTexture(source)).use { renderer ->
                Surface.makeRasterN32Premul(1280,800).use { surface ->
                    renderer.draw(surface.canvas,1280f,800f,0f)
                    val before=surface.makeImageSnapshot().use { it.encodeToData()!!.use { it.bytes } }
                    val start=System.nanoTime()
                    repeat(30) { renderer.draw(surface.canvas,1280f,800f,it / 30f) }
                    val elapsed=(System.nanoTime()-start)/1_000_000
                    println("Software cached background: 30 frames in ${elapsed}ms")
                    assertTrue(elapsed<3000,"CPU background exceeds 100ms per frame: $elapsed")
                    renderer.draw(surface.canvas,1280f,800f,12f)
                    val after=surface.makeImageSnapshot().use { it.encodeToData()!!.use { it.bytes } }
                    assertFalse(before.contentEquals(after),"Software background must retain moving cover colours")
                }
            }
        } finally {
            if(previous==null) System.clearProperty("skiko.renderApi") else System.setProperty("skiko.renderApi",previous)
        }
    }
}
