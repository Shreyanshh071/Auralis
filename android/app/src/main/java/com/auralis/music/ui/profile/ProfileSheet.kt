package com.auralis.music.ui.profile

import com.auralis.music.ui.components.*
import androidx.compose.material.icons.filled.WarningAmber
import com.auralis.music.data.datastore.DiscordRpcDataStore
import com.auralis.music.domain.model.DiscordRpcSettings
import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.auralis.music.ui.theme.AuralisPushedPage
import com.auralis.music.ui.theme.auralisPushParent
import com.auralis.music.ui.theme.rememberPushProgress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.viewmodel.AuthUiState

val PROFILE_LIME: Color
    @Composable get() = MaterialTheme.colorScheme.primary
val PROFILE_CARD_BG: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

/**
 * Native profile overview with live integrations and account management.
 * Account management and playlist imports open as pushed pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    authUiState: AuthUiState,
    playlistsCount: Int = 0,
    savedTracksCount: Int = 0,
    onImportYouTubePlaylist: (String) -> Unit = {},
    /** Playlists picked from the signed-in YouTube Music library (Profile > YouTube account). */
    onImportYouTubeLibraryPlaylists: (List<com.auralis.music.data.network.YouTubeMusicLibrary.LibraryPlaylist>) -> Unit = {},
    onClearYouTubeImportMessage: () -> Unit = {},
    isImportingYouTube: Boolean = false,
    youtubeImportMessage: String? = null,
    onOpenPlaylistSelector: () -> Unit,
    onSyncLikedMusic: () -> Unit,
    onDisconnect: () -> Unit,
    /** Deletes the account and its backed-up data; the password is null for Google accounts. */
    onDeleteAccount: (password: String?) -> Unit = {},
    deleteAccountNeedsPassword: Boolean = false,
    onClearDeleteAccountError: () -> Unit = {},
    onClosePlaylistSelector: () -> Unit,
    onTogglePlaylistSelection: (String) -> Unit,
    onSelectAllPlaylists: () -> Unit,
    onDeselectAllPlaylists: () -> Unit = {},
    onImportSelectedPlaylists: () -> Unit = {},
    onImportSpotifyPlaylist: (String) -> Unit = {},
    /** Playlists picked from the signed-in Spotify library. */
    onImportSpotifyLibraryPlaylists: (List<com.auralis.music.data.network.SpotifyLibrary.LibraryPlaylist>) -> Unit = {},
    onClearSpotifyImportMessage: () -> Unit = {},
    isImportingSpotify: Boolean = false,
    spotifyImportMessage: String? = null,
    playerSettings: com.auralis.music.domain.model.PlayerSettings = com.auralis.music.domain.model.PlayerSettings(),
    onThemeModeChange: (com.auralis.music.domain.model.ThemeMode) -> Unit = {},
    onAudioQualityChange: (com.auralis.music.domain.model.AudioQuality) -> Unit = {},
    onToggleGaplessPlayback: (Boolean) -> Unit = {},
    onToggleSkipSilence: (Boolean) -> Unit = {},
    onToggleSpatialAudio: (Boolean) -> Unit = {},
    onClearCache: () -> Unit = {},
    onDismiss: () -> Unit,
    historyRepository: com.auralis.music.domain.repository.HistoryRepository? = null,
    searchRepository: com.auralis.music.domain.repository.SearchRepository? = null,
    hasActiveTrack: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val profile = authUiState.profile
    var isSettingsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var isDiscordIntegrationOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var isYouTubeAccountOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var isImportPlaylistsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val youTubeSignedIn by com.auralis.music.data.network.YouTubeSession.signedIn.collectAsState()

    androidx.activity.compose.BackHandler(enabled = true) {
        if (isImportPlaylistsOpen) {
            isImportPlaylistsOpen = false
        } else if (isYouTubeAccountOpen) {
            isYouTubeAccountOpen = false
        } else if (isDiscordIntegrationOpen) {
            isDiscordIntegrationOpen = false
        } else if (isSettingsOpen) {
            isSettingsOpen = false
        } else {
            onDismiss()
        }
    }

    // Settings, Discord, YouTube, and Import Playlists push over the profile instead of replacing it on one frame.
    val subPage = when {
        isImportPlaylistsOpen -> ProfileSubPage.IMPORT_PLAYLISTS
        isYouTubeAccountOpen -> ProfileSubPage.YOUTUBE
        isDiscordIntegrationOpen -> ProfileSubPage.DISCORD
        isSettingsOpen -> ProfileSubPage.SETTINGS
        else -> null
    }
    val subPagePush = rememberPushProgress(subPage != null)

    val discordStore = remember { DiscordRpcDataStore(context) }
    val discord by discordStore.settingsFlow.collectAsState(initial = DiscordRpcSettings())
    val youtubeLabel by com.auralis.music.data.network.YouTubeSession.accountLabel.collectAsState()
    var showDisconnectDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val connected = profile.isGoogleConnected || profile.accessToken != null

    AccountPageTheme {
        AccountPage(
            title = str(R.string.profile_account),
            modifier = modifier.auralisPushParent(subPagePush),
            actions = {
                IconButton(onClick = { isSettingsOpen = true }) {
                    Icon(Icons.Default.Settings, str(R.string.settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, str(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        ) {
            AccountPanel(padding = 20.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                        if (profile.avatarUrl != null) {
                            ArtworkCard(url = profile.avatarUrl, modifier = Modifier.fillMaxSize(),
                                cornerRadius = 32.dp, contentDescription = profile.displayName)
                        } else {
                            Text(profile.displayName.firstOrNull()?.uppercase() ?: "A",
                                fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayName.ifBlank { str(R.string.account_ui_listener) },
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (profile.email.isNotBlank()) Text(profile.email,
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        AccountBadge(str(if (connected && playerSettings.cloudSyncEnabled)
                            R.string.account_ui_cloud_active else R.string.account_ui_local_library))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountSectionLabel(str(R.string.account_ui_integrations))
                AccountPanel(padding = 0.dp) {
                    AccountRow(str(R.string.account_ui_discord_presence),
                        str(if (discord.isLoggedIn && discord.enableRichPresence)
                            R.string.account_ui_discord_sharing else R.string.connect_rich_presence_display_song_activ),
                        onClick = { isDiscordIntegrationOpen = true },
                        icon = { AccountGlyph {
                            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_discord), null,
                                modifier = Modifier.size(20.dp))
                        } })
                    AccountDivider()
                    AccountRow(str(R.string.youtube_account),
                        if (youTubeSignedIn) youtubeLabel.ifBlank { str(R.string.signed_in) } +
                            " · " + str(R.string.age_restricted_songs_unlocked)
                        else str(R.string.sign_in_to_download_age_restricted_songs),
                        onClick = { isYouTubeAccountOpen = true },
                        icon = { AccountGlyph(Icons.Default.PlayArrow) })
                    AccountDivider()
                    AccountRow(str(R.string.import_playlists), str(R.string.from_youtube_music_or_spotify),
                        onClick = { isImportPlaylistsOpen = true }, icon = { AccountGlyph(Icons.Default.SwapHoriz) })
                }
            }

            if (connected) {
                AccountPanel(padding = 0.dp) {
                    AccountRow(str(R.string.disconnect_account), str(R.string.account_ui_disconnect_description),
                        onClick = { showDisconnectDialog = true },
                        icon = { AccountGlyph(Icons.AutoMirrored.Filled.ExitToApp) })
                    AccountDivider()
                    AccountRow(str(R.string.account_ui_delete_data), str(R.string.account_ui_delete_description),
                        onClick = { onClearDeleteAccountError(); showDeleteDialog = true },
                        icon = { AccountGlyph(Icons.Default.Delete) },
                        trailing = { Icon(Icons.Default.WarningAmber, null,
                            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                }
            }
            Text(str(R.string.account_ui_version, com.auralis.music.BuildConfig.VERSION_NAME),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        if (showDisconnectDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDisconnectDialog = false },
                icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
                title = { Text(str(R.string.account_ui_disconnect_confirmation_title)) },
                text = { Text(str(R.string.account_ui_disconnect_confirmation_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDisconnectDialog = false
                        onDisconnect()
                    }) {
                        Text(str(R.string.disconnect_account), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDisconnectDialog = false }) {
                        Text(str(R.string.cancel))
                    }
                }
            )
        }
        if (showDeleteDialog) DeleteAccountDialog(
            needsPassword = deleteAccountNeedsPassword, isDeleting = authUiState.isDeletingAccount,
            error = authUiState.deleteAccountError, onConfirm = onDeleteAccount,
            onDismiss = { if (!authUiState.isDeletingAccount) showDeleteDialog = false })
    }

    AuralisPushedPage(page = subPage) { page ->
        when (page) {
            ProfileSubPage.IMPORT_PLAYLISTS -> com.auralis.music.ui.screens.ImportPlaylistsScreen(
                onDismiss = { isImportPlaylistsOpen = false },
                onNavigateToYouTubeAccount = {
                    isImportPlaylistsOpen = false
                    isYouTubeAccountOpen = true
                },
                onImportYouTubeLibraryPlaylists = onImportYouTubeLibraryPlaylists,
                isImportingYouTube = isImportingYouTube,
                youtubeImportMessage = youtubeImportMessage,
                onClearYouTubeImportMessage = onClearYouTubeImportMessage,
                onImportYouTubePlaylist = onImportYouTubePlaylist,
                onImportSpotifyPlaylist = onImportSpotifyPlaylist,
                onImportSpotifyLibraryPlaylists = onImportSpotifyLibraryPlaylists,
                isImportingSpotify = isImportingSpotify,
                spotifyImportMessage = spotifyImportMessage,
                onClearSpotifyImportMessage = onClearSpotifyImportMessage
            )
            ProfileSubPage.YOUTUBE -> com.auralis.music.ui.screens.YouTubeAccountScreen(
                onDismiss = { isYouTubeAccountOpen = false }
            )
            ProfileSubPage.DISCORD -> com.auralis.music.ui.screens.DiscordIntegrationScreen(
                onDismiss = { isDiscordIntegrationOpen = false }
            )
            ProfileSubPage.SETTINGS -> {
                com.auralis.music.ui.screens.SettingsScreen(
                    settings = playerSettings,
                    onThemeModeChange = onThemeModeChange,
                    onAudioQualityChange = onAudioQualityChange,
                    onToggleGaplessPlayback = onToggleGaplessPlayback,
                    onToggleSkipSilence = onToggleSkipSilence,
                    onToggleSpatialAudio = onToggleSpatialAudio,
                    onClearCache = onClearCache,
                    onNavigateToAccount = { isSettingsOpen = false },
                    onDismiss = { isSettingsOpen = false },
                    historyRepository = historyRepository,
                    searchRepository = searchRepository,
                    hasActiveTrack = hasActiveTrack
                )
            }
        }
    }
}

private enum class ProfileSubPage { SETTINGS, DISCORD, YOUTUBE, IMPORT_PLAYLISTS }

@Composable
private fun GoogleLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height

        drawRect(
            color = Color(0xFF4285F4),
            topLeft = Offset(w * 0.45f, h * 0.40f),
            size = Size(w * 0.55f, h * 0.20f)
        )
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 180f,
            sweepAngle = 140f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.20f)
        )
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 120f,
            sweepAngle = 120f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.20f)
        )
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 0f,
            sweepAngle = 120f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.20f)
        )
    }
}

