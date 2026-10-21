package com.auralis.music.windows.settings

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.Switch

import com.auralis.music.ui.screens.*

import com.auralis.music.ui.i18n.Strings

import com.auralis.music.ui.i18n.str

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.auralis.music.ui.components.toggleable
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.auralis.music.ui.theme.AuralisPushedPage
import com.auralis.music.ui.theme.auralisPushParent
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.rememberPushProgress
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.domain.model.PlayerSettings
import com.auralis.music.domain.model.ThemeMode
import com.auralis.music.ui.profile.AuralisHubView
import com.auralis.music.ui.components.bottomChromePadding

@Composable
fun DesktopSettingsScreen(
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
    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    val settingsStore = com.auralis.music.data.datastore.LocalSettingsStores.current.settings
    val settingsPlatform = com.auralis.music.ui.settings.LocalSettingsPlatform.current
    val settingsScope = rememberCoroutineScope()
    val systemStore = DesktopSystemPreferences.store
    val system by systemStore.settings.collectAsState()
    var showDiscord by remember { mutableStateOf(false) }
    if (showDiscord) com.auralis.music.windows.discord.DiscordSettingsPopup(
        settingsPlatform.discord as? com.auralis.music.windows.discord.WindowsDiscordPresence) { showDiscord = false }
    var activeDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<SettingsDialogType?>(null) }

    com.auralis.music.ui.components.PlatformBackHandler(enabled = true) {
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

    // Every section is a full page that pushes over the list.
    val pagedSection = activeDialog
    val sectionPush = rememberPushProgress(pagedSection != null)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
            Column(modifier = Modifier.fillMaxSize().auralisPushParent(sectionPush)) {
                // ── PAGE TITLE ──
                com.auralis.music.ui.components.PageHeader(str(Strings.settings), onDismiss, Modifier.padding(horizontal = 12.dp))

                // ── SETTINGS LIST ──
                val listState = rememberLazyListState()
                androidx.compose.runtime.key(currentThemeKey) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = bottomChromePadding(includeNavigationBar = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ── INTERFACE ──
                        item(key = "hdr_interface") { SettingsCategoryHeader(title = str(Strings.text_interface), color = cardPrimary) }
                        item(key = "item_appearance") {
                            SettingsRowItem(
                                icon = Icons.Default.Palette,
                                title = str(Strings.appearance),
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.APPEARANCE }
                            )
                        }

                        // ── PLAYER & CONTENT ──
                        item(key = "hdr_player") { SettingsCategoryHeader(title = str(Strings.player_content), color = cardPrimary) }
                        item(key = "item_player_audio") {
                            SettingsRowItem(
                                icon = Icons.Default.PlayArrow,
                                title = str(Strings.player_and_audio),
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.PLAYER_AUDIO }
                            )
                        }
                        item(key = "item_content") {
                            SettingsRowItem(
                                icon = Icons.Default.LibraryMusic,
                                title = str(Strings.content),
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.CONTENT }
                            )
                        }
                        item(key = "item_ai_lyrics") {
                            SettingsRowItem(
                                icon = Icons.Default.Translate,
                                title = str(Strings.ai_lyrics_translation),
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.LYRICS_TRANSLATION }
                            )
                        }

                        // ── PRIVACY & STORAGE ──
                        item(key = "hdr_privacy") { SettingsCategoryHeader(title = str(Strings.privacy_storage), color = cardPrimary) }
                        item(key = "item_privacy") {
                            SettingsRowItem(
                                icon = Icons.Default.Security,
                                title = str(Strings.privacy),
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
                                title = str(Strings.storage),
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = { activeDialog = SettingsDialogType.STORAGE }
                            )
                        }

                        // ── SYSTEM & ABOUT ──
                        item(key = "discord") {
                            SettingsRowItem(icon = Icons.Default.Forum, title = "Discord Rich Presence",
                                cardBackground = cardBackground, borderColor = cardBorder, textColor = cardText,
                                iconTint = cardPrimary, iconBackground = cardIconBg, onClick = { showDiscord = true })
                        }
                        item(key = "hdr_system") { SettingsCategoryHeader(title = str(Strings.system_about), color = cardPrimary) }
                        item(key = "close_to_tray") {
                            PlayerPreferenceToggle("Close to system tray",
                                "Keep Auralis running in the background when closing the window.", system.closeToTray) {
                                systemStore.update { old -> old.copy(closeToTray = it) }
                            }
                        }
                        item(key = "song_notifications") {
                            PlayerPreferenceToggle("Song notifications",
                                "Show a notification when a new song starts while Auralis is in the background.", system.songNotifications) {
                                systemStore.update { old -> old.copy(songNotifications = it) }
                            }
                        }
                        item(key = "item_updater") {
                            SettingsRowItem(
                                icon = Icons.Default.SystemUpdate,
                                title = str(Strings.updater),
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
                                title = str(Strings.system_app_settings),
                                cardBackground = cardBackground,
                                borderColor = cardBorder,
                                textColor = cardText,
                                iconTint = cardPrimary,
                                iconBackground = cardIconBg,
                                onClick = {
                                    val open = settingsPlatform.openSystemAppSettings
                                    if (open != null) {
                                        try {
                                            open()
                                        } catch (e: Exception) {
                                            platformActions.showMessage(str(Strings.unable_to_open_system_settings))
                                        }
                                    } else {
                                        platformActions.showMessage(com.auralis.music.ui.settings.PlatformNotes.noSystemAppSettings)
                                    }
                                }
                            )
                        }
                        item(key = "item_about") {
                            SettingsRowItem(
                                icon = Icons.Default.Info,
                                title = str(Strings.about),
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

            // ── FULL-PAGE SECTIONS ──
            AuralisPushedPage(page = pagedSection) { section ->
                when (section) {
                    SettingsDialogType.APPEARANCE -> {
                        DesktopAppearanceSettings(
                            onDismiss = { activeDialog = null }
                        )
                    }

                    SettingsDialogType.PLAYER_AUDIO -> DesktopAudioSettings(onDismiss = { activeDialog = null })

                    SettingsDialogType.STORAGE -> {
                        DesktopStorageSettings(
                            onDismiss = { activeDialog = null }
                        )
                    }

                    SettingsDialogType.LYRICS_TRANSLATION -> {
                        AiLyricsTranslationScreen(
                            onDismiss = { activeDialog = null }
                        )
                    }

                    SettingsDialogType.CONTENT -> {
                        ContentSettingsScreen(onDismiss = { activeDialog = null })
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
                }
            }
    }
}

private enum class SettingsDialogType {
    APPEARANCE,
    CONTENT,
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
        text = desktopText(title),
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
                text = desktopText(title),
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
