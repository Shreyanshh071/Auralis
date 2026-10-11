package com.auralis.music.ui.glass

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

@Composable
actual fun rememberDialogPageAnchor(): Any? = null

/** Desktop dialogs draw in the same window, so the glass needs no offset. */
actual fun Modifier.liquidGlassDialog(glass: LiquidGlassContext, shape: CornerBasedShape, pageAnchor: Any?): Modifier =
    composed { liquidGlass(glass, shape) }
