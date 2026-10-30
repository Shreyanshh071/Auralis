package com.auralis.music.ui.profile

import com.auralis.music.ui.screens.SettingsGroup
import com.auralis.music.ui.screens.SettingsNavRow
import com.auralis.music.ui.screens.SettingsPage
import com.auralis.music.ui.theme.dynamicPrimary
import androidx.compose.ui.res.vectorResource
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

    SettingsPage(
        title = str(R.string.profile_account),
        onBack = onDismiss,
        modifier = modifier.auralisPushParent(subPagePush),
        actions = { backdrop ->
            com.auralis.music.ui.glass.FloatingPageButton(
                Icons.Default.Settings, str(R.string.settings), backdrop, onClick = { isSettingsOpen = true }
            )
        }
    ) {
        item(key = "profile_identity", contentType = "identity") {
            SettingsGroup(null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(68.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .09f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (profile.avatarUrl != null) {
                            ArtworkCard(url = profile.avatarUrl, modifier = Modifier.fillMaxSize(),
                                cornerRadius = 34.dp, contentDescription = profile.displayName)
                        } else {
                            Text(profile.displayName.firstOrNull()?.uppercase() ?: "A",
                                fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayName.ifBlank { str(R.string.account_ui_listener) },
                            fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (profile.email.isNotBlank()) Text(profile.email,
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(str(if (connected && playerSettings.cloudSyncEnabled)
                            R.string.account_ui_cloud_active else R.string.account_ui_local_library),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.dynamicPrimary)
                    }
                }
            }
        }
        item(key = "profile_integrations", contentType = "group") {
            SettingsGroup(str(R.string.account_ui_integrations)) {
                SettingsNavRow(
                    androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_discord),
                    str(R.string.account_ui_discord_presence),
                    str(if (discord.isLoggedIn && discord.enableRichPresence)
                        R.string.account_ui_discord_sharing else R.string.connect_rich_presence_display_song_activ)
                ) { isDiscordIntegrationOpen = true }
                SettingsNavRow(
                    Icons.Default.PlayArrow,
                    str(R.string.youtube_account),
                    if (youTubeSignedIn) youtubeLabel.ifBlank { str(R.string.signed_in) } +
                        " · " + str(R.string.age_restricted_songs_unlocked)
                    else str(R.string.sign_in_to_download_age_restricted_songs)
                ) { isYouTubeAccountOpen = true }
                SettingsNavRow(Icons.Default.SwapHoriz, str(R.string.import_playlists), str(R.string.from_youtube_music_or_spotify)) {
                    isImportPlaylistsOpen = true
                }
            }
        }
        if (connected) item(key = "profile_account", contentType = "group") {
            SettingsGroup(null) {
                SettingsNavRow(Icons.AutoMirrored.Filled.ExitToApp, str(R.string.disconnect_account), str(R.string.account_ui_disconnect_description)) {
                    showDisconnectDialog = true
                }
                SettingsNavRow(Icons.Default.Delete, str(R.string.account_ui_delete_data), str(R.string.account_ui_delete_description)) {
                    onClearDeleteAccountError()
                    showDeleteDialog = true
                }
            }
        }
        item(key = "profile_version", contentType = "footer") {
            Text(str(R.string.account_ui_version, com.auralis.music.BuildConfig.VERSION_NAME),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }
    if (showDisconnectDialog) {
        com.auralis.music.ui.components.AuralisConfirmDialog(
            title = str(R.string.account_ui_disconnect_confirmation_title),
            message = str(R.string.account_ui_disconnect_confirmation_message),
            confirmLabel = str(R.string.disconnect),
            onDismiss = { showDisconnectDialog = false },
            icon = Icons.AutoMirrored.Filled.ExitToApp,
            onConfirm = {
                    showDisconnectDialog = false
                    onDisconnect()
                },
        )
    }
    if (showDeleteDialog) DeleteAccountDialog(
        needsPassword = deleteAccountNeedsPassword, isDeleting = authUiState.isDeletingAccount,
        error = authUiState.deleteAccountError, onConfirm = onDeleteAccount,
        onDismiss = { if (!authUiState.isDeletingAccount) showDeleteDialog = false })

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
    val foreground = com.auralis.music.ui.glass.LocalLiquidGlass.current?.let {
        com.auralis.music.ui.glass.glassContentColor(it.isDark)
    } ?: MaterialTheme.colorScheme.onSurface
    com.auralis.music.ui.components.AuralisConfirmDialog(
        title = str(R.string.delete_your_account),
        message = str(R.string.this_permanently_deletes_your_auralis_ac) +
            "playlists, liked songs, saved artists and listening stats. It can't be undone.\n\n" +
            str(R.string.songs_and_playlists_on_this_phone_stay_t),
        confirmLabel = str(if (isDeleting) R.string.deleting else R.string.delete),
        onConfirm = { onConfirm(if (needsPassword) password else null) },
        onDismiss = onDismiss,
        enabled = !isDeleting && (!needsPassword || password.isNotBlank()),
        dismissEnabled = !isDeleting,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (needsPassword) {
                    androidx.compose.material3.TextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(str(R.string.password)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        ),
                        enabled = !isDeleting,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedTextColor = foreground,
                            unfocusedTextColor = foreground,
                            cursorColor = foreground,
                            focusedLabelColor = foreground,
                            unfocusedLabelColor = foreground.copy(alpha = .7f),
                            focusedContainerColor = foreground.copy(alpha = .10f),
                            unfocusedContainerColor = foreground.copy(alpha = .06f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                } else {
                    Text(
                        str(R.string.you_ll_be_asked_to_sign_in_with_google_a),
                        fontSize = 13.sp,
                        color = foreground.copy(alpha = .7f)
                    )
                }
                if (error != null) {
                    Text(error, color = Color(0xFFEF4444), fontSize = 13.sp)
                }
            }
        }
    )
}
