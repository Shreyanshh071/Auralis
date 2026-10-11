package com.auralis.music.ui.components

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import javax.imageio.ImageIO

/** The phone's logo drawables, bundled at build time (shared :bundleDesktopStrings copies them). */
object DesktopBrandImages {
    private fun load(name: String): Painter =
        DesktopBrandImages::class.java.getResourceAsStream("/brand/$name")?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
            ?: ColorPainter(Color.Transparent)

    private val header by lazy { load("ic_auralis_header_logo.png") }
    private val app by lazy { load("ic_auralis_logo.png") }

    /** The phone's vector drawables (Android vector XML), drawn by Compose Desktop's loader. */
    @androidx.compose.runtime.Composable
    private fun vector(name: String): Painter {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val image = androidx.compose.runtime.remember(name, density) {
            DesktopBrandImages::class.java.getResourceAsStream("/brand/$name")?.use {
                androidx.compose.ui.res.loadXmlImageVector(org.xml.sax.InputSource(it), density)
            }
        }
        return if (image != null) androidx.compose.ui.graphics.vector.rememberVectorPainter(image) else ColorPainter(Color.Transparent)
    }

    val images = BrandImages(
        headerLogo = { header },
        appLogo = { app },
        github = { vector("ic_github.xml") },
        discord = { vector("ic_discord.xml") }
    )
}
