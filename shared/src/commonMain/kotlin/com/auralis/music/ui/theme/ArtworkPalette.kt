package com.auralis.music.ui.theme

import androidx.compose.ui.graphics.Color

/** Colours taken from a song's artwork; each platform extracts them its own way. */
data class ArtworkPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val seedColor: Color = primary,
    val isMonochrome: Boolean = false,
    val glowColors: List<Color> = emptyList(),
    val isPlaceholder: Boolean = false
) {
    val isDefault: Boolean
        get() = this == DefaultArtworkPalette
}

/** No artwork colours yet: the neutral palette the phone has always used. */
val DefaultArtworkPalette = ArtworkPalette(
        primary = Color(0xFF161619),   // Neutral Slate Charcoal
        secondary = Color(0xFF121215), // Deep Midnight
        tertiary = Color(0xFF0E0E10),  // Obsidian Base
        seedColor = Color.Unspecified, // Pure unspecified seed - prevents artificial blue HCT tints!
        isMonochrome = true,
        glowColors = listOf(
            Color(0xFF202024),
            Color(0xFF161619),
            Color(0xFF121215),
            Color(0xFF0E0E10),
            Color(0xFF1A1A1E),
            Color(0xFF0A0A0C)
        )
    )
