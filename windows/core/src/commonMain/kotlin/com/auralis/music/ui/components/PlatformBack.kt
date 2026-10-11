package com.auralis.music.ui.components

import androidx.compose.runtime.Composable

/** The platform's back action (Android's back gesture; Esc or the mouse back button on desktop). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
