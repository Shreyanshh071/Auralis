package com.auralis.music.ui.screens

import com.auralis.music.ui.i18n.Strings

import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.network.SpotifyLibrary
import com.auralis.music.data.network.SpotifySession
import com.auralis.music.data.network.YouTubeMusicLibrary
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.ui.profile.SpotifyLogoIcon
import com.auralis.music.ui.theme.dynamicBackground
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Dedicated Import Playlists screen.
 * Gives users clean choices:
 * 1. Pick from their signed-in YouTube Music account via a scrollable popup.
 * 2. Import a YouTube Music playlist by link.
 * 3. Import every playlist and Liked Songs from a signed-in Spotify account.
 * 4. Import a Spotify playlist by link.
 */
@Composable
fun ImportPlaylistsScreen(
    onDismiss: () -> Unit,
    onNavigateToYouTubeAccount: () -> Unit,
    onImportYouTubeLibraryPlaylists: (List<YouTubeMusicLibrary.LibraryPlaylist>) -> Unit,
    isImportingYouTube: Boolean,
    youtubeImportMessage: String?,
    onClearYouTubeImportMessage: () -> Unit,
    onImportYouTubePlaylist: (String) -> Unit,
    onImportSpotifyPlaylist: (String) -> Unit,
    onImportSpotifyLibraryPlaylists: (List<SpotifyLibrary.LibraryPlaylist>) -> Unit = {},
    isImportingSpotify: Boolean,
    spotifyImportMessage: String?,
    onClearSpotifyImportMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    val youTubeSignedIn by YouTubeSession.signedIn.collectAsState()
    val accountLabel by YouTubeSession.accountLabel.collectAsState()
    val spotifySignedIn by SpotifySession.signedIn.collectAsState()
    val spotifyAccountLabel by SpotifySession.accountLabel.collectAsState()

    var showPlaylistPickerPopup by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<YouTubeMusicLibrary.LibraryPlaylist>?>(null) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val selectedPlaylists = remember { mutableStateListOf<String>() }
    var showSpotifySignIn by remember { mutableStateOf(false) }
    var spotifyPlaylists by remember { mutableStateOf<List<SpotifyLibrary.LibraryPlaylist>?>(null) }
    var isLoadingSpotifyPlaylists by remember { mutableStateOf(false) }
    var spotifyPlaylistLoadFailed by remember { mutableStateOf(false) }
    var spotifyReloadKey by remember { mutableStateOf(0) }
    var spotifyImportFromAccount by remember { mutableStateOf(false) }
    var importAllAfterSpotifySignIn by remember { mutableStateOf(false) }

    LaunchedEffect(youTubeSignedIn, reloadKey) {
        if (youTubeSignedIn) {
            isLoadingPlaylists = true
            loadFailed = false
            val result = YouTubeMusicLibrary.fetchPlaylists()
            playlists = result
            loadFailed = result == null
            isLoadingPlaylists = false
        } else {
            playlists = null
            selectedPlaylists.clear()
        }
    }

    LaunchedEffect(spotifySignedIn, spotifyReloadKey) {
        if (spotifySignedIn) {
            isLoadingSpotifyPlaylists = true
            val result = SpotifyLibrary.fetchPlaylists()
            spotifyPlaylists = result
            spotifyPlaylistLoadFailed = result == null
            isLoadingSpotifyPlaylists = false
            if (result != null && importAllAfterSpotifySignIn) {
                importAllAfterSpotifySignIn = false
                spotifyImportFromAccount = true
                onImportSpotifyLibraryPlaylists(result)
            }
        } else {
            spotifyPlaylists = null
            spotifyPlaylistLoadFailed = false
            importAllAfterSpotifySignIn = false
        }
    }

    val spotifySignIn = com.auralis.music.ui.screens.LocalWebSignIn.current.spotifySignIn
    if (showSpotifySignIn && spotifySignIn != null) {
        spotifySignIn(
            {
                showSpotifySignIn = false
                importAllAfterSpotifySignIn = true
                spotifyReloadKey++
            },
            { showSpotifySignIn = false }
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            ImportHeader(onDismiss = onDismiss)

            Spacer(Modifier.height(14.dp))

            YouTubeAccountCard(
                signedIn = youTubeSignedIn,
                accountLabel = accountLabel,
                isLoadingPlaylists = isLoadingPlaylists,
                playlists = playlists,
                isImporting = isImportingYouTube,
                importMessage = youtubeImportMessage,
                onOpenPicker = { showPlaylistPickerPopup = true },
                onNavigateToAccount = onNavigateToYouTubeAccount
            )

            Spacer(Modifier.height(12.dp))

            SpotifyAccountCard(
                signedIn = spotifySignedIn,
                accountLabel = spotifyAccountLabel,
                isLoadingPlaylists = isLoadingSpotifyPlaylists,
                isImporting = isImportingSpotify && spotifyImportFromAccount,
                importMessage = if (spotifyPlaylistLoadFailed) "Couldn't load Spotify playlists. Try again."
                    else if (spotifyImportFromAccount) spotifyImportMessage else null,
                onImportAll = {
                    spotifyImportFromAccount = true
                    // A fresh library request is required to see playlists added since the last import.
                    importAllAfterSpotifySignIn = true
                    spotifyReloadKey++
                },
                onSignIn = {
                    if (spotifySignIn != null) showSpotifySignIn = true
                    else platformActions.showMessage(com.auralis.music.ui.settings.PlatformNotes.noWebSignIn)
                },
                onDisconnect = { SpotifySession.signOut() }
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = str(Strings.import_by_link),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            Spacer(Modifier.height(8.dp))

            YouTubeLinkCard(
                isImporting = isImportingYouTube && !showPlaylistPickerPopup,
                importMessage = if (!showPlaylistPickerPopup) youtubeImportMessage else null,
                onImport = onImportYouTubePlaylist,
                onClearMessage = onClearYouTubeImportMessage
            )

            Spacer(modifier = Modifier.height(12.dp))

            SpotifyLinkCard(
                isImporting = isImportingSpotify && !spotifyImportFromAccount,
                importMessage = if (!spotifyImportFromAccount) spotifyImportMessage else null,
                onImport = { spotifyImportFromAccount = false; onImportSpotifyPlaylist(it) },
                onClearMessage = onClearSpotifyImportMessage
            )

            Spacer(Modifier.height(32.dp))
        }

        if (showPlaylistPickerPopup) {
            YouTubePlaylistPickerBottomSheet(
                playlists = playlists,
                isLoading = isLoadingPlaylists,
                loadFailed = loadFailed,
                onRetry = { reloadKey++ },
                selected = selectedPlaylists,
                isImporting = isImportingYouTube,
                importMessage = youtubeImportMessage,
                onImport = { selected -> onImportYouTubeLibraryPlaylists(selected) },
                onDismiss = { showPlaylistPickerPopup = false }
            )
        }
    }
}

@Composable
private fun ImportHeader(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = str(Strings.back),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = str(Strings.import_playlists),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp
        )
    }

    Text(
        text = str(Strings.transfer_music_from_youtube_music_via_ac),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun YouTubeAccountCard(
    signedIn: Boolean,
    accountLabel: String,
    isLoadingPlaylists: Boolean,
    playlists: List<YouTubeMusicLibrary.LibraryPlaylist>?,
    isImporting: Boolean,
    importMessage: String?,
    onOpenPicker: () -> Unit,
    onNavigateToAccount: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.2.dp, YOUTUBE_RED.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(YOUTUBE_RED.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (signedIn) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                        contentDescription = str(Strings.youtube_music),
                        tint = YOUTUBE_RED,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = str(Strings.youtube_music_account),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (signedIn) {
                            if (accountLabel.isNotBlank()) accountLabel else str(Strings.signed_in)
                        } else str(Strings.sign_in_to_pick_playlists_directly),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (signedIn) {
                Text(
                    text = str(Strings.import_liked_music_and_playlists_directl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = onOpenPicker,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = YOUTUBE_RED,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                isLoadingPlaylists -> str(Strings.loading_playlists)
                                playlists != null && playlists.isNotEmpty() -> str(Strings.choose_playlists_x_available, playlists.size)
                                else -> str(Strings.choose_playlists_to_import)
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                if (isImporting) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = YOUTUBE_RED,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = importMessage ?: str(Strings.importing),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                } else if (importMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = importMessage,
                        color = if (importMessage.startsWith("Imported", ignoreCase = true) || importMessage.startsWith("Success", ignoreCase = true)) Color(0xFF16A34A) else Color(0xFFEF4444),
                        fontSize = 12.sp
                    )
                }
            } else {
                Text(
                    text = str(Strings.sign_in_to_your_youtube_account_to_pick),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = onNavigateToAccount,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = YOUTUBE_RED,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = str(Strings.sign_in_to_youtube),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeLinkCard(
    isImporting: Boolean,
    importMessage: String?,
    onImport: (String) -> Unit,
    onClearMessage: () -> Unit
) {
    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    var youtubeUrlInput by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = str(Strings.youtube_music_link),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                }

                if (isImporting) {
                    CircularProgressIndicator(
                        color = Color(0xFFEF4444),
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = str(Strings.paste_a_youtube_music_playlist_link_to_i),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = youtubeUrlInput,
                onValueChange = {
                    youtubeUrlInput = it
                    onClearMessage()
                },
                label = { Text(str(Strings.youtube_music_link), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                placeholder = { Text(str(Strings.paste_music_youtube_com_playlist_list), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFEF4444),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    cursorColor = Color(0xFFEF4444)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (youtubeUrlInput.isNotEmpty()) {
                            IconButton(onClick = {
                                youtubeUrlInput = ""
                                onClearMessage()
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = str(Strings.clear), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(
                            onClick = {
                                val pasted = platformActions.readClipboardText()
                                if (pasted != null) {
                                    youtubeUrlInput = pasted.trim()
                                    onClearMessage()
                                    platformActions.showMessage(str(Strings.pasted_youtube_music_link))
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = str(Strings.paste), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    if (youtubeUrlInput.isNotBlank() && !isImporting) {
                        onImport(youtubeUrlInput.trim())
                    }
                },
                enabled = youtubeUrlInput.isNotBlank() && !isImporting,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF4444),
                    disabledContainerColor = Color(0xFFEF4444).copy(alpha = 0.30f),
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.70f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = str(Strings.importing_songs),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = str(Strings.import_yt_music_playlist),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = str(Strings.tip_make_sure_your_playlist_is_set_to_pu),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            if (importMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = importMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (importMessage.startsWith("Imported", ignoreCase = true) || importMessage.startsWith("Success", ignoreCase = true)) Color(0xFF16A34A) else Color(0xFFEF4444),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SpotifyLinkCard(
    isImporting: Boolean,
    importMessage: String?,
    onImport: (String) -> Unit,
    onClearMessage: () -> Unit
) {
    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    var spotifyUrlInput by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color(0xFF1DB954).copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpotifyLogoIcon(modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = str(Strings.spotify_link),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                }

                if (isImporting) {
                    CircularProgressIndicator(
                        color = Color(0xFF1DB954),
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = str(Strings.paste_any_spotify_playlist_album_or_trac),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = spotifyUrlInput,
                onValueChange = {
                    spotifyUrlInput = it
                    onClearMessage()
                },
                label = { Text(str(Strings.spotify_link), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                placeholder = { Text(str(Strings.paste_open_spotify_com_playlist), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1DB954),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    cursorColor = Color(0xFF1DB954)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    SpotifyLogoIcon(modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (spotifyUrlInput.isNotEmpty()) {
                            IconButton(onClick = {
                                spotifyUrlInput = ""
                                onClearMessage()
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = str(Strings.clear), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(
                            onClick = {
                                val pasted = platformActions.readClipboardText()
                                if (pasted != null) {
                                    spotifyUrlInput = pasted.trim()
                                    onClearMessage()
                                    platformActions.showMessage(str(Strings.pasted_spotify_link))
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = str(Strings.paste), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    if (spotifyUrlInput.isNotBlank() && !isImporting) {
                        onImport(spotifyUrlInput.trim())
                    }
                },
                enabled = spotifyUrlInput.isNotBlank() && !isImporting,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1DB954),
                    disabledContainerColor = Color(0xFF1DB954).copy(alpha = 0.30f),
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.70f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = str(Strings.importing_songs),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = str(Strings.import_spotify_playlist),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF1DB954),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = str(Strings.tip_if_your_playlist_is_private_briefly),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            if (importMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = importMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (importMessage.startsWith("Imported", ignoreCase = true) || importMessage.startsWith("Success", ignoreCase = true)) Color(0xFF16A34A) else Color(0xFFEF4444),
                    fontSize = 11.sp
                )
            }
        }
    }
}

val SPOTIFY_GREEN = Color(0xFF1DB954)

@Composable
private fun SpotifyAccountCard(
    signedIn: Boolean,
    accountLabel: String,
    isLoadingPlaylists: Boolean,
    isImporting: Boolean,
    importMessage: String?,
    onImportAll: () -> Unit,
    onSignIn: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.2.dp, SPOTIFY_GREEN.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape)
                    .background(SPOTIFY_GREEN.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                SpotifyLogoIcon(modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(str(Strings.spotify_account), fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                Text(if (signedIn) accountLabel.ifBlank { str(Strings.signed_in) }
                    else str(Strings.sign_in_to_spotify),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(str(Strings.spotify_account_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = if (signedIn) onImportAll else onSignIn,
            enabled = !isImporting && !isLoadingPlaylists,
            colors = ButtonDefaults.buttonColors(containerColor = SPOTIFY_GREEN, contentColor = Color.White),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Icon(if (signedIn) Icons.Default.LibraryMusic else Icons.Default.AccountCircle,
                contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (!signedIn) str(Strings.sign_in_to_spotify)
                else if (isImporting) str(Strings.importing_songs)
                else if (isLoadingPlaylists) str(Strings.loading_playlists)
                else str(Strings.import_all_spotify_playlists),
                fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        if (isImporting || importMessage != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isImporting) {
                    CircularProgressIndicator(color = SPOTIFY_GREEN, strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(importMessage ?: str(Strings.importing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        if (signedIn && !isImporting) {
            TextButton(onClick = onDisconnect, modifier = Modifier.align(Alignment.End)) {
                Text(str(Strings.disconnect_spotify), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp)
            }
        }
    }
}


const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"


/** Scrollable bottom sheet containing playlists from the signed-in Spotify account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyPlaylistPickerBottomSheet(
    playlists: List<SpotifyLibrary.LibraryPlaylist>?,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    selected: SnapshotStateList<String>,
    isImporting: Boolean,
    importMessage: String?,
    onImport: (List<SpotifyLibrary.LibraryPlaylist>) -> Unit,
    onSignInAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(str(Strings.your_spotify_playlists), fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, modifier = Modifier.weight(1f))
                val all = playlists.orEmpty()
                if (all.isNotEmpty()) {
                    TextButton(onClick = {
                        val allSelected = all.all { it.id in selected }
                        selected.clear()
                        if (!allSelected) selected.addAll(all.map { it.id })
                    }) {
                        Text(if (all.all { it.id in selected }) str(Strings.clear)
                            else str(Strings.select_all), color = SPOTIFY_GREEN)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = str(Strings.close))
                }
            }
            Text(str(Strings.spotify_picker_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp))
            when {
                isLoading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SPOTIFY_GREEN)
                }
                loadFailed || playlists == null -> Column(Modifier.fillMaxWidth().height(180.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(str(Strings.spotify_playlists_load_failed))
                    TextButton(onClick = onRetry) { Text(str(Strings.try_again), color = SPOTIFY_GREEN) }
                    TextButton(onClick = onSignInAgain) { Text(str(Strings.sign_in_again), color = SPOTIFY_GREEN) }
                }
                playlists.isEmpty() -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text(str(Strings.spotify_playlists_empty))
                }
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(playlists, key = { it.id }) { item ->
                        val checked = item.id in selected
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (checked) SPOTIFY_GREEN.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable(enabled = !isImporting) {
                                    if (checked) selected.remove(item.id) else selected.add(item.id)
                                }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                if (item.isLikedSongs) Icon(Icons.Default.Favorite, null, tint = SPOTIFY_GREEN)
                                else if (item.thumbnail != null) com.auralis.music.ui.components.LocalArtworkRenderer.current.Image(
                                    url = item.thumbnail, contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    crossfade = false, modifier = Modifier.fillMaxSize())
                                else SpotifyLogoIcon(modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(item.subtitle, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Checkbox(checked = checked, enabled = !isImporting,
                                onCheckedChange = { if (it) selected.add(item.id) else selected.remove(item.id) },
                                colors = CheckboxDefaults.colors(checkedColor = SPOTIFY_GREEN))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onImport(playlists.orEmpty().filter { it.id in selected }) },
                enabled = selected.isNotEmpty() && !isImporting,
                colors = ButtonDefaults.buttonColors(containerColor = SPOTIFY_GREEN,
                    disabledContainerColor = SPOTIFY_GREEN.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                if (isImporting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (selected.isEmpty()) str(Strings.select_playlists_to_import)
                    else "Import ${selected.size} ${if (selected.size == 1) "playlist" else "playlists"}")
            }
            if (importMessage != null) Text(importMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Scrollable bottom sheet modal popup containing all the signed-in YouTube Music playlists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubePlaylistPickerBottomSheet(
    playlists: List<YouTubeMusicLibrary.LibraryPlaylist>?,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    selected: SnapshotStateList<String>,
    isImporting: Boolean,
    importMessage: String?,
    onImport: (List<YouTubeMusicLibrary.LibraryPlaylist>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                Spacer(Modifier.height(10.dp))
            }
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Header: Title + Select all / Clear + Close button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                Text(
                    text = str(Strings.your_youtube_music_playlists),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f)
                )

                val all = playlists.orEmpty()
                if (all.isNotEmpty()) {
                    val allSelected = selected.size == all.size
                    TextButton(
                        onClick = {
                            selected.clear()
                            if (!allSelected) selected.addAll(all.map { it.id })
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (allSelected) str(Strings.clear) else str(Strings.select_all),
                            color = YOUTUBE_RED,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = str(Strings.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = str(Strings.private_playlists_included_liked_music_i),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Body: Loading / Error / Empty / Scrollable list
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = YOUTUBE_RED, modifier = Modifier.size(32.dp))
                    }
                }
                loadFailed || playlists == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = str(Strings.couldn_t_load_your_playlists_from_youtub),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Text(str(Strings.try_again), color = YOUTUBE_RED, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                playlists.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = str(Strings.no_playlists_found_in_your_youtube_music),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
                else -> {
                    // Scrollable list bounded by weight so it never exceeds screen bounds
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(playlists, key = { it.id }) { item ->
                            val checked = item.id in selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (checked) YOUTUBE_RED.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable(enabled = !isImporting) {
                                        if (checked) selected.remove(item.id) else selected.add(item.id)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.isLikedMusic) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = YOUTUBE_RED,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    } else if (item.thumbnail != null) {
                                        com.auralis.music.ui.components.LocalArtworkRenderer.current.Image(
                                            url = item.thumbnail,
                                            contentDescription = null,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            crossfade = false,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = YOUTUBE_RED,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (item.subtitle.isNotBlank()) {
                                        Text(
                                            text = item.subtitle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Checkbox(
                                    checked = checked,
                                    enabled = !isImporting,
                                    onCheckedChange = { on ->
                                        if (on) selected.add(item.id) else selected.remove(item.id)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = YOUTUBE_RED)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action button
            Button(
                onClick = {
                    val toImport = playlists?.filter { it.id in selected }.orEmpty()
                    if (toImport.isNotEmpty()) {
                        onImport(toImport)
                    }
                },
                enabled = selected.isNotEmpty() && !isImporting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = YOUTUBE_RED,
                    disabledContainerColor = YOUTUBE_RED.copy(alpha = 0.35f),
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = str(Strings.importing),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        text = when {
                            selected.isEmpty() -> str(Strings.select_playlists_to_import)
                            else -> "Import ${selected.size} ${if (selected.size == 1) "playlist" else "playlists"}"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            if (importMessage != null) {
                Text(
                    text = importMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
