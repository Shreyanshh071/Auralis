package com.auralis.music.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.glass.glassContentColor
import com.auralis.music.ui.glass.liquidGlassDialog

private val DialogShape = RoundedCornerShape(30.dp)

/** The app's popup: real glass on liquid glass, a raised card otherwise. */
@Composable
internal fun AuralisAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    val glass = LocalLiquidGlass.current
    val pageView = LocalView.current
    val foreground = glass?.let { glassContentColor(it.isDark) } ?: MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = if (glass != null) modifier.liquidGlassDialog(glass, DialogShape, pageView) else modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = DialogShape,
        containerColor = if (glass != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = foreground,
        textContentColor = foreground,
        tonalElevation = 0.dp
    )
}
