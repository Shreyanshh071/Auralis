package com.auralis.music.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSupported
import androidx.compose.ui.graphics.layer.drawLayer

// Vibrant player chrome adapted from BitChord (GPL-3.0): https://github.com/kushagrasinghx/BitChord

/**
 * The neutral grey that vibrant chrome is drawn in, so its hue is all the backdrop's. The grey
 * sets the dodge's gain (about 2.5x here); brighter greys clipped colourful covers to flat neon.
 */
internal val VibrantInk = Color(0xFF999999).copy(alpha = 0.85f)

/**
 * How much of the ink is also simply added on top. Dodge multiplies, and black times anything
 * is still black, so without this the artist line all but vanished on a dark cover.
 */
private const val VIBRANT_FLOOR = 0.4f

/**
 * Colour-dodges content drawn in [VibrantInk] into whatever is behind it, so the cover's colour
 * comes through the text and glyphs brighter and richer, the way Apple Music's player does,
 * instead of a flat grey laid over it. Where the platform has no dodge the ink is added instead.
 *
 * The blend only reaches what is drawn into the same layer, so nothing between this and the
 * backdrop may composite offscreen (they don't at rest; a fading parent briefly does).
 */
internal fun Modifier.vibrantChrome(): Modifier = drawWithCache {
    val floor = obtainGraphicsLayer().apply {
        blendMode = BlendMode.Plus
        alpha = VIBRANT_FLOOR
    }
    val dodgeSupported = BlendMode.ColorDodge.isSupported()
    val tint = obtainGraphicsLayer().apply {
        blendMode = if (dodgeSupported) BlendMode.ColorDodge else BlendMode.Plus
    }
    onDrawWithContent {
        floor.record { this@onDrawWithContent.drawContent() }
        tint.record { this@onDrawWithContent.drawContent() }
        drawLayer(floor)
        drawLayer(tint)
    }
}
