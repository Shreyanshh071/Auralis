package com.auralis.music.windows.search

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.layer.GraphicsLayer

/** Records only Auralis's own page, never the desktop or other applications. */
internal val LocalSearchBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }
