package com.auralis.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The sheet supplies live glass once. Scrolling rows add only a quiet tint and rim:
 * no per-row backdrop capture, RenderEffect, refraction shader or shadow. */
internal fun Modifier.playlistRowGlass(isDark: Boolean, selected: Boolean, accent: Color): Modifier {
    val shape=RoundedCornerShape(14.dp)
    val tint=if(selected) accent.copy(alpha=if(isDark) .10f else .12f)
        else if(isDark) Color.White.copy(alpha=.018f) else Color.White.copy(alpha=.09f)
    val rim=if(selected) accent.copy(alpha=.18f) else
        if(isDark) Color.White.copy(alpha=.045f) else Color.Black.copy(alpha=.035f)
    return background(Brush.verticalGradient(listOf(tint.copy(alpha=tint.alpha*1.2f),tint)),shape)
        .border(.5.dp,rim,shape)
}
