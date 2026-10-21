package com.auralis.music.windows.home

import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
import com.auralis.music.ui.theme.ArtworkPalette

internal fun greetingThemeColour(palette: ArtworkPalette, themeBackground: Color): Color {
    val background = themeBackground.compositeOver(lerp(Color.Black, palette.primary, .68f))
    fun contrast(a: Color, b: Color): Float = (maxOf(a.luminance(), b.luminance()) + .05f) /
        (minOf(a.luminance(), b.luminance()) + .05f)
    // Keep the song's hue. On dark artwork, move toward white instead of picking a companion hue.
    val edge = if (contrast(Color.White, background) >= contrast(Color.Black, background)) Color.White else Color.Black
    val start = lerp(palette.primary, edge, .38f)
    if (contrast(start, background) >= 4.5f) return start
    var low = 0f
    var high = 1f
    repeat(16) {
        val amount = (low + high) / 2f
        if (contrast(lerp(start, edge, amount), background) >= 4.5f) high = amount else low = amount
    }
    return lerp(start, edge, high)
}

internal fun greetingNameText(part: String, name: String?, colour: Color): AnnotatedString = buildAnnotatedString {
    append(part)
    if (!name.isNullOrBlank()) {
        append(", ")
        withStyle(SpanStyle(color = colour)) { append(name.trim()) }
    }
}
