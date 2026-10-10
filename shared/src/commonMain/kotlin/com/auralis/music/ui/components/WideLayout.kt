package com.auralis.music.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True where screens may rearrange for a wide window (desktop). The phone leaves it false, so
 * its layouts stay exactly as they are on every screen size it runs on.
 */
val LocalWideWindowLayout = staticCompositionLocalOf { false }
