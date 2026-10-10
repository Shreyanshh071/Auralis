package com.auralis.music.ui.components

import androidx.compose.runtime.Composable

/** The phone's view haptics (clock tick, long press); nothing where the device has none. */
interface ViewHaptics {
    fun tick()
    fun longPress()
}

@Composable
expect fun rememberViewHaptics(): ViewHaptics
