package com.auralis.music.ui.components

import androidx.compose.runtime.Composable

private object NoHaptics : ViewHaptics {
    override fun tick() {}
    override fun longPress() {}
}

@Composable
actual fun rememberViewHaptics(): ViewHaptics = NoHaptics
