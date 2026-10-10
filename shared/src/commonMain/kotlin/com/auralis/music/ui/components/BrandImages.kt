package com.auralis.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter

/** The Auralis logos and link icons, from each platform's own resources (the phone's drawables, desktop's bundled copies). */
class BrandImages(
    val headerLogo: @Composable () -> Painter,
    val appLogo: @Composable () -> Painter,
    val github: @Composable () -> Painter = NoImage,
    val discord: @Composable () -> Painter = NoImage
)

private val NoImage: @Composable () -> Painter = { ColorPainter(Color.Transparent) }

val LocalBrandImages = staticCompositionLocalOf { BrandImages(NoImage, NoImage) }
