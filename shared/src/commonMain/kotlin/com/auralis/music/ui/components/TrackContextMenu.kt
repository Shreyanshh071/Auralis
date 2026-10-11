package com.auralis.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Optional desktop presentation; the phone retains its existing sheet. */
val LocalTrackContextMenu = staticCompositionLocalOf<(@Composable (List<GlassMenuItem>, () -> Unit) -> Unit)?> { null }
