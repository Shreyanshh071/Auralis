package com.auralis.music.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ============================================================================
// 🌓 MATERIAL 3 COLOR SCHEMES
// ============================================================================
// The fixed Auralis colour schemes, used when no dynamic (wallpaper or artwork) colours apply.

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBDD269),
    onPrimary = Color(0xFF1C2000),
    primaryContainer = Color(0xFF3B4800),
    onPrimaryContainer = Color(0xFFDCE2BD),
    secondary = Color(0xFFC4CAAC),
    onSecondary = Color(0xFF2E331B),
    secondaryContainer = Color(0xFF444A30),
    onSecondaryContainer = Color(0xFFE1E7C5),
    tertiary = Color(0xFFA2D0C1),
    onTertiary = Color(0xFF04372D),
    tertiaryContainer = Color(0xFF204E43),
    onTertiaryContainer = Color(0xFFBCECE0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF161619),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF202024),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF383840),
    outlineVariant = Color(0xFF28282E)
)

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF536600),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE2BD),
    onPrimaryContainer = Color(0xFF171E00),
    secondary = Color(0xFF5C6246),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E7C5),
    onSecondaryContainer = Color(0xFF191E09),
    tertiary = Color(0xFF3A665A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCECE0),
    onTertiaryContainer = Color(0xFF00201A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9FAFB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFF9CA3AF),
    outlineVariant = Color(0xFFE5E7EB)
)

val AmoledDarkColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF121214),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF2C2C32),
    outlineVariant = Color(0xFF202026)
)

val MidnightDarkColorScheme = DarkColorScheme.copy(
    background = Color(0xFF0A0A0C),
    surface = Color(0xFF121215),
    surfaceVariant = Color(0xFF1A1A1E),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF303038),
    outlineVariant = Color(0xFF222228)
)
