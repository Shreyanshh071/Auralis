package com.auralis.music.windows.media

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class AppIconTest {
    @Test fun appIconHasADrawnTileForEverySmallSize() {
        val sizes = listOf(16, 20, 24, 32, 40, 48, 64)
        for (size in sizes) {
            val tile = assertNotNull(javaClass.classLoader.getResourceAsStream("app-icon/tile_$size.png")).use(ImageIO::read)
            assertEquals(size, tile.width)
            // The knot must read as white on the brown tile: plenty of near-white pixels.
            val white = (0 until size * size).count { val c = tile.getRGB(it % size, it / size); ((c shr 16) and 255) > 200 && (c and 255) > 200 }
            assertTrue(white >= size * size / 10, "${size}px tile: knot too faint ($white white pixels)")
        }
        val ico = File("icons/auralis.ico").readBytes()
        val count = (ico[4].toInt() and 255) or ((ico[5].toInt() and 255) shl 8)
        val icoSizes = (0 until count).map { (ico[6 + 16 * it].toInt() and 255).let { s -> if (s == 0) 256 else s } }
        assertEquals(sizes + listOf(128, 256), icoSizes, "The .exe icon carries the same drawn small sizes")
    }

    @Test fun trayHasItsOwnTileForEveryTraySize() {
        for (size in listOf(16, 20, 24, 32, 40, 48)) {
            val tile = assertNotNull(javaClass.classLoader.getResourceAsStream("app-icon/tray_$size.png")).use(ImageIO::read)
            assertEquals(size, tile.width)
            val white = (0 until size * size).count { val c = tile.getRGB(it % size, it / size); ((c shr 16) and 255) > 200 && (c and 255) > 200 }
            assertTrue(white >= size * size / 14, "${size}px tray tile: knot too faint ($white white pixels)")
        }
    }
}
