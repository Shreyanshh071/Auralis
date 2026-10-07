package com.auralis.music.ui.profile

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import com.auralis.music.ui.theme.AuralisPushedPage
import com.auralis.music.ui.theme.auralisPushParent
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.rememberPushProgress
import com.auralis.music.ui.theme.dynamicPrimary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.viewmodel.AuthUiState
import com.auralis.music.ui.components.bottomChromePadding

val PROFILE_LIME: Color
    @Composable get() = MaterialTheme.colorScheme.primary
val PROFILE_CARD_BG: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

/**
 * Pure YouTube Playlist Importer & Account Sheet.
 * Direct Google OAuth 2.0 via Firebase Auth with `https://www.googleapis.com/auth/youtube.readonly` scope.
 * The Bearer token is kept in-memory to call the YouTube Data API v3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    authUiState: AuthUiState,
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

    val themePrimary = MaterialTheme.dynamicPrimary
    val themeBackground = MaterialTheme.dynamicBackground

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBackground)
            .auralisPushParent(subPagePush)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ── TOP HEADER (WITH COMPACT PADDING) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = str(R.string.profile_account),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isSettingsOpen = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = str(R.string.settings),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = str(R.string.close),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── USER PROFILE CARD ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile.avatarUrl != null) {
                        ArtworkCard(
                            url = profile.avatarUrl,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            cornerRadius = 23.dp,
                            contentDescription = profile.displayName
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = profile.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (profile.isGoogleConnected) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF16A34A).copy(alpha = 0.15f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = str(R.string.cloud_backup_active),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF16A34A),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── DISCORD INTEGRATION CARD ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.2.dp, Color(0xFF5865F2).copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                    .clickable { isDiscordIntegrationOpen = true }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF5865F2).copy(alpha = 0.18f))
                                .border(1.dp, Color(0xFF5865F2).copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = com.auralis.music.R.drawable.ic_discord),
                                contentDescription = str(R.string.discord),
                                tint = Color(0xFF5865F2),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = str(R.string.discord_integration),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                            Text(
                                text = str(R.string.connect_rich_presence_display_song_activ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = str(R.string.open_discord_integration),
                        tint = Color(0xFF5865F2),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── YOUTUBE ACCOUNT CARD (age-restricted songs) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.2.dp, Color(0xFFFF0033).copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .clickable { isYouTubeAccountOpen = true }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF0033).copy(alpha = 0.16f))
                                .border(1.dp, Color(0xFFFF0033).copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (youTubeSignedIn) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                                contentDescription = str(R.string.youtube),
                                tint = Color(0xFFFF0033),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = str(R.string.youtube_account),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (youTubeSignedIn) str(R.string.age_restricted_songs_unlocked)
                                else str(R.string.sign_in_to_download_age_restricted_songs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = str(R.string.open_youtube_account),
                        tint = Color(0xFFFF0033),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── IMPORT PLAYLISTS CARD ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .clickable { isImportPlaylistsOpen = true }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = str(R.string.import_playlists),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                            Text(
                                text = str(R.string.from_youtube_music_or_spotify),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = str(R.string.open_import_playlists),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }


            Spacer(modifier = Modifier.height(10.dp))

            // ── DISCONNECT BUTTON ──
            if (profile.isGoogleConnected || profile.accessToken != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFEF4444).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .tactileBounce(scaleDown = 0.96f) { onDisconnect() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = str(R.string.disconnect_account),
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                // ── DELETE ACCOUNT ──
                var showDeleteDialog by remember { mutableStateOf(false) }
                Text(
                    text = str(R.string.delete_account),
                    color = Color(0xFFEF4444).copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClearDeleteAccountError(); showDeleteDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                if (showDeleteDialog) {
                    DeleteAccountDialog(
                        needsPassword = deleteAccountNeedsPassword,
                        isDeleting = authUiState.isDeletingAccount,
                        error = authUiState.deleteAccountError,
                        onConfirm = onDeleteAccount,
                        onDismiss = { if (!authUiState.isDeletingAccount) showDeleteDialog = false }
                    )
                }
            }

            Spacer(modifier = Modifier.padding(bottomChromePadding(includeNavigationBar = false)))
        }
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
