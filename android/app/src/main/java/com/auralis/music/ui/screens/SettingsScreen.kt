package com.auralis.music.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
    hasActiveTrack: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { com.auralis.music.data.datastore.SettingsDataStore(context) }
    val settingsScope = rememberCoroutineScope()
    var activeDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<SettingsDialogType?>(null) }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (activeDialog != null) {
            activeDialog = null
        } else {
            onDismiss()
        }
    }

    val themePrimary = MaterialTheme.dynamicPrimary
    val themeBackground = MaterialTheme.dynamicBackground
    val themeSurface = MaterialTheme.dynamicSurface
    val currentThemeKey = "${themeBackground.toArgb()}_${MaterialTheme.colorScheme.surfaceVariant.toArgb()}_${themeSurface.toArgb()}_${themePrimary.toArgb()}"
    val primaryColor = themePrimary
    val onBackground = MaterialTheme.colorScheme.onBackground
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = themeSurface
    val isDark = themeBackground.luminance() < 0.5f
    val cardBackground = themeSurface
    val cardBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.5f else 0.8f)
    val cardText = MaterialTheme.colorScheme.onSurface
    val cardPrimary = themePrimary
    val cardIconBg = themePrimary.copy(alpha = if (isDark) 0.12f else 0.16f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBackground)
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
                val listBottomPadding = if (hasActiveTrack) 130.dp else 16.dp
                val listState = rememberLazyListState()
                androidx.compose.runtime.key(currentThemeKey) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(bottom = listBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ── INTERFACE ──
                        item(key = "hdr_interface") { SettingsCategoryHeader(title = "Interface", color = cardPrimary) }
                        item(key = "item_appearance") {
                            SettingsRowItem(
                                icon = Icons.Default.Palette,
                                title = "Appearance",
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.APPEARANCE }
                            )
                        }

                        // ── PLAYER & CONTENT ──
                        item(key = "hdr_player") { SettingsCategoryHeader(title = "Player & Content", color = cardPrimary) }
                        item(key = "item_player_audio") {
                            SettingsRowItem(
                                icon = Icons.Default.PlayArrow,
                                title = "Player and audio",
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.PLAYER_AUDIO }
                            )
                        }
                        item(key = "item_ai_lyrics") {
                            SettingsRowItem(
                                icon = Icons.Default.Translate,
                                title = "AI lyrics translation",
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.LYRICS_TRANSLATION }
                            )
                        }

                        // ── PRIVACY & STORAGE ──
                        item(key = "hdr_privacy") { SettingsCategoryHeader(title = "Privacy & Storage", color = cardPrimary) }
                        item(key = "item_privacy") {
                            SettingsRowItem(
                                icon = Icons.Default.Security,
                                title = "Privacy",
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.PRIVACY }
                            )
                        }
                        item(key = "item_storage") {
                            SettingsRowItem(
                                icon = Icons.Default.Storage,
                                title = "Storage",
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.STORAGE }
                            )
                        }

                        // ── SYSTEM & ABOUT ──
                        item(key = "hdr_system") { SettingsCategoryHeader(title = "System & About", color = cardPrimary) }
                        item(key = "item_updater") {
                            SettingsRowItem(
                                icon = Icons.Default.SystemUpdate,
                                title = "Updater",
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.UPDATER }
                            )
                        }
                        item(key = "item_sys_app") {
                            SettingsRowItem(
                                icon = Icons.Default.AppSettingsAlt,
                                title = "System app settings",
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
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
                        item(key = "item_about") {
                            SettingsRowItem(
                                icon = Icons.Default.Info,
                                title = "About",
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.ABOUT }
                            )
                        }
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
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            SettingsCategoryHeader("Player", primaryColor)
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
                            SettingsCategoryHeader("Queue", primaryColor)
                            PlayerPreferenceToggle("Persistent queue", "Restore your queue and position after restarting", settings.persistentQueue) {
                                settingsScope.launch { settingsStore.setPersistentQueue(it) }
                            }
                            PlayerPreferenceToggle("Auto load more songs", "Add recommendations as your radio queue runs low", settings.autoLoadMore) {
                                settingsScope.launch { settingsStore.setAutoLoadMore(it) }
                            }
                            SettingsCategoryHeader("Misc", primaryColor)
                            PlayerPreferenceToggle("Stop music on task clear", "Stop playback when Auralis is swiped away from recent apps", settings.stopMusicOnTaskClear) {
                                settingsScope.launch { settingsStore.setStopMusicOnTaskClear(it) }
                            }
                            PlayerPreferenceToggle("Pause music when media is muted", "Pause when device media volume reaches zero", settings.pauseOnMediaMute) {
                                settingsScope.launch { settingsStore.setPauseOnMediaMute(it) }
                            }
                            PlayerPreferenceToggle("Resume on Bluetooth connect", "Resume the current song when Bluetooth audio connects", settings.resumeOnBluetoothConnect) {
                                settingsScope.launch { settingsStore.setResumeOnBluetoothConnect(it) }
                            }
                            PlayerPreferenceToggle("Keep screen on when player is expanded", "Keep the display awake while the expanded player is playing", settings.keepScreenOn) {
                                settingsScope.launch { settingsStore.setKeepScreenOn(it) }
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
                    hasActiveTrack = hasActiveTrack,
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
private fun SettingsCategoryHeader(
    title: String,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text = title,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp)
    )
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    cardBackground: Color,
    borderColor: Color,
    textColor: Color,
    iconTint: Color,
    iconBackground: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBackground,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = title,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.5.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PlayerPreferenceToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
