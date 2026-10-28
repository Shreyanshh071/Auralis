package com.auralis.music.ui.lyrics.renderers

import androidx.compose.ui.graphics.ImageBitmap

/** Rasterise a glyph mask's halo once; animation only moves the cached pixels. */
internal expect fun blurSpicyGlyphMask(mask: ImageBitmap, sigma: Float): ImageBitmap
