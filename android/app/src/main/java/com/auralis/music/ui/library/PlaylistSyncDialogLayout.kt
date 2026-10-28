package com.auralis.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.glass.liquidGlassDialog

/** Solid material by default; a page-aligned refractive surface in liquid glass mode. */
@Composable
internal fun PlaylistSyncDialogLayout(
    playlistTitle: String,
    onDismissRequest: () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {}
) {
    val glass = LocalLiquidGlass.current
    val pageView = LocalView.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .85f).dp
    val shape = RoundedCornerShape(if (glass != null) 32.dp else 24.dp)
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val surface = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight)
            .clip(shape).then(if (glass != null) Modifier.liquidGlassDialog(glass, shape, pageView)
            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), shape))
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Surface(modifier = surface, shape = shape, tonalElevation = 0.dp,
                color = if (glass != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(if (glass != null) 23.dp else 14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (glass != null) .20f else .12f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Playlist sync", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(playlistTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { text() }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        confirmButton()
                        dismissButton()
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaylistSyncSection(content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalLiquidGlass.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(if (glass != null) MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
            else MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
internal fun PlaylistSyncConnection(service: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        Text("Auralis", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Icon(Icons.Default.SyncAlt, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(service, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PlaylistSyncStatus(message: String, problem: Boolean = false, busy: Boolean = false) {
    val accent = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = .09f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(if (problem) Icons.Default.Warning else Icons.Default.CheckCircle,
            null, Modifier.size(18.dp), tint = accent)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
