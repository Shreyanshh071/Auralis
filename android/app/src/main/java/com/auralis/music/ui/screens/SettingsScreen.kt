package com.auralis.music.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.auralis.music.ui.profile.AuralisHubView

@Composable
fun SettingsScreen(
    settings: PlayerSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAudioQualityChange: (AudioQuality) -> Unit,
    onToggleGaplessPlayback: (Boolean) -> Unit,
    onToggleSkipSilence: (Boolean) -> Unit,
    onToggleSpatialAudio: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onDismiss: () -> Unit,
    historyRepository: com.auralis.music.domain.repository.HistoryRepository? = null,
    searchRepository: com.auralis.music.domain.repository.SearchRepository? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeDialog by remember { mutableStateOf<SettingsDialogType?>(null) }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (activeDialog != null) {
            activeDialog = null
        } else {
            onDismiss()
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val onBackground = MaterialTheme.colorScheme.onBackground
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── TOP APP BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onBackground,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    fontSize = 22.sp
                )
            }

            // ── SETTINGS LIST ──
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── INTERFACE ──
                item { SettingsCategoryHeader(title = "Interface") }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.Palette,
                        title = "Appearance",
                        onClick = { activeDialog = SettingsDialogType.APPEARANCE }
                    )
                }

                // ── PLAYER & CONTENT ──
                item { SettingsCategoryHeader(title = "Player & Content") }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.PlayArrow,
                        title = "Player and audio",
                        onClick = { activeDialog = SettingsDialogType.PLAYER_AUDIO }
                    )
                }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.Translate,
                        title = "AI lyrics translation",
                        onClick = { activeDialog = SettingsDialogType.LYRICS_TRANSLATION }
                    )
                }

                // ── PRIVACY & STORAGE ──
                item { SettingsCategoryHeader(title = "Privacy & Storage") }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.Security,
                        title = "Privacy",
                        onClick = { activeDialog = SettingsDialogType.PRIVACY }
                    )
                }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.Storage,
                        title = "Storage",
                        onClick = { activeDialog = SettingsDialogType.STORAGE }
                    )
                }

                // ── SYSTEM & ABOUT ──
                item { SettingsCategoryHeader(title = "System & About") }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.SystemUpdate,
                        title = "Updater",
                        onClick = { activeDialog = SettingsDialogType.UPDATER }
                    )
                }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.AppSettingsAlt,
                        title = "System app settings",
                        onClick = {
                            try {
                                val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Unable to open system settings", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.Info,
                        title = "About",
                        onClick = { activeDialog = SettingsDialogType.ABOUT }
                    )
                }
            }
        }

        // ── DIALOG HANDLER ──
        when (activeDialog) {
            SettingsDialogType.APPEARANCE -> {
                AppearanceScreen(
                    onDismiss = { activeDialog = null }
                )
            }

            SettingsDialogType.PLAYER_AUDIO -> {
                var showQualityPicker by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("Player and audio", fontWeight = FontWeight.Bold, color = onBackground) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // Quality
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .clickable { showQualityPicker = !showQualityPicker }
                                    .padding(12.dp)
                            ) {
                                Text("Streaming Quality", color = onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(settings.audioQuality.displayName, color = primaryColor, fontSize = 12.sp)

                                if (showQualityPicker) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    AudioQuality.values().forEach { q ->
                                        val isSel = settings.audioQuality == q
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSel) primaryColor.copy(alpha = 0.15f) else Color.Transparent)
                                                .clickable {
                                                    onAudioQualityChange(q)
                                                    showQualityPicker = false
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(selected = isSel, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = primaryColor))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(q.displayName, color = if (isSel) primaryColor else onBackground, fontSize = 13.sp)
                                                Text(q.description, color = onSurfaceVariant, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // Spatial Audio
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .clickable { onToggleSpatialAudio(!settings.spatialAudio) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("3D Spatial Soundstage", color = onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Dolby Atmos simulation for headphones", color = onSurfaceVariant, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = settings.spatialAudio,
                                    onCheckedChange = onToggleSpatialAudio,
                                    thumbContent = if (settings.spatialAudio) {
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

                            // Gapless Playback
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .clickable { onToggleGaplessPlayback(!settings.gaplessPlayback) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Gapless Playback", color = onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Zero-delay instant track transitions", color = onSurfaceVariant, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = settings.gaplessPlayback,
                                    onCheckedChange = onToggleGaplessPlayback,
                                    thumbContent = if (settings.gaplessPlayback) {
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

                            // Skip Silence
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .clickable { onToggleSkipSilence(!settings.skipSilence) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Remove Silence", color = onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Skip dead air at track boundaries", color = onSurfaceVariant, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = settings.skipSilence,
                                    onCheckedChange = onToggleSkipSilence,
                                    thumbContent = if (settings.skipSilence) {
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
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) {
                            Text("Done", color = primaryColor)
                        }
                    }
                )
            }

            SettingsDialogType.STORAGE -> {
                StorageSettingsScreen(
                    onDismiss = { activeDialog = null }
                )
            }

            SettingsDialogType.LYRICS_TRANSLATION -> {
                AiLyricsTranslationScreen(
                    onDismiss = { activeDialog = null }
                )
            }

            SettingsDialogType.PRIVACY -> {
                PrivacySettingsScreen(
                    onDismiss = { activeDialog = null },
                    historyRepository = historyRepository,
                    searchRepository = searchRepository
                )
            }

            SettingsDialogType.ABOUT -> {
                AboutScreen(
                    onNavigateToUpdater = { activeDialog = SettingsDialogType.UPDATER },
                    onDismiss = { activeDialog = null }
                )
            }

            SettingsDialogType.UPDATER -> {
                UpdaterScreen(
                    onDismiss = { activeDialog = null }
                )
            }

            null -> {}
        }
    }
}

private enum class SettingsDialogType {
    APPEARANCE,
    PLAYER_AUDIO,
    LYRICS_TRANSLATION,
    PRIVACY,
    STORAGE,
    UPDATER,
    ABOUT
}

@Composable
private fun SettingsCategoryHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
