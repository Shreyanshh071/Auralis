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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
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
    modifier: Modifier = Modifier,
    compact: Boolean = false
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
        if (!compact) return
    }

    if (compact) {
        var byLink by remember { mutableStateOf(false) }
        var spotifyLink by remember { mutableStateOf(false) }
        CompositionLocalProvider(LocalImportGlass provides true) {
        Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlaylistAdd, null, modifier = Modifier.size(20.dp))
                Text("Import playlists", Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.Bold)
                IconButton(onDismiss, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Close") }
            }
            Text("Bring your music into Auralis.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .08f)).padding(4.dp)) {
                listOf("Accounts", "By link").forEachIndexed { index, label ->
                    val active = byLink == (index == 1)
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (active) Color.White.copy(alpha = .22f) else Color.Transparent)
                        .clickable { byLink = index == 1; showPlaylistPickerPopup = false }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            if (byLink) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !spotifyLink, onClick = { spotifyLink = false }, label = { Text("YouTube Music") }, colors = importChipColors())
                    FilterChip(selected = spotifyLink, onClick = { spotifyLink = true }, label = { Text("Spotify") }, colors = importChipColors())
                }
                if (spotifyLink) SpotifyLinkCard(
                    isImporting = isImportingSpotify && !spotifyImportFromAccount,
                    importMessage = if (!spotifyImportFromAccount) spotifyImportMessage else null,
                    onImport = { spotifyImportFromAccount = false; onImportSpotifyPlaylist(it) },
                    onClearMessage = onClearSpotifyImportMessage
                ) else YouTubeLinkCard(isImportingYouTube, youtubeImportMessage, onImportYouTubePlaylist, onClearYouTubeImportMessage)
            } else if (showPlaylistPickerPopup) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your YouTube Music playlists", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton({ showPlaylistPickerPopup = false }) { Text("Back") }
                }
                when {
                    isLoadingPlaylists -> CircularProgressIndicator(Modifier.size(24.dp))
                    loadFailed -> {
                        Text("Couldn't load your playlists.")
                        TextButton({ reloadKey++ }) { Text("Try again") }
                    }
                    playlists.isNullOrEmpty() -> Text("No playlists found in this account.")
                    else -> {
                        val all = playlists.orEmpty()
                        TextButton({ if (selectedPlaylists.size == all.size) selectedPlaylists.clear() else { selectedPlaylists.clear(); selectedPlaylists.addAll(all.map { it.id }) } }) {
                            Text(if (selectedPlaylists.size == all.size) "Clear selection" else "Select all")
                        }
                        all.forEach { playlist ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !isImportingYouTube) {
                                if (playlist.id in selectedPlaylists) selectedPlaylists.remove(playlist.id) else selectedPlaylists.add(playlist.id)
                            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(playlist.id in selectedPlaylists, null)
                                Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Button({ onImportYouTubeLibraryPlaylists(all.filter { it.id in selectedPlaylists }) }, Modifier.fillMaxWidth(), enabled = selectedPlaylists.isNotEmpty() && !isImportingYouTube) {
                            Text(if (isImportingYouTube) "Importing…" else "Import selected (${selectedPlaylists.size})")
                        }
                    }
                }
                youtubeImportMessage?.let { Text(it, fontSize = 13.sp) }
            } else {
                YouTubeAccountCard(youTubeSignedIn, accountLabel, isLoadingPlaylists, playlists, isImportingYouTube, youtubeImportMessage,
                    { showPlaylistPickerPopup = true }, onNavigateToYouTubeAccount)
                SpotifyAccountCard(spotifySignedIn, spotifyAccountLabel, isLoadingSpotifyPlaylists,
                    isImportingSpotify && spotifyImportFromAccount,
                    if (spotifyPlaylistLoadFailed) "Couldn't load Spotify playlists. Try again." else if (spotifyImportFromAccount) spotifyImportMessage else null,
                    { spotifyImportFromAccount = true; importAllAfterSpotifySignIn = true; spotifyReloadKey++ },
                    { if (spotifySignIn != null) showSpotifySignIn = true else platformActions.showMessage(com.auralis.music.ui.settings.PlatformNotes.noWebSignIn) },
                    { SpotifySession.signOut() })
            }
        }
        }
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
    com.auralis.music.ui.components.PageHeader(str(Strings.import_playlists), onDismiss)

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
            .background(IMPORT_SURFACE)
            .border(1.dp, IMPORT_ACCENT.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
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
                        .background(IMPORT_ACCENT.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (signedIn) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                        contentDescription = str(Strings.youtube_music),
                        tint = IMPORT_ACCENT,
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
                        containerColor = IMPORT_BUTTON_SURFACE,
                        contentColor = IMPORT_ON_ACCENT,
                        disabledContainerColor = IMPORT_DISABLED_BUTTON_SURFACE,
                        disabledContentColor = IMPORT_ON_ACCENT.copy(alpha = .55f)
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
                            color = IMPORT_ACCENT,
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
                        containerColor = IMPORT_BUTTON_SURFACE,
                        contentColor = IMPORT_ON_ACCENT,
                        disabledContainerColor = IMPORT_DISABLED_BUTTON_SURFACE,
                        disabledContentColor = IMPORT_ON_ACCENT.copy(alpha = .55f)
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
            .background(IMPORT_SURFACE)
            .border(1.dp, IMPORT_ACCENT.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
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
                        tint = IMPORT_ACCENT,
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
                        color = IMPORT_ACCENT,
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
                    focusedBorderColor = IMPORT_ACCENT,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = IMPORT_FIELD_SURFACE,
                    unfocusedContainerColor = IMPORT_FIELD_SURFACE,
                    cursorColor = IMPORT_ACCENT
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = IMPORT_ACCENT, modifier = Modifier.size(18.dp))
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
                    containerColor = IMPORT_BUTTON_SURFACE,
                    disabledContainerColor = IMPORT_DISABLED_BUTTON_SURFACE,
                    contentColor = IMPORT_ON_ACCENT,
                    disabledContentColor = IMPORT_ON_ACCENT.copy(alpha = 0.70f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = IMPORT_ON_ACCENT,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = str(Strings.importing_songs),
                        color = IMPORT_ON_ACCENT,
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
                            tint = IMPORT_ON_ACCENT,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = str(Strings.import_yt_music_playlist),
                            color = IMPORT_ON_ACCENT,
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
                    .background(IMPORT_TIP_SURFACE)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = IMPORT_ACCENT,
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
            .background(IMPORT_SURFACE)
            .border(1.dp, IMPORT_ACCENT.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
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
                        color = IMPORT_ACCENT,
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
                    focusedBorderColor = IMPORT_ACCENT,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = IMPORT_FIELD_SURFACE,
                    unfocusedContainerColor = IMPORT_FIELD_SURFACE,
                    cursorColor = IMPORT_ACCENT
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
                    containerColor = IMPORT_BUTTON_SURFACE,
                    disabledContainerColor = IMPORT_DISABLED_BUTTON_SURFACE,
                    contentColor = IMPORT_ON_ACCENT,
                    disabledContentColor = IMPORT_ON_ACCENT.copy(alpha = 0.70f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = IMPORT_ON_ACCENT,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = str(Strings.importing_songs),
                        color = IMPORT_ON_ACCENT,
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
                            tint = IMPORT_ON_ACCENT,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = str(Strings.import_spotify_playlist),
                            color = IMPORT_ON_ACCENT,
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
                    .background(IMPORT_TIP_SURFACE)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = IMPORT_ACCENT,
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

// Compact imports sit on the parent's captured, blurred artwork. Keep every inner surface
// translucent so it remains visible through the cards, fields and controls.
private val LocalImportGlass = staticCompositionLocalOf { false }
private val IMPORT_SURFACE: Color
    @Composable get() = if (LocalImportGlass.current) Color.White.copy(alpha = .045f) else MaterialTheme.colorScheme.surface
private val IMPORT_FIELD_SURFACE: Color
    @Composable get() = if (LocalImportGlass.current) Color.White.copy(alpha = .055f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)
private val IMPORT_TIP_SURFACE: Color
    @Composable get() = if (LocalImportGlass.current) Color.White.copy(alpha = .04f) else MaterialTheme.colorScheme.surfaceVariant
private val IMPORT_BUTTON_SURFACE: Color
    @Composable get() = if (LocalImportGlass.current) Color.White.copy(alpha = .18f) else IMPORT_ACCENT
private val IMPORT_DISABLED_BUTTON_SURFACE: Color
    @Composable get() = if (LocalImportGlass.current) Color.White.copy(alpha = .075f) else IMPORT_ACCENT.copy(alpha = .30f)

// One neutral accent from the current theme (Liquidify, colour themes, light or dark) instead of
// brand red and green, so the page sits quietly on whatever background is showing.
private val IMPORT_ACCENT: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
// Playlist pickers are solid sheets: the theme's surface is see-through under artwork theming,
// so it is laid over an opaque base (dark or light to match the theme).
private val SHEET_COLOR: Color
    @Composable get() {
        val scheme = MaterialTheme.colorScheme
        val base = if (scheme.onSurface.luminance() > 0.5f) Color(0xFF141317) else Color(0xFFF7F5F8)
        return scheme.surface.compositeOver(base)
    }
private val IMPORT_ON_ACCENT: Color
    @Composable get() = if (LocalImportGlass.current) Color.White else if (MaterialTheme.colorScheme.onSurface.luminance() > 0.5f) Color.Black else Color.White

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
            .background(IMPORT_SURFACE)
            .border(1.dp, IMPORT_ACCENT.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape)
                    .background(IMPORT_ACCENT.copy(alpha = 0.08f)),
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
            colors = ButtonDefaults.buttonColors(containerColor = IMPORT_BUTTON_SURFACE, contentColor = IMPORT_ON_ACCENT, disabledContainerColor = IMPORT_DISABLED_BUTTON_SURFACE, disabledContentColor = IMPORT_ON_ACCENT.copy(alpha = .55f)),
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
                    CircularProgressIndicator(color = IMPORT_ACCENT, strokeWidth = 2.dp,
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
        containerColor = SHEET_COLOR
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
                            else str(Strings.select_all), color = IMPORT_ACCENT)
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
                    CircularProgressIndicator(color = IMPORT_ACCENT)
                }
                loadFailed || playlists == null -> Column(Modifier.fillMaxWidth().height(180.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(str(Strings.spotify_playlists_load_failed))
                    TextButton(onClick = onRetry) { Text(str(Strings.try_again), color = IMPORT_ACCENT) }
                    TextButton(onClick = onSignInAgain) { Text(str(Strings.sign_in_again), color = IMPORT_ACCENT) }
                }
                playlists.isEmpty() -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text(str(Strings.spotify_playlists_empty))
                }
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(playlists, key = { it.id }) { item ->
                        val checked = item.id in selected
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (checked) IMPORT_ACCENT.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable(enabled = !isImporting) {
                                    if (checked) selected.remove(item.id) else selected.add(item.id)
                                }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                                .background(IMPORT_TIP_SURFACE), contentAlignment = Alignment.Center) {
                                if (item.isLikedSongs) Icon(Icons.Default.Favorite, null, tint = IMPORT_ACCENT)
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
                                colors = CheckboxDefaults.colors(checkedColor = IMPORT_ACCENT))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onImport(playlists.orEmpty().filter { it.id in selected }) },
                enabled = selected.isNotEmpty() && !isImporting,
                colors = ButtonDefaults.buttonColors(containerColor = IMPORT_BUTTON_SURFACE, contentColor = IMPORT_ON_ACCENT,
                    disabledContainerColor = IMPORT_ACCENT.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                if (isImporting) CircularProgressIndicator(Modifier.size(18.dp), color = IMPORT_ON_ACCENT, strokeWidth = 2.dp)
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
        containerColor = SHEET_COLOR,
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
                            color = IMPORT_ACCENT,
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
                        CircularProgressIndicator(color = IMPORT_ACCENT, modifier = Modifier.size(32.dp))
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
                            Text(str(Strings.try_again), color = IMPORT_ACCENT, fontWeight = FontWeight.Bold)
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
                                    .background(if (checked) IMPORT_ACCENT.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable(enabled = !isImporting) {
                                        if (checked) selected.remove(item.id) else selected.add(item.id)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(IMPORT_TIP_SURFACE),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.isLikedMusic) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = IMPORT_ACCENT,
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
                                            tint = IMPORT_ACCENT,
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
                                    colors = CheckboxDefaults.colors(checkedColor = IMPORT_ACCENT)
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
                    containerColor = IMPORT_BUTTON_SURFACE,
                    disabledContainerColor = IMPORT_ACCENT.copy(alpha = 0.35f),
                    contentColor = IMPORT_ON_ACCENT,
                    disabledContentColor = IMPORT_ON_ACCENT.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        color = IMPORT_ON_ACCENT,
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

@Composable
private fun importChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.White.copy(alpha = .04f),
    selectedContainerColor = Color.White.copy(alpha = .18f),
    labelColor = Color.White.copy(alpha = .75f), selectedLabelColor = Color.White
)
