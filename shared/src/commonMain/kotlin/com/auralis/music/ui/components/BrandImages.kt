package com.auralis.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter

/** The Auralis logos, from each platform's own resources (the phone's drawables, desktop's bundled PNGs). */
class BrandImages(
    val headerLogo: @Composable () -> Painter,
    val appLogo: @Composable () -> Painter
)

private val NoImage: @Composable () -> Painter = { ColorPainter(Color.Transparent) }

val LocalBrandImages = staticCompositionLocalOf { BrandImages(NoImage, NoImage) }
