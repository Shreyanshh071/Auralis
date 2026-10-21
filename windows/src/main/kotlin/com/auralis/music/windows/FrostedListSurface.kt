package com.auralis.music.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Matte frost over the app's already blurred artwork; no reflective gradients or refraction. */
@Composable
internal fun Modifier.frostedListSurface(shape: Shape, hover: Float, accent: Color? = null): Modifier {
    val light = MaterialTheme.colorScheme.onSurface.luminance() < .5f
    val tint = if (light) Color(0xFFFAFAFA).copy(alpha = .64f + .06f * hover)
        else Color(0xFF242428).copy(alpha = .56f + .06f * hover)
    val rim = accent?.copy(alpha = .50f)
        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = .10f + .06f * hover)
    return clip(shape).background(tint)
        .background(accent?.copy(alpha = .07f) ?: Color.Transparent)
        .border(1.dp, rim, shape)
}
