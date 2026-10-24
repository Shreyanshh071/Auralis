package com.auralis.music.windows.home

import com.auralis.music.ui.components.DesktopArtworkRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/** Custom playlist covers synced from the phone are stored as data URIs, not URLs. */
class EmbeddedCoverTest {
    @Test fun dataUriCoversDecodeToTheirImage() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val uri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
        val bytes = assertNotNull(DesktopArtworkRenderer.decodeDataUri(uri))
        assertEquals(6, ImageIO.read(bytes.inputStream()).width)
        assertTrue(DesktopArtworkRenderer.model(uri) is ByteArray, "Coil gets the decoded bytes")
        assertEquals("https://example.com/a.jpg", DesktopArtworkRenderer.model("https://example.com/a.jpg"))
        assertNull(DesktopArtworkRenderer.decodeDataUri("data:text/plain,hello"))
    }
}
