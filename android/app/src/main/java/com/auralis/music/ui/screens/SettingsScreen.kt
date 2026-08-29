package com.auralis.music.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.background
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeDialog by remember { mutableStateOf<SettingsDialogType?>(null) }
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
                contentPadding = PaddingValues(bottom = 96.dp),
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
                        icon = Icons.Default.Stars,
                        title = "Auralis",
                        onClick = { activeDialog = SettingsDialogType.AURALIS }
                    )
                }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.Podcasts,
                        title = "Stream sources",
                        onClick = { activeDialog = SettingsDialogType.STREAM_SOURCES }
                    )
                }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.Tune,
                        title = "Content",
                        onClick = { activeDialog = SettingsDialogType.CONTENT }
                    )
                }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.Translate,
                        title = "AI lyrics translation",
                        onClick = { activeDialog = SettingsDialogType.LYRICS_TRANSLATION }
                    )
                }

                // ── ANDROID AUTO ──
                item { SettingsCategoryHeader(title = "Android Auto") }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.DirectionsCar,
                        title = "Android Auto",
                        onClick = { activeDialog = SettingsDialogType.ANDROID_AUTO }
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
                        icon = Icons.Default.CleaningServices,
                        title = "Storage & Cache",
                        onClick = { activeDialog = SettingsDialogType.STORAGE }
                    )
                }

                // ── SYSTEM & ABOUT ──
                item { SettingsCategoryHeader(title = "System & About") }
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
                        icon = Icons.Default.HistoryEdu,
                        title = "Changelog",
                        onClick = { activeDialog = SettingsDialogType.CHANGELOG }
                    )
                }
                item {
                    SettingsRowItem(
                        icon = Icons.Default.Info,
                        title = "About",
                        onClick = { activeDialog = SettingsDialogType.ABOUT }
                    )
                }

                item { Spacer(modifier = Modifier.height(30.dp)) }
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
                                    .background(surfaceVariant)
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
                                    .background(surfaceVariant)
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
                                    colors = SwitchDefaults.colors(checkedTrackColor = primaryColor, checkedThumbColor = Color.Black)
                                )
                            }

                            // Gapless Playback
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceVariant)
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
                                    colors = SwitchDefaults.colors(checkedTrackColor = primaryColor, checkedThumbColor = Color.Black)
                                )
                            }

                            // Skip Silence
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceVariant)
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
                                    colors = SwitchDefaults.colors(checkedTrackColor = primaryColor, checkedThumbColor = Color.Black)
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

            SettingsDialogType.AURALIS -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("Auralis Modular Hub", fontWeight = FontWeight.Bold, color = primaryColor) },
                    text = {
                        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                            AuralisHubView(
                                onNavigateToAccount = {
                                    activeDialog = null
                                    onNavigateToAccount()
                                }
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) {
                            Text("Close", color = primaryColor)
                        }
                    }
                )
            }

            SettingsDialogType.STORAGE -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("Storage & Cache", fontWeight = FontWeight.Bold, color = onBackground) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Clear temporary audio stream buffers and image cache to free up memory.", color = onSurfaceVariant, fontSize = 13.sp)
                            Button(
                                onClick = {
                                    onClearCache()
                                    Toast.makeText(context, "Audio & image cache cleared!", Toast.LENGTH_SHORT).show()
                                    activeDialog = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Clear Cache Now", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) {
                            Text("Cancel", color = onSurfaceVariant)
                        }
                    }
                )
            }

            SettingsDialogType.STREAM_SOURCES -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("Stream sources", fontWeight = FontWeight.Bold, color = onBackground) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Active Audio Engine:", color = primaryColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("• YouTube Music Native Stream Extractor (up to 160 kbps transparent Opus)", color = onBackground, fontSize = 13.sp)
                            Text("• WebEngine Fallback (Universal audio redundancy)", color = onSurfaceVariant, fontSize = 13.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) {
                            Text("OK", color = primaryColor)
                        }
                    }
                )
            }

            SettingsDialogType.CONTENT -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("Content", fontWeight = FontWeight.Bold, color = onBackground) },
                    text = {
                        Text("Content preferences, localized chart recommendations, and explicit content filtering are enabled.", color = onSurfaceVariant, fontSize = 13.sp)
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) { Text("OK", color = primaryColor) }
                    }
                )
            }

            SettingsDialogType.LYRICS_TRANSLATION -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("AI lyrics translation", fontWeight = FontWeight.Bold, color = onBackground) },
                    text = {
                        Text("Kinetic word-by-word synchronized lyrics and automatic Romanized transliteration are active.", color = onSurfaceVariant, fontSize = 13.sp)
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) { Text("OK", color = primaryColor) }
                    }
                )
            }

            SettingsDialogType.ANDROID_AUTO -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("Android Auto", fontWeight = FontWeight.Bold, color = onBackground) },
                    text = {
                        Text("Android Auto media session service is configured for background car audio playback.", color = onSurfaceVariant, fontSize = 13.sp)
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) { Text("OK", color = primaryColor) }
                    }
                )
            }

            SettingsDialogType.PRIVACY -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("Privacy", fontWeight = FontWeight.Bold, color = onBackground) },
                    text = {
                        Text("Listening history and search history are stored locally on your device with offline SQLite encryption.", color = onSurfaceVariant, fontSize = 13.sp)
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) { Text("OK", color = primaryColor) }
                    }
                )
            }

            SettingsDialogType.CHANGELOG -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("Changelog", fontWeight = FontWeight.Bold, color = onBackground) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("v2.1.0-stable", fontWeight = FontWeight.Bold, color = primaryColor)
                            Text("• True Zero-Delay Gapless Playback engine", color = onBackground, fontSize = 13.sp)
                            Text("• 3D Spatial Soundstage & Virtualizer", color = onBackground, fontSize = 13.sp)
                            Text("• Audio Quality Stream Selector (Auto, High, Standard, Low)", color = onBackground, fontSize = 13.sp)
                            Text("• Overhauled modular Settings screen", color = onBackground, fontSize = 13.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) { Text("Close", color = primaryColor) }
                    }
                )
            }

            SettingsDialogType.ABOUT -> {
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    containerColor = surfaceColor,
                    title = { Text("About Auralis", fontWeight = FontWeight.Bold, color = onBackground) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Auralis Music Native", fontWeight = FontWeight.Bold, color = primaryColor)
                            Text("Version 2.1.0 (Pure Kotlin + Jetpack Compose)", color = onBackground, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Zero ads, unlimited streaming, and synchronized kinetic lyrics.", color = onSurfaceVariant, fontSize = 12.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { activeDialog = null }) { Text("OK", color = primaryColor) }
                    }
                )
            }

            null -> {}
        }
    }
}

private enum class SettingsDialogType {
    APPEARANCE,
    PLAYER_AUDIO,
    AURALIS,
    STREAM_SOURCES,
    CONTENT,
    LYRICS_TRANSLATION,
    ANDROID_AUTO,
    PRIVACY,
    STORAGE,
    CHANGELOG,
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
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
