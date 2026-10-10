package com.auralis.music.ui.glass

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The page a dialog's glass should show through, taken before the dialog opens (the phone's activity view). */
@Composable
expect fun rememberDialogPageAnchor(): Any?

/** Glass for a dialog's own window, aligned with the page under it ([pageAnchor]). */
expect fun Modifier.liquidGlassDialog(glass: LiquidGlassContext, shape: CornerBasedShape, pageAnchor: Any?): Modifier