@Composable
fun SpotifyLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val radius = w / 2f

        // Green Circle Background
        drawCircle(
            color = Color(0xFF1DB954),
            radius = radius,
            center = Offset(w / 2f, h / 2f)
        )

        // 3 Soundwave Arcs (Top, Middle, Bottom)
        // Top Arc
        val topPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.24f, h * 0.38f)
            quadraticTo(w * 0.49f, h * 0.24f, w * 0.75f, h * 0.33f)
        }
        drawPath(
            path = topPath,
            color = Color.Black,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.088f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )

        // Middle Arc
        val midPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.28f, h * 0.52f)
            quadraticTo(w * 0.49f, h * 0.40f, w * 0.72f, h * 0.47f)
        }
        drawPath(
            path = midPath,
            color = Color.Black,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.078f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )

        // Bottom Arc
        val botPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.32f, h * 0.65f)
            quadraticTo(w * 0.49f, h * 0.55f, w * 0.68f, h * 0.61f)
        }
        drawPath(
            path = botPath,
            color = Color.Black,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.068f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
    }
}

/**
 * Confirms account deletion and says exactly what goes: the sign-in and everything backed up to it.
 * Data on this phone stays. Email accounts re-enter their password; Google accounts confirm with a
 * fresh Google sign-in after tapping Delete.
 */
@Composable
private fun DeleteAccountDialog(
    needsPassword: Boolean,
    isDeleting: Boolean,
    error: String?,
    onConfirm: (password: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.delete_your_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    str(R.string.this_permanently_deletes_your_auralis_ac) +
                        "playlists, liked songs, saved artists and listening stats. It can't be undone.\n\n" +
                        str(R.string.songs_and_playlists_on_this_phone_stay_t)
                )
                if (needsPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(str(R.string.password)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        enabled = !isDeleting
                    )
                } else {
                    Text(
                        str(R.string.you_ll_be_asked_to_sign_in_with_google_a),
                        fontSize = 13.sp
                    )
                }
                if (error != null) {
                    Text(error, color = Color(0xFFEF4444), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(if (needsPassword) password else null) },
                enabled = !isDeleting && (!needsPassword || password.isNotBlank())
            ) {
                Text(if (isDeleting) str(R.string.deleting) else str(R.string.delete), color = Color(0xFFEF4444))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) { Text(str(R.string.cancel)) }
        }
    )
}
