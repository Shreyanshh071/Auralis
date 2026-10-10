package com.auralis.music.ui.theme

import androidx.compose.runtime.Composable

/** Windows' "Show animations" setting (SPI_GETCLIENTAREAANIMATION); read once per composition. */
@Composable
actual fun rememberReducedMotion(): Boolean = WindowsAnimationSetting.animationsOff
