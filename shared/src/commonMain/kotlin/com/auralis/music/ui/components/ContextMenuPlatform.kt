package com.auralis.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset

/** The page behind a glass menu: its size, and whether / how it can be blurred. */
class MenuPage(
    val width: () -> Int,
    val height: () -> Int,
    val blurSupported: Boolean,
    val setBlur: (radiusPx: Float) -> Unit
)

@Composable
expect fun rememberMenuPage(): MenuPage

/** Where this composition's window is on screen (zero where windows place popups themselves). */
@Composable
expect fun rememberOriginOnScreen(): () -> Offset

/** Inside the menu's Dialog: makes its window full screen, undimmed and unanimated where that applies. */
@Composable
expect fun ConfigureMenuDialogWindow()

/** Behind a song's / playlist's option sheet: a colour field from its cover (the phone's player field). */
val LocalCoverSheetBackground = staticCompositionLocalOf<@Composable (artworkUrl: String?) -> Unit> { { } }

/** Full-window dialog properties for the menu. */
expect fun menuDialogProperties(): androidx.compose.ui.window.DialogProperties
