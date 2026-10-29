package com.auralis.music.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.glass.glassContentColor
import com.auralis.music.ui.glass.liquidGlassDialog
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface

/** Uses the page's current palette; glass samples the page across the dialog window. */
@Composable
internal fun PlaylistCreationDialogLayout(
    name: String,
    onNameChange: (String) -> Unit,
    youtubeSync: Boolean,
    onYoutubeSyncChange: (Boolean) -> Unit,
    busy: Boolean,
    saved: Boolean,
    trackCount: Int,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: () -> Unit
) {
    val glass = LocalLiquidGlass.current
    val pageView = LocalView.current
    val accent = if (glass != null) glassContentColor(glass.isDark) else MaterialTheme.dynamicPrimary
    val foreground = if (glass != null) glassContentColor(glass.isDark) else MaterialTheme.dynamicOnSurface
    val surface = MaterialTheme.dynamicSurface
    val colors = MaterialTheme.colorScheme.copy(
        primary = accent,
        onPrimary = if (accent.luminance() > .179f) Color.Black else Color.White,
        onSurface = foreground,
        onSurfaceVariant = foreground.copy(alpha = if (glass != null) .85f else .7f),
        outline = foreground.copy(alpha = .3f),
        outlineVariant = foreground.copy(alpha = .12f)
    )
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .85f).dp
    val shape = RoundedCornerShape(28.dp)
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        MaterialTheme(colorScheme = colors) {
            val keyboard = LocalSoftwareKeyboardController.current
            val focus = LocalFocusManager.current
            Box(Modifier.fillMaxWidth().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("playlist-creation-dialog")
                        .then(if (glass != null) Modifier.liquidGlassDialog(glass, shape, pageView) else Modifier),
                    shape = shape,
                    color = if (glass != null) Color.Transparent else lerp(surface, accent, .035f),
                    contentColor = foreground,
                    border = if (glass == null) BorderStroke(1.dp, colors.outlineVariant) else null,
                    tonalElevation = 0.dp
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = accent)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Create playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Choose where to save it", style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
                            }
                        }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            OutlinedTextField(value = name, onValueChange = onNameChange,
                                modifier = Modifier.fillMaxWidth(), enabled = !busy && !saved,
                                singleLine = true, shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
                                label = { Text("Playlist name") }, placeholder = { Text("Name your playlist") })
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Save to", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                                PlaylistCreationDestination("Auralis only", "Save in your Auralis library",
                                    selected = !youtubeSync, youtube = false, enabled = !busy && !saved,
                                    onClick = { onYoutubeSyncChange(false) })
                                PlaylistCreationDestination("YouTube Music", "Private playlist with two-way sync",
                                    selected = youtubeSync, youtube = true, enabled = !busy && !saved,
                                    onClick = { onYoutubeSyncChange(true) })
                            }
                            if (trackCount > 0) Text(if (trackCount == 1) "1 song will be added" else "$trackCount songs will be added",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                        }
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = onCreate, enabled = name.isNotBlank() && !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                                        color = LocalContentColor.current)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (busy) "Creating…" else if (saved) "Retry connection" else "Create playlist")
                            }
                            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCreationDestination(title: String, subtitle: String, selected: Boolean, youtube: Boolean,
    enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Surface(shape = shape,
        color = if (selected) colors.primary.copy(alpha = .12f) else colors.onSurface.copy(alpha = .035f),
        border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = .6f) else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(shape).selectable(selected, enabled = enabled,
            role = Role.RadioButton, onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (youtube) Icons.Default.Sync else Icons.Default.MusicNote, null,
                tint = if (selected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Default.Check, null, tint = colors.primary, modifier = Modifier.size(20.dp))
            else Spacer(Modifier.size(20.dp))
        }
    }
}
