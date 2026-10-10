package com.auralis.music.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** Settings → Appearance, as the app's theme provides it. */
val LocalAppearanceSettings = staticCompositionLocalOf {
    com.auralis.music.domain.model.AppearanceSettings()
}
