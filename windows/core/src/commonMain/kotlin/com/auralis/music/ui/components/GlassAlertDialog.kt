package com.auralis.music.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Desktop supplies a page-backed glass host; other platforms keep the standard dialog. */
val LocalGlassDialogHost = staticCompositionLocalOf<(@Composable (() -> Unit, @Composable () -> Unit) -> Unit)?> { null }

@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation
) {
    val host = LocalGlassDialogHost.current
    if (host == null) {
        AlertDialog(onDismissRequest, confirmButton, modifier, dismissButton = dismissButton,
            title = title, text = text, shape = shape, containerColor = containerColor,
            tonalElevation = tonalElevation)
        return
    }
    host(onDismissRequest) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            title?.let { ProvideTextStyle(MaterialTheme.typography.headlineSmall) { it() } }
            text?.let { ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
