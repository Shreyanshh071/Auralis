package com.auralis.music.windows.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable internal fun DesktopThemeOverrides(prefs: DesktopPreferencesStore = DesktopPreferences.store, content: @Composable () -> Unit) {
    remember {
        java.io.File(com.auralis.music.util.desktopAppDataDir(),"fonts").listFiles()?.forEach { file ->
            runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,file)) }
        }
    }
    val base = MaterialTheme.colorScheme
    val accent = prefs.text("accent").takeIf { it.matches(Regex("#[A-Fa-f0-9]{6}")) }?.let { Color(0xff000000 or it.drop(1).toLong(16)) }
    val colors = if (accent == null) base else base.copy(primary = accent, secondary = accent,
        onPrimary=if(accent.luminance()>.179f) Color.Black else Color.White,
        onSecondary=if(accent.luminance()>.179f) Color.Black else Color.White)
    val body = prefs.text("bodyFont", "Default").takeUnless { it == "Default" }?.let { FontFamily(it) }
    val heading = prefs.text("headingFont", "Default").takeUnless { it == "Default" }?.let { FontFamily(it) }
    val t = MaterialTheme.typography
    val typography = t.copy(bodyLarge=t.bodyLarge.copy(fontFamily=body ?: t.bodyLarge.fontFamily), bodyMedium=t.bodyMedium.copy(fontFamily=body ?: t.bodyMedium.fontFamily),
        bodySmall=t.bodySmall.copy(fontFamily=body ?: t.bodySmall.fontFamily), labelLarge=t.labelLarge.copy(fontFamily=body ?: t.labelLarge.fontFamily),
        labelMedium=t.labelMedium.copy(fontFamily=body ?: t.labelMedium.fontFamily), labelSmall=t.labelSmall.copy(fontFamily=body ?: t.labelSmall.fontFamily),
        displayLarge=t.displayLarge.copy(fontFamily=heading ?: t.displayLarge.fontFamily), displayMedium=t.displayMedium.copy(fontFamily=heading ?: t.displayMedium.fontFamily),
        displaySmall=t.displaySmall.copy(fontFamily=heading ?: t.displaySmall.fontFamily), headlineLarge=t.headlineLarge.copy(fontFamily=heading ?: t.headlineLarge.fontFamily),
        headlineMedium=t.headlineMedium.copy(fontFamily=heading ?: t.headlineMedium.fontFamily), headlineSmall=t.headlineSmall.copy(fontFamily=heading ?: t.headlineSmall.fontFamily),
        titleLarge=t.titleLarge.copy(fontFamily=heading ?: t.titleLarge.fontFamily), titleMedium=t.titleMedium.copy(fontFamily=heading ?: t.titleMedium.fontFamily), titleSmall=t.titleSmall.copy(fontFamily=heading ?: t.titleSmall.fontFamily))
    val r=prefs.number("roundness",12.0).coerceIn(0.0,28.0).toFloat().dp
    MaterialTheme(colorScheme=colors, typography=typography, shapes=Shapes(RoundedCornerShape(r/3),RoundedCornerShape(r/2),RoundedCornerShape(r),RoundedCornerShape(r*1.5f),RoundedCornerShape(r*2)),content=content)
}
