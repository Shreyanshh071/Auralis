package com.auralis.music.windows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.auralis.music.ui.theme.DarkColorScheme
import com.auralis.music.ui.theme.DesktopTypography

// The phone's own dark scheme and type scale (shared/…/ui/theme). The phone recolours itself from
// the wallpaper or the playing song's artwork; until desktop does that, it uses the phone's fixed
// dark scheme, which is what the phone shows when no dynamic colours are available.
@Composable
fun AuralisDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, typography = DesktopTypography, content = content)
}
