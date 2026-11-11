package com.auralis.music.ui.player

import androidx.compose.runtime.compositionLocalOf

/** False for minimized windows and content covered by an opaque player.
 * Keep playback and UI state alive without running invisible visual clocks or decoders.
 */
val LocalPlaybackVisualsActive = compositionLocalOf { true }
