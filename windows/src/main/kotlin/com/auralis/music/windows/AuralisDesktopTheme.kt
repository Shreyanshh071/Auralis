package com.auralis.music.windows

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same values as the Android theme (android/app/.../ui/theme/Color.kt). Once the Compose UI moves
// into shared/, both apps will use that one definition and this copy goes away.
private val DesktopColors = darkColorScheme(
    primary = Color(0xFF8B5CF6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3B1E78),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFF06B6D4),
    onSecondary = Color(0xFF00363A),
    tertiary = Color(0xFFF43F5E),
    onTertiary = Color(0xFF4C0519),
    background = Color(0xFF0B0D0A),
    onBackground = Color(0xFFF2F3EE),
    surface = Color(0xFF161815),
    onSurface = Color(0xFFE4E5E0),
    surfaceVariant = Color(0xFF1F211E),
    onSurfaceVariant = Color(0xFFC4C6BF),
    surfaceContainer = Color(0xFF272825),
    surfaceContainerHigh = Color(0xFF333531),
    surfaceContainerHighest = Color(0xFF3F413D),
    outline = Color(0xFF434540),
    outlineVariant = Color(0xFF2B2D29),
    error = Color(0xFFFF5252),
)

@Composable
fun AuralisDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DesktopColors, content = content)
}
