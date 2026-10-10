package com.auralis.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalWindowInfo

/** The window's content area; no page blur on desktop yet (the menu dims instead, as on older phones). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun rememberMenuPage(): MenuPage {
    val windowInfo = LocalWindowInfo.current
    return remember(windowInfo) {
        MenuPage(
            width = { windowInfo.containerSize.width },
            height = { windowInfo.containerSize.height },
            blurSupported = false,
            setBlur = {}
        )
    }
}

/** Desktop dialogs and popups are laid out in window coordinates. */
@Composable
actual fun rememberOriginOnScreen(): () -> Offset = remember { { Offset.Zero } }

@Composable
actual fun ConfigureMenuDialogWindow() {}

actual fun menuDialogProperties() = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
