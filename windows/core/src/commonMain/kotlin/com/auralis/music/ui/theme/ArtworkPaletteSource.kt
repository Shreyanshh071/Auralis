package com.auralis.music.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The current song's artwork palette, for screens that preview it; the phone points this at its palette cache. */
object ArtworkPaletteSource {
    @Volatile var current: StateFlow<ArtworkPalette> = MutableStateFlow(DefaultArtworkPalette)
}
