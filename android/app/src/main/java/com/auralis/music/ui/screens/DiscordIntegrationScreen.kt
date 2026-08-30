package com.auralis.music.ui.screens

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
import android.webkit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Dynamic Palette Synced with MaterialTheme & System Light/Dark Theme
private val THEME_BG: Color
    @Composable get() = MaterialTheme.colorScheme.background

private val CARD_BG: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

private val PILL_BG: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest

private val PEACH_ACCENT: Color
    @Composable get() = MaterialTheme.colorScheme.primary

private val TEXT_PRIMARY: Color
    @Composable get() = MaterialTheme.colorScheme.onBackground

private val TEXT_SECONDARY: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

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
    var showAuthDialog by remember { mutableStateOf(false) }
    var isAuthorizing by remember { mutableStateOf(false) }
    var authUsernameInput by remember { mutableStateOf("") }
    var authTokenInput by remember { mutableStateOf("") }

    var showStatusDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showPlatformDialog by remember { mutableStateOf(false) }
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
            showAuthDialog -> showAuthDialog = false
            showStatusDialog -> showStatusDialog = false
            showIntervalDialog -> showIntervalDialog = false
            showPlatformDialog -> showPlatformDialog = false
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TEXT_PRIMARY
                        )
                    }
                },
                actions = { },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = THEME_BG
                )
            )
        },
        containerColor = THEME_BG
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(top = 0.dp, bottom = 40.dp)
        ) {
            // ── LARGE PAGE TITLE ──
            item {
                Text(
                    text = "Discord Integration",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TEXT_PRIMARY,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            // ── 1. ACCOUNT SECTION ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Account",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = PEACH_ACCENT,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = CARD_BG,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Avatar & Status Row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(PILL_BG),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (settings.isLoggedIn && settings.discordAvatarUrl.isNotBlank()) {
                                        ArtworkCard(
                                            url = settings.discordAvatarUrl,
                                            modifier = Modifier.fillMaxSize(),
                                            cornerRadius = 32.dp
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_discord),
                                            contentDescription = "Discord",
                                            tint = PEACH_ACCENT,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(18.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = if (settings.isLoggedIn && settings.discordUsername.isNotBlank()) settings.discordUsername else "Not logged in",
                                        fontWeight = FontWeight.Bold,
                                        color = TEXT_PRIMARY,
                                        fontSize = 21.sp
                                    )
                                    if (!settings.isLoggedIn) {
                                        Text(
                                            text = "Discord presence authorization required",
                                            color = TEXT_SECONDARY,
                                            fontSize = 12.5.sp
                                        )
                                    }
                                }
                            }

                            // Waiting for Discord authorization loading card
                            if (isAuthorizing && !settings.isLoggedIn) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            color = PEACH_ACCENT,
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Text(
                                            text = "Waiting for Discord authorization...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }

                            // Enable Rich Presence Inner Card
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = BUTTON_SECONDARY_BG,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(THEME_BG),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = null,
                                                tint = PEACH_ACCENT,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Text(
                                            text = "Enable Rich Presence",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TEXT_PRIMARY,
                                            fontSize = 14.5.sp
                                        )
                                    }

                                    Switch(
                                        checked = settings.enableRichPresence,
                                        onCheckedChange = { checked ->
                                            scope.launch { dataStore.setEnableRichPresence(checked) }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = THEME_BG,
                                            checkedTrackColor = PEACH_ACCENT,
                                            uncheckedThumbColor = THEME_BG,
                                            uncheckedTrackColor = PILL_BG
                                        )
                                    )
                                }
                            }

                            // Main Auth Button (Solid Accent Pill / Error Red on Disconnect)
                            Button(
                                onClick = {
                                    if (settings.isLoggedIn) {
                                        scope.launch {
                                            dataStore.setLoggedIn(false)
                                            dataStore.setEnableRichPresence(false)
                                        }
                                        isAuthorizing = false
                                        DiscordGatewayManager.getInstance(context).disconnect()
                                        Toast.makeText(context, "Logged out of Discord", Toast.LENGTH_SHORT).show()
                                    } else {
                                        isAuthorizing = true
                                        showAuthDialog = true
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        settings.isLoggedIn -> MaterialTheme.colorScheme.error
                                        isAuthorizing -> BUTTON_SECONDARY_BG
                                        else -> PEACH_ACCENT
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                ) {
                                    Text(
                                        text = when {
                                            settings.isLoggedIn -> "Disconnect Discord Account"
                                            isAuthorizing -> "Open Discord authorization"
                                            else -> "Open Discord authorization"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            settings.isLoggedIn -> MaterialTheme.colorScheme.onError
                                            isAuthorizing -> TEXT_SECONDARY.copy(alpha = 0.6f)
                                            else -> BUTTON_DARK_TEXT
                                        },
                                        fontSize = 14.5.sp
                                    )
                                }
                        }
                    }
                }
            }

            // ── 2. OPTIONS SECTION ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Options",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = PEACH_ACCENT,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = CARD_BG,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = PEACH_ACCENT,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = "Refresh",
                                        fontWeight = FontWeight.SemiBold,
                                        color = TEXT_PRIMARY,
                                        fontSize = 15.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Manually refresh Discord Rich Presence",
                                        color = TEXT_SECONDARY,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }

                            Text(
                                text = "Refresh",
                                color = TEXT_SECONDARY.copy(alpha = 0.6f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp,
                                modifier = Modifier
                                    .clickable {
                                        DiscordGatewayManager.getInstance(context).pushPresence()
                                        Toast.makeText(context, "Discord Rich Presence refreshed!", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }

            // ── 3. CONNECTION SECTION ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Connection",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = PEACH_ACCENT,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = CARD_BG,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            NomaSettingRowItem(
                                icon = Icons.Default.Refresh,
                                title = "Activity Status",
                                value = settings.activityStatus,
                                onClick = { showStatusDialog = true }
                            )

                            NomaSettingRowItem(
                                icon = Icons.Default.Timer,
                                title = "Update Interval",
                                value = when (settings.updateIntervalSeconds) {
                                    0 -> "Disabled"
                                    60 -> "1m"
                                    300 -> "5m"
                                    else -> "${settings.updateIntervalSeconds}s"
                                },
                                onClick = { showIntervalDialog = true }
                            )

                            NomaSettingRowItem(
                                icon = Icons.Default.Laptop,
                                title = "Platform",
                                value = settings.platform,
                                onClick = { showPlatformDialog = true }
                            )
                        }
                    }
                }
            }

            // ── 4. ACTIVITY CONTENT SECTION ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Activity Content",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = PEACH_ACCENT,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = CARD_BG,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            NomaSettingRowItem(
                                icon = Icons.Default.TextFields,
                                title = "Activity name",
                                value = settings.activityName,
                                onClick = {
                                    customActivityNameInput = settings.activityName
                                    showActivityNameDialog = true
                                }
                            )

                            NomaSettingRowItem(
                                icon = Icons.Default.TextFields,
                                title = "Activity details",
                                value = settings.activityDetails,
                                onClick = { showActivityDetailsDialog = true }
                            )

                            NomaSettingRowItem(
                                icon = Icons.Default.TextFields,
                                title = "Activity state",
                                value = settings.activityState,
                                onClick = { showActivityStateDialog = true }
                            )

                            // Show RPC when paused row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = PEACH_ACCENT,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Show RPC when paused",
                                        fontWeight = FontWeight.SemiBold,
                                        color = TEXT_PRIMARY,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "If enabled, Rich Presence will remain visible while paused with a pause icon. If disabled, RPC will disappear when paused.",
                                        color = TEXT_SECONDARY,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = settings.showRpcWhenPaused,
                                    onCheckedChange = { checked ->
                                        scope.launch { dataStore.setShowRpcWhenPaused(checked) }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF1B1714),
                                        checkedTrackColor = PEACH_ACCENT,
                                        uncheckedThumbColor = Color(0xFF1B1714),
                                        uncheckedTrackColor = Color(0xFF382C24)
                                    )
                                )
                            }

                            NomaSettingRowItem(
                                icon = Icons.Default.Headphones,
                                title = "Activity type",
                                value = settings.activityType,
                                onClick = { showActivityTypeDialog = true }
                            )
                        }
                    }
                }
            }

            // ── 5. IMAGE OPTIONS SECTION ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Image Options",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = PEACH_ACCENT,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = CARD_BG,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            NomaSettingRowItem(
                                icon = Icons.Default.Image,
                                title = "Large Image",
                                value = settings.largeImage,
                                onClick = { showLargeImageDialog = true }
                            )

                            NomaSettingRowItem(
                                icon = Icons.Default.TextFields,
                                title = "Large Text",
                                value = settings.largeText,
                                onClick = { showLargeTextDialog = true }
                            )

                            NomaSettingRowItem(
                                icon = Icons.Default.Image,
                                title = "Small Image",
                                value = settings.smallImage,
                                onClick = { showSmallImageDialog = true }
                            )
                        }
                    }
                }
            }

            // ── 6. PREVIEW SECTION ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Preview",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TEXT_PRIMARY,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = CARD_BG,
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
                                                contentDescription = "Auralis App Logo",
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
                                                contentDescription = "Auralis App Logo",
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
                                        text = effectiveTrack?.title ?: "Song title",
                                        color = TEXT_SECONDARY,
                                        fontSize = 13.5.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = effectiveTrack?.artist ?: "Artist name",
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
                                        text = "Listen on YouTube Music",
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
                                        text = "Go to Auralis",
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
    }

    // ── CONFIGURATION PICKER DIALOGS ──

    // 1. Auth Dialog (Direct Discord Login WebView + Token Capture)
    if (showAuthDialog) {
        DiscordAuthWebViewDialog(
            onDismiss = { showAuthDialog = false },
            onTokenCaptured = { token ->
                scope.launch {
                    val result = DiscordGatewayManager.getInstance(context).verifyAndSaveToken(token)
                    if (result.isSuccess) {
                        val user = result.getOrNull()
                        Toast.makeText(context, "Connected to Discord as ${user?.username ?: "User"}!", Toast.LENGTH_SHORT).show()
                    } else {
                        dataStore.setLoggedIn(true, username = "Discord User", token = token)
                        dataStore.setEnableRichPresence(true)
                        DiscordGatewayManager.getInstance(context).connect(token)
                        Toast.makeText(context, "Connected to Discord!", Toast.LENGTH_SHORT).show()
                    }
                }
                showAuthDialog = false
            }
        )
    }

    // Pickers with NomaOptionPickerBottomSheet
    if (showStatusDialog) {
        NomaOptionPickerBottomSheet(
            title = "Activity Status",
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
            AlertDialog(
                onDismissRequest = { showCustomIntervalDialog = false },
                containerColor = CARD_BG,
                title = { Text("Custom Interval", fontWeight = FontWeight.Bold, color = TEXT_PRIMARY) },
                text = {
                    Column {
                        Text("Enter update interval in seconds (e.g. 3 to 600):", color = TEXT_SECONDARY, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customIntervalInput,
                            onValueChange = { customIntervalInput = it.filter { c -> c.isDigit() } },
                            label = { Text("Seconds") },
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
                        Text("Save", color = PEACH_ACCENT, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomIntervalDialog = false }) {
                        Text("Cancel", color = TEXT_SECONDARY)
                    }
                }
            )
        } else {
            NomaOptionPickerBottomSheet(
                title = "Update Interval",
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

    if (showPlatformDialog) {
        NomaOptionPickerBottomSheet(
            title = "Platform",
            options = listOf("Android", "Desktop", "iOS", "Web"),
            selected = settings.platform,
            onSelect = {
                scope.launch { dataStore.setPlatform(it) }
            },
            onDismiss = { showPlatformDialog = false }
        )
    }

    if (showActivityNameDialog) {
        AlertDialog(
            onDismissRequest = { showActivityNameDialog = false },
            containerColor = CARD_BG,
            title = { Text("Activity Name", fontWeight = FontWeight.Bold, color = TEXT_PRIMARY) },
            text = {
                OutlinedTextField(
                    value = customActivityNameInput,
                    onValueChange = { customActivityNameInput = it },
                    label = { Text("App Name in Discord") },
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
                    Text("Save", color = BUTTON_DARK_TEXT, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showActivityNameDialog = false }) {
                    Text("Cancel", color = TEXT_SECONDARY)
                }
            }
        )
    }

    if (showActivityDetailsDialog) {
        NomaOptionPickerBottomSheet(
            title = "Activity details",
            options = listOf("Artist name", "Album name", "Song title", "Auralis"),
            selected = settings.activityDetails,
            onSelect = {
                scope.launch { dataStore.setActivityDetails(it) }
            },
            onDismiss = { showActivityDetailsDialog = false }
        )
    }

    if (showActivityStateDialog) {
        NomaOptionPickerBottomSheet(
            title = "Activity state",
            options = listOf("Artist name", "Album name", "Song title", "Auralis"),
            selected = settings.activityState,
            onSelect = {
                scope.launch { dataStore.setActivityState(it) }
            },
            onDismiss = { showActivityStateDialog = false }
        )
    }

    if (showActivityTypeDialog) {
        NomaOptionPickerBottomSheet(
            title = "Activity type",
            options = listOf("Listening", "Playing", "Streaming", "Competing"),
            selected = settings.activityType,
            onSelect = {
                scope.launch { dataStore.setActivityType(it) }
            },
            onDismiss = { showActivityTypeDialog = false }
        )
    }

    if (showLargeImageDialog) {
        NomaOptionPickerBottomSheet(
            title = "Large Image",
            options = listOf("Album artwork", "App icon", "None"),
            selected = settings.largeImage,
            onSelect = {
                scope.launch { dataStore.setLargeImage(it) }
            },
            onDismiss = { showLargeImageDialog = false }
        )
    }

    if (showLargeTextDialog) {
        NomaOptionPickerBottomSheet(
            title = "Large Text",
            options = listOf("Album name", "Song title", "Auralis", "None"),
            selected = settings.largeText,
            onSelect = {
                scope.launch { dataStore.setLargeText(it) }
            },
            onDismiss = { showLargeTextDialog = false }
        )
    }

    if (showSmallImageDialog) {
        NomaOptionPickerBottomSheet(
            title = "Small Image",
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
private fun NomaSettingRowItem(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PEACH_ACCENT,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = TEXT_PRIMARY,
                    fontSize = 15.sp
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PILL_BG
                ) {
                    Text(
                        text = value,
                        color = PEACH_ACCENT,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TEXT_SECONDARY.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NomaOptionPickerBottomSheet(
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
                            text = option,
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

@Composable
private fun DiscordAuthWebViewDialog(
    onDismiss: () -> Unit,
    onTokenCaptured: (String) -> Unit
) {
    var showManualTokenInput by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = THEME_BG
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CARD_BG)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TEXT_PRIMARY
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Discord Authorization",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = TEXT_PRIMARY
                        )
                    }

                    TextButton(onClick = { showManualTokenInput = !showManualTokenInput }) {
                        Text(
                            text = if (showManualTokenInput) "Web Login" else "Paste Token",
                            color = PEACH_ACCENT,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (showManualTokenInput) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Paste Discord User Token",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = TEXT_PRIMARY
                        )
                        Text(
                            text = "If you have your Discord user authorization token, paste it below to connect directly:",
                            color = TEXT_SECONDARY,
                            fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = { manualToken = it },
                            label = { Text("User Token") },
                            placeholder = { Text("Paste token here") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PEACH_ACCENT,
                                unfocusedBorderColor = TEXT_SECONDARY.copy(alpha = 0.4f),
                                focusedLabelColor = PEACH_ACCENT,
                                unfocusedLabelColor = TEXT_SECONDARY,
                                focusedTextColor = TEXT_PRIMARY,
                                unfocusedTextColor = TEXT_PRIMARY
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val token = manualToken.trim()
                                if (token.isNotBlank()) {
                                    onTokenCaptured(token)
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PEACH_ACCENT),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Connect With Token", color = BUTTON_DARK_TEXT, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun onToken(token: String?) {
                                        if (!token.isNullOrBlank() && token.length > 20) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                onTokenCaptured(token)
                                            }
                                        }
                                    }
                                }, "AuralisBridge")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)

                                        // Inject script to monitor authorization headers and extract token
                                        val script = """
                                            (function() {
                                                try {
                                                    var iframe = document.createElement('iframe');
                                                    document.body.appendChild(iframe);
                                                    var token = iframe.contentWindow.localStorage.getItem('token');
                                                    if (token && token.length > 20) {
                                                        window.AuralisBridge && window.AuralisBridge.onToken(JSON.parse(token));
                                                    }
                                                } catch(e) {}
                                                try {
                                                    var token = window.localStorage.getItem('token');
                                                    if (token && token.length > 20) {
                                                        window.AuralisBridge && window.AuralisBridge.onToken(JSON.parse(token));
                                                    }
                                                } catch(e) {}
                                                if (!window._auralisHooked) {
                                                    window._auralisHooked = true;
                                                    var origOpen = XMLHttpRequest.prototype.open;
                                                    var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                                                    XMLHttpRequest.prototype.setRequestHeader = function(h, v) {
                                                        if (h && h.toLowerCase() === 'authorization' && v && v.length > 20) {
                                                            window.AuralisBridge && window.AuralisBridge.onToken(v);
                                                        }
                                                        return origSetHeader.apply(this, arguments);
                                                    };
                                                }
                                            })();
                                        """.trimIndent()
                                        view?.evaluateJavascript(script, null)
                                    }
                                }
                                loadUrl("https://discord.com/login")
                            }
                        }
                    )
                }
            }
        }
    }
}

