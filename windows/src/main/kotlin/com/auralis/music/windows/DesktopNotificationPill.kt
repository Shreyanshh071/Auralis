package com.auralis.music.windows

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The phone's compact, dismissible pill treatment instead of a page-wide banner. */
@Composable
internal fun DesktopNotificationPillHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        DesktopNotificationPill(data.visuals.message, onDismiss = data::dismiss)
    }
}

@Composable
internal fun DesktopNotificationPill(message: String, onDismiss: () -> Unit = {}) {
    Surface(onClick = onDismiss, shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(.75.dp, Color.White.copy(alpha = .15f)), shadowElevation = 10.dp,
        modifier = Modifier.widthIn(max = 420.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(message, Modifier.weight(1f, fill = false), fontSize = 12.5.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold)
        }
    }
}
