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

    val images = BrandImages(headerLogo = { header }, appLogo = { app })
}
