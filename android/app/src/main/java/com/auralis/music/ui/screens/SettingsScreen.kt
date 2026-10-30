package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.auralis.music.ui.theme.AuralisPushedPage
import com.auralis.music.ui.theme.auralisPushParent
import com.auralis.music.ui.theme.rememberPushProgress
import com.auralis.music.ui.theme.dynamicPrimary
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.domain.model.PlayerSettings
import com.auralis.music.domain.model.ThemeMode

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

    var showImportedLyrics by remember { mutableStateOf(false) }
    if (showImportedLyrics) com.auralis.music.ui.lyrics.ImportedLyricsDialog(onDismiss = { showImportedLyrics = false })
    val primaryColor = MaterialTheme.dynamicPrimary
    val onBackground = MaterialTheme.colorScheme.onBackground
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // Every section is a full page that pushes over the list.
    val pagedSection = activeDialog
    val sectionPush = rememberPushProgress(pagedSection != null)

    Box(modifier = modifier.fillMaxSize()) {
        SettingsPage(
            title = str(R.string.settings),
            onBack = onDismiss,
            modifier = Modifier.auralisPushParent(sectionPush)
        ) {
            item(key = "group_interface") {
                SettingsGroup(str(R.string.text_interface)) {
                    SettingsNavRow(Icons.Default.Palette, str(R.string.appearance), str(R.string.settings_hint_appearance)) { activeDialog = SettingsDialogType.APPEARANCE }
                }
            }
            item(key = "group_player") {
                SettingsGroup(str(R.string.player_content)) {
                    SettingsNavRow(Icons.Default.PlayArrow, str(R.string.player_and_audio), str(R.string.settings_hint_player)) { activeDialog = SettingsDialogType.PLAYER_AUDIO }
                    SettingsNavRow(Icons.Default.LibraryMusic, str(R.string.content), str(R.string.settings_hint_content)) { activeDialog = SettingsDialogType.CONTENT }
                }
            }
            item(key = "group_lyrics") {
                SettingsGroup("Lyrics") {
                    SettingsNavRow(Icons.Default.Lyrics, "Imported lyrics", str(R.string.settings_hint_imported_lyrics)) { showImportedLyrics = true }
                    SettingsNavRow(Icons.Default.Translate, str(R.string.ai_lyrics_translation), str(R.string.settings_hint_ai_lyrics)) { activeDialog = SettingsDialogType.LYRICS_TRANSLATION }
                }
            }
            item(key = "group_privacy") {
                SettingsGroup(str(R.string.privacy_storage)) {
                    SettingsNavRow(Icons.Default.Security, str(R.string.privacy), str(R.string.settings_hint_privacy)) { activeDialog = SettingsDialogType.PRIVACY }
                    SettingsNavRow(Icons.Default.Storage, str(R.string.storage), str(R.string.settings_hint_storage)) { activeDialog = SettingsDialogType.STORAGE }
                }
            }
            item(key = "group_system") {
                SettingsGroup(str(R.string.system_about)) {
                    SettingsNavRow(Icons.Default.SystemUpdate, str(R.string.updater), str(R.string.settings_hint_updater)) { activeDialog = SettingsDialogType.UPDATER }
                    SettingsNavRow(Icons.Default.AppSettingsAlt, str(R.string.system_app_settings), str(R.string.settings_hint_system)) {
                        try {
                            val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, str(R.string.unable_to_open_system_settings), Toast.LENGTH_SHORT).show()
                        }
                    }
                    SettingsNavRow(Icons.Default.Info, str(R.string.about), str(R.string.settings_hint_about)) { activeDialog = SettingsDialogType.ABOUT }
                }
            }
        }

        // ── FULL-PAGE SECTIONS ──
        AuralisPushedPage(page = pagedSection) { section ->
            when (section) {
                SettingsDialogType.APPEARANCE -> AppearanceScreen(onDismiss = { activeDialog = null })

                SettingsDialogType.PLAYER_AUDIO -> {
                    var showQualityPicker by remember { mutableStateOf(false) }

                    SettingsPage(title = str(R.string.player_and_audio), onBack = { activeDialog = null }) {
                        item(key = "group_player") {
                            SettingsGroup(str(R.string.player)) {
                                SettingsRow(
                                    icon = Icons.Default.HighQuality,
                                    title = str(R.string.streaming_quality),
                                    subtitle = com.auralis.music.ui.i18n.UiLabels.of(settings.audioQuality.displayName),
                                    onClick = { showQualityPicker = !showQualityPicker }
                                ) {
                                    val rotation by androidx.compose.animation.core.animateFloatAsState(if (showQualityPicker) 180f else 0f, tween(220), label = "qualityChevron")
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        null,
                                        tint = onSurfaceVariant,
                                        modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = rotation }
                                    )
                                }
                                AnimatedVisibility(
                                    visible = showQualityPicker,
                                    enter = fadeIn(animationSpec = tween(220)) + expandVertically(
                                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                                        expandFrom = Alignment.Top
                                    ),
                                    exit = fadeOut(animationSpec = tween(160)) + shrinkVertically(
                                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                                        shrinkTowards = Alignment.Top
                                    )
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                                        AudioQuality.values().forEach { q ->
                                            val isSel = settings.audioQuality == q
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .background(if (isSel) primaryColor.copy(alpha = 0.15f) else Color.Transparent)
                                                    .clickable {
                                                        onAudioQualityChange(q)
                                                        showQualityPicker = false
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(com.auralis.music.ui.i18n.UiLabels.of(q.displayName), color = if (isSel) primaryColor else onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                                    Text(com.auralis.music.ui.i18n.UiLabels.of(q.description), color = onSurfaceVariant, fontSize = 12.sp)
                                                }
                                                if (isSel) Icon(Icons.Default.Check, null, tint = primaryColor, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }
                                SettingsSwitchRow(Icons.Default.SurroundSound, str(R.string.t_3d_spatial_soundstage), str(R.string.android_audio_virtualization_when_suppor), settings.spatialAudio, onToggleSpatialAudio)
                                SettingsSwitchRow(Icons.Default.GraphicEq, str(R.string.gapless_playback), str(R.string.preload_the_next_track_for_smoother_tran), settings.gaplessPlayback, onToggleGaplessPlayback)
                                SettingsSwitchRow(Icons.Default.FastForward, str(R.string.remove_silence), str(R.string.skip_quiet_passages_during_playback), settings.skipSilence, onToggleSkipSilence)
                            }
                        }
                        item(key = "group_queue") {
                            SettingsGroup(str(R.string.queue)) {
                                SettingsSwitchRow(Icons.Default.Restore, str(R.string.persistent_queue), str(R.string.restore_your_queue_and_position_after_re), settings.persistentQueue, { value ->
                                    settingsScope.launch { settingsStore.setPersistentQueue(value) }
                                })
                                SettingsSwitchRow(Icons.Default.AllInclusive, str(R.string.auto_load_more_songs), str(R.string.add_recommendations_as_your_radio_queue), settings.autoLoadMore, { value ->
                                    settingsScope.launch { settingsStore.setAutoLoadMore(value) }
                                })
                            }
                        }
                        item(key = "group_misc") {
                            SettingsGroup(str(R.string.misc)) {
                                SettingsSwitchRow(Icons.Default.StopCircle, str(R.string.stop_music_on_task_clear), str(R.string.stop_playback_when_auralis_is_swiped_awa), settings.stopMusicOnTaskClear, { value ->
                                    settingsScope.launch { settingsStore.setStopMusicOnTaskClear(value) }
                                })
                                SettingsSwitchRow(Icons.Default.VolumeOff, str(R.string.pause_music_when_media_is_muted), str(R.string.pause_when_device_media_volume_reaches_z), settings.pauseOnMediaMute, { value ->
                                    settingsScope.launch { settingsStore.setPauseOnMediaMute(value) }
                                })
                                SettingsSwitchRow(Icons.Default.Bluetooth, str(R.string.resume_on_bluetooth_connect), str(R.string.resume_the_current_song_when_bluetooth_a), settings.resumeOnBluetoothConnect, { value ->
                                    settingsScope.launch { settingsStore.setResumeOnBluetoothConnect(value) }
                                })
                                SettingsSwitchRow(Icons.Default.LightMode, str(R.string.keep_screen_on_when_player_is_expanded), str(R.string.keep_the_display_awake_while_the_expande), settings.keepScreenOn, { value ->
                                    settingsScope.launch { settingsStore.setKeepScreenOn(value) }
                                })
                            }
                        }
                    }
                }

                SettingsDialogType.STORAGE -> StorageSettingsScreen(onDismiss = { activeDialog = null })

                SettingsDialogType.LYRICS_TRANSLATION -> AiLyricsTranslationScreen(onDismiss = { activeDialog = null })

                SettingsDialogType.CONTENT -> ContentSettingsScreen(onDismiss = { activeDialog = null })

                SettingsDialogType.PRIVACY -> PrivacySettingsScreen(
                    onDismiss = { activeDialog = null },
                    historyRepository = historyRepository,
                    searchRepository = searchRepository
                )

                SettingsDialogType.ABOUT -> AboutScreen(
                    onNavigateToUpdater = { activeDialog = SettingsDialogType.UPDATER },
                    hasActiveTrack = hasActiveTrack,
                    onDismiss = { activeDialog = null }
                )

                SettingsDialogType.UPDATER -> UpdaterScreen(onDismiss = { activeDialog = null })
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
