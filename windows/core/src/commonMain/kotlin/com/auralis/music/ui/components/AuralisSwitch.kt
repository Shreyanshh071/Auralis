package com.auralis.music.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Every toggle in the Windows app. Screens used to pass their own colours, which with artwork
 * themes gave mismatched pairs (an orange track under a grey thumb). This one always uses the
 * theme accent for "on" with a contrasting thumb, and quiet neutrals for "off". Imported by name,
 * it takes precedence over material3's Switch, so a screen's [colors] argument is ignored.
 */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") colors: SwitchColors? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF141414) else Color.White
    val neutral = scheme.onSurface
    SmoothControl(modifier, interactionSource, rememberThumbHoverShape(if (checked) .7f else .3f), neutral, enabled) { draw, source ->
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = draw,
        thumbContent = thumbContent,
        enabled = enabled,
        interactionSource = source,
        colors = SwitchDefaults.colors(
            checkedThumbColor = onAccent,
            checkedTrackColor = accent,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = accent,
            uncheckedThumbColor = neutral.copy(alpha = 0.75f),
            uncheckedTrackColor = neutral.copy(alpha = 0.12f),
            uncheckedBorderColor = neutral.copy(alpha = 0.28f),
            uncheckedIconColor = scheme.surface,
            disabledCheckedThumbColor = onAccent.copy(alpha = 0.6f),
            disabledCheckedTrackColor = accent.copy(alpha = 0.35f),
            disabledUncheckedThumbColor = neutral.copy(alpha = 0.35f),
            disabledUncheckedTrackColor = neutral.copy(alpha = 0.06f),
            disabledUncheckedBorderColor = neutral.copy(alpha = 0.12f),
        )
    )
    }
}
