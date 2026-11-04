package com.auralis.music.ui.screens

import com.auralis.music.ui.glass.pageGlassActionColor
import com.auralis.music.ui.glass.pageGlassSelection
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import com.auralis.music.ui.i18n.str

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.data.datastore.DiscordRpcDataStore
import com.auralis.music.data.network.discord.DiscordGatewayManager
import com.auralis.music.domain.model.DiscordRpcSettings
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.AuralisPlayerSlider
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface

// Dynamic Palette Synced with MaterialTheme & System Light/Dark Theme
private val THEME_BG: Color
    @Composable get() = MaterialTheme.dynamicBackground

private val CARD_BG: Color
    @Composable get() = MaterialTheme.dynamicSurface

private val PILL_BG: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest

private val PEACH_ACCENT: Color
    @Composable get() = MaterialTheme.dynamicPrimary

private val TEXT_PRIMARY: Color
    @Composable get() = MaterialTheme.dynamicOnBackground

private val TEXT_SECONDARY: Color
    @Composable get() = MaterialTheme.dynamicOnSurface

private val BUTTON_DARK_TEXT: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimary

private val BUTTON_SECONDARY_BG: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordIntegrationScreen(
    currentTrack: Track? = null,
    isPlaying: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val player = remember { com.auralis.music.data.service.AuralisAudioPlayer.getInstance(context) }
    val activeTrack by player.currentTrack.collectAsState()
    val activeIsPlaying by player.isPlaying.collectAsState()
    val activePos by player.playbackPositionMs.collectAsState()
    val activeDuration by player.durationMs.collectAsState()

    val effectiveTrack = activeTrack ?: currentTrack
    val effectiveIsPlaying = activeIsPlaying || isPlaying
    val effectivePos = activePos
    val effectiveDuration = activeDuration

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFrac by remember { mutableFloatStateOf(0f) }

    val dataStore = remember { DiscordRpcDataStore(context) }
    val settings by dataStore.settingsFlow.collectAsState(initial = DiscordRpcSettings())
    val scope = rememberCoroutineScope()

    // Dialog States
    var isAuthorizing by remember { mutableStateOf(false) }

    var showStatusDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showActivityNameDialog by remember { mutableStateOf(false) }
    var customActivityNameInput by remember { mutableStateOf(settings.activityName) }
    var showActivityDetailsDialog by remember { mutableStateOf(false) }
    var showActivityStateDialog by remember { mutableStateOf(false) }
    var showActivityTypeDialog by remember { mutableStateOf(false) }
    var showLargeImageDialog by remember { mutableStateOf(false) }
    var showLargeTextDialog by remember { mutableStateOf(false) }
    var showSmallImageDialog by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            showStatusDialog -> showStatusDialog = false
            showIntervalDialog -> showIntervalDialog = false
            showActivityNameDialog -> showActivityNameDialog = false
            showActivityDetailsDialog -> showActivityDetailsDialog = false
            showActivityStateDialog -> showActivityStateDialog = false
            showActivityTypeDialog -> showActivityTypeDialog = false
            showLargeImageDialog -> showLargeImageDialog = false
            showLargeTextDialog -> showLargeTextDialog = false
            showSmallImageDialog -> showSmallImageDialog = false
            else -> onDismiss()
        }
    }

    SettingsPage(title = str(R.string.discord_integration), onBack = onDismiss) {
            item(key = "discord_account", contentType = "account") {
                SettingsGroup(null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .09f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (settings.isLoggedIn && settings.discordAvatarUrl.isNotBlank()) {
                                ArtworkCard(url = settings.discordAvatarUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 32.dp)
                            } else {
                                Icon(painter = painterResource(id = R.drawable.ic_discord), contentDescription = str(R.string.discord),
                                    tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(30.dp))
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (settings.isLoggedIn && settings.discordUsername.isNotBlank()) settings.discordUsername else str(R.string.discord),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 19.sp
                            )
                            if (!settings.isLoggedIn) Text(
                                text = str(R.string.discord_presence_authorization_required),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }
                    if (isAuthorizing && !settings.isLoggedIn) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = PEACH_ACCENT, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(str(R.string.waiting_for_discord_authorization), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        }
                    }
                    SettingsSwitchRow(Icons.Default.Refresh, str(R.string.enable_rich_presence), checked = settings.enableRichPresence, onCheckedChange = { checked ->
                        scope.launch { dataStore.setEnableRichPresence(checked) }
                    })
                }
            }
            item(key = "discord_authorize", contentType = "action") {
                val onAuthClick = {
                    if (settings.isLoggedIn) {
                        scope.launch {
                            dataStore.setLoggedIn(false)
                            dataStore.setEnableRichPresence(false)
                        }
                        isAuthorizing = false
                        DiscordGatewayManager.getInstance(context).disconnect()
                        Toast.makeText(context, str(R.string.logged_out_of_discord), Toast.LENGTH_SHORT).show()
                    } else {
                        isAuthorizing = true
                        DiscordGatewayManager.getInstance(context).beginAuthorization { success, username, detail ->
                            isAuthorizing = false
                            val message = if (success) {
                                str(R.string.connected_to_discord_as_x, username.ifBlank { "your account" })
                            } else {
                                detail
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                    Unit
                }
                val danger = MaterialTheme.colorScheme.error
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .then(
                            if (settings.isLoggedIn) Modifier.clip(CircleShape).background(danger.copy(alpha = .16f))
                            else Modifier.pageGlassSelection(CircleShape, true, PEACH_ACCENT, PEACH_ACCENT, !isAuthorizing)
                        )
                        .clickable(enabled = !isAuthorizing, onClick = onAuthClick),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = str(if (settings.isLoggedIn) R.string.disconnect_discord_account else R.string.open_discord_authorization),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (settings.isLoggedIn) danger else pageGlassActionColor(BUTTON_DARK_TEXT)
                    )
                }
            }
            item(key = "discord_options", contentType = "group") {
                SettingsGroup(str(R.string.options)) {
                    SettingsRow(Icons.Default.Refresh, str(R.string.refresh), str(R.string.manually_refresh_discord_rich_presence), onClick = {
                        DiscordGatewayManager.getInstance(context).pushPresence()
                        Toast.makeText(context, str(R.string.discord_rich_presence_refreshed), Toast.LENGTH_SHORT).show()
                    })
                }
            }
            item(key = "discord_connection", contentType = "group") {
                SettingsGroup(str(R.string.connection)) {
                    DiscordSettingRowItem(Icons.Default.Refresh, str(R.string.activity_status), settings.activityStatus) { showStatusDialog = true }
                    DiscordSettingRowItem(
                        Icons.Default.Timer,
                        str(R.string.update_interval),
                        when (settings.updateIntervalSeconds) {
                            0 -> "Disabled"
                            60 -> "1m"
                            300 -> "5m"
                            else -> "${settings.updateIntervalSeconds}s"
                        }
                    ) { showIntervalDialog = true }
                }
            }
            item(key = "discord_activity", contentType = "group") {
                SettingsGroup(str(R.string.activity_content)) {
                    DiscordSettingRowItem(Icons.Default.TextFields, str(R.string.activity_name), settings.activityName) {
                        customActivityNameInput = settings.activityName
                        showActivityNameDialog = true
                    }
                    DiscordSettingRowItem(Icons.Default.TextFields, str(R.string.activity_details), settings.activityDetails) { showActivityDetailsDialog = true }
                    DiscordSettingRowItem(Icons.Default.TextFields, str(R.string.activity_state), settings.activityState) { showActivityStateDialog = true }
                    SettingsSwitchRow(Icons.Default.Pause, str(R.string.show_rpc_when_paused), str(R.string.if_enabled_rich_presence_will_remain_vis), settings.showRpcWhenPaused, { checked ->
                        scope.launch { dataStore.setShowRpcWhenPaused(checked) }
                    })
                    DiscordSettingRowItem(Icons.Default.Headphones, str(R.string.activity_type), settings.activityType) { showActivityTypeDialog = true }
                }
            }
            item(key = "discord_images", contentType = "group") {
                SettingsGroup(str(R.string.image_options)) {
                    DiscordSettingRowItem(Icons.Default.Image, str(R.string.large_image), settings.largeImage) { showLargeImageDialog = true }
                    DiscordSettingRowItem(Icons.Default.TextFields, str(R.string.large_text), settings.largeText) { showLargeTextDialog = true }
                    DiscordSettingRowItem(Icons.Default.Image, str(R.string.small_image), settings.smallImage) { showSmallImageDialog = true }
                }
            }

            // ── 6. PREVIEW SECTION ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = str(R.string.preview),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )

                    com.auralis.music.ui.glass.OptionalGlassCard(
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .07f),
                        border = null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = settings.activityType,
                                color = PEACH_ACCENT,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier.size(76.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val previewArtwork = effectiveTrack?.thumbnail?.ifBlank { null }
                                        if (settings.largeImage == "App icon" || settings.largeImage == "App logo") {
                                            Image(
                                                painter = painterResource(id = R.drawable.ic_auralis_logo),
                                                contentDescription = str(R.string.auralis_app_logo),
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(10.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else if (settings.largeImage == "None") {
                                            // None selected
                                        } else if (previewArtwork != null) {
                                            ArtworkCard(
                                                url = previewArtwork,
                                                modifier = Modifier.fillMaxSize(),
                                                cornerRadius = 16.dp
                                            )
                                        } else {
                                            Image(
                                                painter = painterResource(id = R.drawable.ic_auralis_logo),
                                                contentDescription = str(R.string.auralis_app_logo),
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(10.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                        }
                                    }

                                    // Small Image Badge (if enabled)
                                    if (settings.smallImage != "None") {
                                        val previewArtwork = effectiveTrack?.thumbnail?.ifBlank { null }
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .offset(x = 4.dp, y = 4.dp)
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(CARD_BG)
                                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                                .padding(2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (settings.smallImage == "App logo" || settings.smallImage == "App icon" || settings.smallImage == "Play state") {
                                                Image(
                                                    painter = painterResource(id = R.drawable.ic_auralis_logo),
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Fit
                                                )
                                            } else if (settings.smallImage == "Artist artwork" && previewArtwork != null) {
                                                ArtworkCard(
                                                    url = previewArtwork,
                                                    modifier = Modifier.fillMaxSize(),
                                                    cornerRadius = 13.dp
                                                )
                                            } else {
                                                Image(
                                                    painter = painterResource(id = R.drawable.ic_auralis_logo),
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Fit
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = settings.activityName.ifBlank { "Auralis" },
                                        fontWeight = FontWeight.Bold,
                                        color = TEXT_PRIMARY,
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = effectiveTrack?.title ?: str(R.string.opt_song_title),
                                        color = TEXT_SECONDARY,
                                        fontSize = 13.5.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = effectiveTrack?.artist ?: str(R.string.opt_artist_name),
                                        color = TEXT_SECONDARY,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = effectiveTrack?.album ?: effectiveTrack?.title ?: "Album name",
                                        color = TEXT_SECONDARY.copy(alpha = 0.75f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Live Flowing Wavy Seekbar with Elapsed and Total Duration
                            val currentProgress = if (effectiveDuration > 0) (effectivePos.toFloat() / effectiveDuration).coerceIn(0f, 1f) else 0f
                            val displayProgress = if (isScrubbing) scrubFrac else currentProgress
                            val displayPosMs = if (isScrubbing) (scrubFrac * effectiveDuration).toLong() else effectivePos

                            AuralisPlayerSlider(
                                value = displayProgress,
                                onValueChange = { frac ->
                                    isScrubbing = true
                                    scrubFrac = frac
                                },
                                onValueChangeFinished = {
                                    isScrubbing = false
                                    player.seekTo((scrubFrac * effectiveDuration).toLong())
                                },
                                isPlaying = effectiveIsPlaying,
                                currentPosMs = displayPosMs,
                                totalDurationMs = effectiveDuration,
                                sliderStyle = "Wavy",
                                activeTrackColor = PEACH_ACCENT,
                                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                                thumbColor = PEACH_ACCENT,
                                textColor = TEXT_SECONDARY,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        val ytUrl = if (effectiveTrack != null && effectiveTrack.id.isNotBlank()) {
                                            "https://music.youtube.com/watch?v=${effectiveTrack.id}"
                                        } else {
                                            "https://music.youtube.com/"
                                        }
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ytUrl)))
                                    },
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PILL_BG
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                ) {
                                    Text(
                                        text = str(R.string.listen_on_youtube_music),
                                        color = TEXT_PRIMARY,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                }

                                Button(
                                    onClick = {
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Shreyanshh071/Auralis"))
                                        context.startActivity(browserIntent)
                                    },
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PEACH_ACCENT
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                ) {
                                    Text(
                                        text = str(R.string.go_to_auralis),
                                        color = BUTTON_DARK_TEXT,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }


    // ── CONFIGURATION PICKER DIALOGS ──

    // Pickers with DiscordOptionPickerBottomSheet
    if (showStatusDialog) {
        DiscordOptionPickerBottomSheet(
            title = str(R.string.activity_status),
            options = listOf("Online", "Idle", "Do Not Disturb", "Invisible"),
            selected = settings.activityStatus,
            onSelect = {
                scope.launch { dataStore.setActivityStatus(it) }
            },
            onDismiss = { showStatusDialog = false }
        )
    }

    if (showIntervalDialog) {
        val intervalOptions = listOf("5s", "10s", "20s", "50s", "1m", "5m", "Custom", "Disabled")
        val currentSelected = when (settings.updateIntervalSeconds) {
            0 -> "Disabled"
            60 -> "1m"
            300 -> "5m"
            5 -> "5s"
            10 -> "10s"
            20 -> "20s"
            50 -> "50s"
            else -> "Custom"
        }
        var showCustomIntervalDialog by remember { mutableStateOf(false) }
        var customIntervalInput by remember { mutableStateOf(if (settings.updateIntervalSeconds > 0) settings.updateIntervalSeconds.toString() else "20") }

        if (showCustomIntervalDialog) {
            com.auralis.music.ui.components.AuralisAlertDialog(
                onDismissRequest = { showCustomIntervalDialog = false },
                title = { Text(str(R.string.custom_interval), fontWeight = FontWeight.Bold, color = TEXT_PRIMARY) },
                text = {
                    Column {
                        Text(str(R.string.enter_update_interval_in_seconds_e_g_3_t), color = TEXT_SECONDARY, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customIntervalInput,
                            onValueChange = { customIntervalInput = it.filter { c -> c.isDigit() } },
                            label = { Text(str(R.string.seconds)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PEACH_ACCENT,
                                unfocusedBorderColor = TEXT_SECONDARY.copy(alpha = 0.4f),
                                focusedTextColor = TEXT_PRIMARY,
                                unfocusedTextColor = TEXT_PRIMARY,
                                focusedLabelColor = PEACH_ACCENT,
                                unfocusedLabelColor = TEXT_SECONDARY
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val secs = customIntervalInput.toIntOrNull()?.coerceIn(3, 3600) ?: 20
                        scope.launch { dataStore.setUpdateInterval(secs) }
                        showCustomIntervalDialog = false
                        showIntervalDialog = false
                    }) {
                        Text(str(R.string.save), color = PEACH_ACCENT, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomIntervalDialog = false }) {
                        Text(str(R.string.cancel), color = TEXT_SECONDARY)
                    }
                }
            )
        } else {
            DiscordOptionPickerBottomSheet(
                title = str(R.string.update_interval),
                options = intervalOptions,
                selected = currentSelected,
                onSelect = { selectedOption ->
                    when (selectedOption) {
                        "5s" -> {
                            scope.launch { dataStore.setUpdateInterval(5) }
                            showIntervalDialog = false
                        }
                        "10s" -> {
                            scope.launch { dataStore.setUpdateInterval(10) }
                            showIntervalDialog = false
                        }
                        "20s" -> {
                            scope.launch { dataStore.setUpdateInterval(20) }
                            showIntervalDialog = false
                        }
                        "50s" -> {
                            scope.launch { dataStore.setUpdateInterval(50) }
                            showIntervalDialog = false
                        }
                        "1m" -> {
                            scope.launch { dataStore.setUpdateInterval(60) }
                            showIntervalDialog = false
                        }
                        "5m" -> {
                            scope.launch { dataStore.setUpdateInterval(300) }
                            showIntervalDialog = false
                        }
                        "Disabled" -> {
                            scope.launch { dataStore.setUpdateInterval(0) }
                            showIntervalDialog = false
                        }
                        "Custom" -> {
                            showCustomIntervalDialog = true
                        }
                    }
                },
                onDismiss = { showIntervalDialog = false }
            )
        }
    }

    if (showActivityNameDialog) {
        com.auralis.music.ui.components.AuralisAlertDialog(
            onDismissRequest = { showActivityNameDialog = false },
            title = { Text(str(R.string.activity_name_2), fontWeight = FontWeight.Bold, color = TEXT_PRIMARY) },
            text = {
                OutlinedTextField(
                    value = customActivityNameInput,
                    onValueChange = { customActivityNameInput = it },
                    label = { Text(str(R.string.app_name_in_discord)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PEACH_ACCENT,
                        unfocusedBorderColor = TEXT_SECONDARY.copy(alpha = 0.4f),
                        focusedTextColor = TEXT_PRIMARY,
                        unfocusedTextColor = TEXT_PRIMARY
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { dataStore.setActivityName(customActivityNameInput.trim().ifBlank { "Auralis" }) }
                        showActivityNameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PEACH_ACCENT)
                ) {
                    Text(str(R.string.save), color = BUTTON_DARK_TEXT, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showActivityNameDialog = false }) {
                    Text(str(R.string.cancel), color = TEXT_SECONDARY)
                }
            }
        )
    }

    if (showActivityDetailsDialog) {
        DiscordOptionPickerBottomSheet(
            title = str(R.string.activity_details),
            options = listOf("Artist name", "Album name", "Song title", "Auralis"),
            selected = settings.activityDetails,
            onSelect = {
                scope.launch { dataStore.setActivityDetails(it) }
            },
            onDismiss = { showActivityDetailsDialog = false }
        )
    }

    if (showActivityStateDialog) {
        DiscordOptionPickerBottomSheet(
            title = str(R.string.activity_state),
            options = listOf("Artist name", "Album name", "Song title", "Auralis"),
            selected = settings.activityState,
            onSelect = {
                scope.launch { dataStore.setActivityState(it) }
            },
            onDismiss = { showActivityStateDialog = false }
        )
    }

    if (showActivityTypeDialog) {
        DiscordOptionPickerBottomSheet(
            title = str(R.string.activity_type),
            options = listOf("Listening", "Playing", "Streaming", "Competing"),
            selected = settings.activityType,
            onSelect = {
                scope.launch { dataStore.setActivityType(it) }
            },
            onDismiss = { showActivityTypeDialog = false }
        )
    }

    if (showLargeImageDialog) {
        DiscordOptionPickerBottomSheet(
            title = str(R.string.large_image),
            options = listOf("Album artwork", "App icon", "None"),
            selected = settings.largeImage,
            onSelect = {
                scope.launch { dataStore.setLargeImage(it) }
            },
            onDismiss = { showLargeImageDialog = false }
        )
    }

    if (showLargeTextDialog) {
        DiscordOptionPickerBottomSheet(
            title = str(R.string.large_text),
            options = listOf("Album name", "Song title", "Auralis", "None"),
            selected = settings.largeText,
            onSelect = {
                scope.launch { dataStore.setLargeText(it) }
            },
            onDismiss = { showLargeTextDialog = false }
        )
    }

    if (showSmallImageDialog) {
        DiscordOptionPickerBottomSheet(
            title = str(R.string.small_image),
            options = listOf("Artist artwork", "Play state", "App logo", "None"),
            selected = settings.smallImage,
            onSelect = {
                scope.launch { dataStore.setSmallImage(it) }
            },
            onDismiss = { showSmallImageDialog = false }
        )
    }
}

@Composable
private fun DiscordSettingRowItem(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) = SettingsNavRow(icon, title, value, onClick = onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscordOptionPickerBottomSheet(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CARD_BG,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PEACH_ACCENT.copy(alpha = 0.5f))
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TEXT_PRIMARY,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
            )

            options.forEach { option ->
                val isSelected = option.equals(selected, ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = if (isSelected) PEACH_ACCENT else PILL_BG,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable {
                            onSelect(option)
                            onDismiss()
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = com.auralis.music.ui.i18n.UiLabels.of(option),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) BUTTON_DARK_TEXT else TEXT_PRIMARY,
                            fontSize = 16.sp
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = BUTTON_DARK_TEXT,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

