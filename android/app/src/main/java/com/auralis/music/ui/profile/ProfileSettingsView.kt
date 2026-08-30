package com.auralis.music.ui.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.domain.model.PlayerSettings
import com.auralis.music.domain.model.ThemeMode

@Composable
fun ProfileSettingsView(
    settings: PlayerSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAudioQualityChange: (AudioQuality) -> Unit,
    onToggleGaplessPlayback: (Boolean) -> Unit,
    onToggleSkipSilence: (Boolean) -> Unit,
    onToggleSpatialAudio: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onNavigateToAccount: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showQualityDialog by remember { mutableStateOf(false) }
    var showAppearanceScreen by remember { mutableStateOf(false) }
    var showUpdaterScreen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── 1. APPEARANCE SECTION ──
        SettingsSectionHeader(title = "Appearance", icon = Icons.Default.Palette)

        SettingsClickableCard(
            title = "Appearance Settings",
            subtitle = "Theme, Mini-player, Player, Lyrics, Gestures & Layout",
            icon = Icons.Default.ColorLens,
            onClick = { showAppearanceScreen = true }
        )

        // ── 2. AUDIO QUALITY SECTION ──
        SettingsSectionHeader(title = "Audio Quality", icon = Icons.Default.GraphicEq)

        SettingsClickableCard(
            title = "Streaming Quality",
            subtitle = "${settings.audioQuality.displayName} • ${settings.audioQuality.description}",
            icon = Icons.Default.HighQuality,
            onClick = { showQualityDialog = true }
        )

        // ── 3. SPATIAL AUDIO SECTION ──
        SettingsSectionHeader(title = "Spatial Audio", icon = Icons.Default.Headphones)

        SettingsSwitchCard(
            title = "3D Spatial Soundstage",
            subtitle = "Virtualize wide 3D surround sound on headphones (Dolby Atmos simulation)",
            icon = Icons.Default.SurroundSound,
            isChecked = settings.spatialAudio,
            onCheckedChange = onToggleSpatialAudio
        )

        // ── 4. PLAYBACK SECTION ──
        SettingsSectionHeader(title = "Playback", icon = Icons.Default.PlayCircleOutline)

        SettingsSwitchCard(
            title = "Gapless Playback",
            subtitle = "Zero-delay instant transitions between songs without pauses or silence",
            icon = Icons.Default.SyncAlt,
            isChecked = settings.gaplessPlayback,
            onCheckedChange = onToggleGaplessPlayback
        )

        SettingsSwitchCard(
            title = "Remove Silence",
            subtitle = "Automatically skip dead air and silent intros/outros",
            icon = Icons.Default.VolumeOff,
            isChecked = settings.skipSilence,
            onCheckedChange = onToggleSkipSilence
        )

        // ── 5. STORAGE SECTION ──
        SettingsSectionHeader(title = "Storage", icon = Icons.Default.Storage)

        SettingsClickableCard(
            title = "Clear Cache",
            subtitle = "Free up temporary stream buffers and cached artwork images",
            icon = Icons.Default.CleaningServices,
            onClick = {
                onClearCache()
                Toast.makeText(context, "Audio and artwork cache cleared!", Toast.LENGTH_SHORT).show()
            }
        )

        // ── 6. UPDATER SECTION ──
        SettingsSectionHeader(title = "Updater", icon = Icons.Default.SystemUpdate)

        SettingsClickableCard(
            title = "Updater",
            subtitle = "Check for updates and configure auto-update settings",
            icon = Icons.Default.SystemUpdate,
            onClick = { showUpdaterScreen = true }
        )

        // ── 7. AURALIS SECTION ──
        SettingsSectionHeader(title = "Auralis", icon = Icons.Default.Stars)

        AuralisHubView(
            onNavigateToAccount = onNavigateToAccount
        )
    }

    if (showUpdaterScreen) {
        com.auralis.music.ui.screens.UpdaterScreen(
            onDismiss = { showUpdaterScreen = false }
        )
        return
    }

    // ── QUALITY DIALOG ──
    if (showQualityDialog) {
        val qualities = remember { AudioQuality.values().toList() }
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Select Audio Quality",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    qualities.forEach { q ->
                        val isSelected = settings.audioQuality == q
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    onAudioQualityChange(q)
                                    showQualityDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = q.displayName,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = q.description,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // ── APPEARANCE SCREEN FULL OVERLAY ──
    if (showAppearanceScreen) {
        com.auralis.music.ui.screens.AppearanceScreen(
            onDismiss = { showAppearanceScreen = false }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String, icon: ImageVector) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = primaryColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = primaryColor,
            fontSize = 15.sp
        )
    }
}

@Composable
fun SettingsClickableCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(surfaceVariant)
            .border(1.dp, outline.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = onSurface.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = onSurfaceVariant.copy(alpha = 0.60f)
            )
        }
    }
}

@Composable
fun SettingsSwitchCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(surfaceVariant)
            .border(1.dp, outline.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!isChecked) }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isChecked) primaryColor else onSurface.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                thumbContent = if (isChecked) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                } else null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = primaryColor,
                    checkedTrackColor = primaryColor.copy(alpha = 0.45f),
                    checkedBorderColor = primaryColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }
    }
}
