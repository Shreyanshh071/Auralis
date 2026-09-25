package com.auralis.music.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.download.AuralisDownloadManager
import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.launch

private val CARD_CONTAINER_COLOR = Color(0xFF262021)

/**
 * YouTube Music style Modal Bottom Sheet for Track Options:
 * - Compact drag handle
 * - Artwork, Title, Subtitle (Artist), Favorite heart
 * - Quick Action Buttons: [ Play next ], [ Add ], [ Share ]
 * - Action Group 1: Start radio, Add to queue
 * - Action Group 2: Pin to Speed dial
 * - Action Group 3: Add to library / Remove from library
 * - Action Group 4: Download
 * - Action Group 5: View artist, View album (with authentic album metadata)
 * - Action Group 6: Recommend to room (Listen Together, if in room)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsMenu(
    track: Track,
    isFavorite: Boolean,
    userPlaylists: List<Playlist>,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
    onPinToSpeedDial: (() -> Unit)? = null,
    isPinned: Boolean = false,
    onGoToArtist: (() -> Unit)? = null,
    onGoToAlbum: ((albumId: String?, albumTitle: String, albumArtist: String?, albumArt: String?) -> Unit)? = null,
    onAddToPlaylist: (Playlist) -> Unit,
    onCreatePlaylistAndAdd: (String) -> Unit,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    onDismiss: () -> Unit,
    queueReferenceStyle: Boolean = false,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dynamicSurface = MaterialTheme.colorScheme.surface
    val dynamicPrimary = MaterialTheme.colorScheme.primary

    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var localIsFavorite by remember(isFavorite) { mutableStateOf(isFavorite) }
    var localIsPinned by remember(track.id, isPinned) { mutableStateOf(isPinned) }

    // Authentic Album Resolution (Apple Music / iTunes query for true parent album)
    val cachedAlbum = remember(track.id, track.title, track.artist) {
        AlbumMetadataResolver.getCached(track.title, track.artist)
    }
    val isRedundantTrackAlbum = remember(track.album, track.title) {
        AlbumMetadataResolver.isRedundantOrSingle(track.album, track.title)
    }

    val initialAlbumName = when {
        !isRedundantTrackAlbum && !track.album.isNullOrBlank() -> track.album
        cachedAlbum != null && !cachedAlbum.albumTitle.isNullOrBlank() && !cachedAlbum.isSingle -> cachedAlbum.albumTitle
        else -> null
    }
    val initialAlbumId = when {
        !isRedundantTrackAlbum && !track.albumId.isNullOrBlank() -> track.albumId
        cachedAlbum != null && !cachedAlbum.albumId.isNullOrBlank() && !cachedAlbum.isSingle -> cachedAlbum.albumId
        else -> null
    }

    var resolvedAlbumName by remember(track.id) { mutableStateOf(initialAlbumName) }
    var resolvedAlbumId by remember(track.id) { mutableStateOf(initialAlbumId) }
    var resolvedArtistName by remember(track.id) { mutableStateOf(cachedAlbum?.artistName) }
    var resolvedAlbumArt by remember(track.id) { mutableStateOf(cachedAlbum?.albumArt) }

    LaunchedEffect(track.id, track.title, track.artist, track.album) {
        if (resolvedAlbumName.isNullOrBlank() || resolvedAlbumId.isNullOrBlank() ||
            AlbumMetadataResolver.isRedundantOrSingle(resolvedAlbumName, track.title)) {
            val resolved = AlbumMetadataResolver.resolveAlbum(
                trackTitle = track.title,
                artistName = track.artist,
                knownAlbum = track.album
            )
            if (resolved != null && !resolved.albumTitle.isNullOrBlank() && !resolved.isSingle) {
                val keepExistingTitle = !isRedundantTrackAlbum && !track.album.isNullOrBlank()
                if (!keepExistingTitle || resolved.albumTitle.contains(track.album!!, ignoreCase = true) || track.album!!.contains(resolved.albumTitle, ignoreCase = true)) {
                    resolvedAlbumName = resolved.albumTitle
                }
                resolvedAlbumId = resolved.albumId
                resolvedArtistName = resolved.artistName
                resolvedAlbumArt = resolved.albumArt
            }
        }
    }

    val displayAlbum = resolvedAlbumName
        ?: track.album.takeIf { !it.isNullOrBlank() && !AlbumMetadataResolver.isRedundantOrSingle(it, track.title) }
        ?: "Album"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = dynamicSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // ── DRAG HANDLE ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                )
            }

            if (!showPlaylistPicker) {
                // ── HEADER ROW (Artwork, Title, Subtitle: Artist, Favorite Heart) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(
                        url = track.thumbnail,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                        cornerRadius = 10.dp,
                        contentDescription = track.title
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = track.artist.ifBlank { "Unknown Artist" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val newFav = !localIsFavorite
                            localIsFavorite = newFav
                            onToggleFavorite()
                            Toast.makeText(
                                context,
                                if (newFav) "Saved to Library" else "Removed from Library",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = if (localIsFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (localIsFavorite) "Saved" else "Save",
                            tint = if (localIsFavorite) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
                )

                // ── SCROLLABLE ACTIONS CONTENT ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Classic Queue follows the reference's Radio / Add / Share action row.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Pill 1: Radio in classic Queue, Play next elsewhere.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(CARD_CONTAINER_COLOR)
                                .clickable {
                                    if (queueReferenceStyle) onStartRadio?.invoke() else onPlayNext()
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (queueReferenceStyle) Icons.Default.Sensors else Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = if (queueReferenceStyle) "Radio" else "Play next",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (queueReferenceStyle) "Radio" else "Play next",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                            }
                        }

                        // Pill 2: Add
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(CARD_CONTAINER_COLOR)
                                .clickable {
                                    showPlaylistPicker = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "Add",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Add",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                            }
                        }

                        // Pill 3: Share
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(CARD_CONTAINER_COLOR)
                                .clickable {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, track.title)
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Listen to '${track.title}' by ${track.artist} on Auralis Music\nhttps://music.youtube.com/watch?v=${track.id}\n\nDownload Auralis App: https://auralis-self-nu.vercel.app/"
                                        )
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Track"))
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Share",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // ── GROUP 1: PLAY NEXT / RADIO, ADD TO QUEUE ──
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CARD_CONTAINER_COLOR)
                    ) {
                        TrackOptionRow(
                            icon = if (queueReferenceStyle) Icons.AutoMirrored.Filled.PlaylistPlay else Icons.Default.Sensors,
                            title = if (queueReferenceStyle) "Play next" else "Start radio",
                            subtitle = if (queueReferenceStyle) "Add to the top of your queue" else "Create a station based on this item",
                            onClick = {
                                if (queueReferenceStyle) onPlayNext() else onStartRadio?.invoke()
                                onDismiss()
                            }
                        )

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.05f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        TrackOptionRow(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            title = "Add to queue",
                            subtitle = "Add to the bottom of your queue",
                            onClick = {
                                onAddToQueue()
                                onDismiss()
                            }
                        )
                    }

                    // ── GROUP 2: PIN TO SPEED DIAL ──
                    if (!queueReferenceStyle) Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CARD_CONTAINER_COLOR)
                    ) {
                        TrackOptionRow(
                            icon = if (localIsPinned) Icons.Default.PushPin else Icons.Default.Add,
                            title = if (localIsPinned) "Unpin from Speed dial" else "Pin to Speed dial",
                            subtitle = null,
                            onClick = {
                                val nextPinned = !localIsPinned
                                localIsPinned = nextPinned
                                val msg = if (nextPinned) "Pinned to Speed dial" else "Unpinned from Speed dial"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                onDismiss()
                                onPinToSpeedDial?.invoke()
                            }
                        )
                    }

                    // ── GROUP 3: ADD TO LIBRARY ──
                    if (!queueReferenceStyle) Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CARD_CONTAINER_COLOR)
                    ) {
                        TrackOptionRow(
                            icon = if (localIsFavorite) Icons.Default.LibraryAddCheck else Icons.Default.LibraryAdd,
                            title = if (localIsFavorite) "Remove from library" else "Add to library",
                            subtitle = if (localIsFavorite) "Remove from your library" else "Save to your library",
                            iconTint = if (localIsFavorite) Color(0xFFFF4081) else Color.White.copy(alpha = 0.9f),
                            titleColor = if (localIsFavorite) Color(0xFFFF4081) else Color.White,
                            onClick = {
                                val newFav = !localIsFavorite
                                localIsFavorite = newFav
                                onToggleFavorite()
                                Toast.makeText(
                                    context,
                                    if (newFav) "Saved to Library" else "Removed from Library",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            }
                        )
                    }

                    // ── GROUP 4: DOWNLOAD ──
                    val isDownloaded = AuralisDownloadManager.isDownloaded(track.id)
                    val isDownloading = AuralisDownloadManager.isDownloading(track.id)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CARD_CONTAINER_COLOR)
                    ) {
                        TrackOptionRow(
                            icon = if (isDownloaded) Icons.Default.DownloadDone else if (isDownloading) Icons.Default.CloudDownload else Icons.Default.Download,
                            title = if (isDownloaded) "Remove download" else if (isDownloading) "Downloading..." else "Download",
                            subtitle = if (isDownloaded) "Downloaded to device" else if (isDownloading) "Saving for offline playback" else "Make available for offline playback",
                            iconTint = if (isDownloaded) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.9f),
                            titleColor = if (isDownloaded) Color(0xFF4CAF50) else Color.White,
                            onClick = {
                                if (isDownloaded) {
                                    AuralisDownloadManager.removeDownload(track.id)
                                } else {
                                    AuralisDownloadManager.downloadTrack(track)
                                }
                                onDismiss()
                            }
                        )
                    }

                    // ── GROUP 5: VIEW ARTIST, VIEW ALBUM ──
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CARD_CONTAINER_COLOR)
                    ) {
                        TrackOptionRow(
                            icon = Icons.Default.Person,
                            title = "View artist",
                            subtitle = track.artist.ifBlank { "Unknown Artist" },
                            onClick = {
                                onGoToArtist?.invoke()
                                onDismiss()
                            }
                        )

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.05f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        TrackOptionRow(
                            icon = Icons.Default.Album,
                            title = "View album",
                            subtitle = displayAlbum,
                            onClick = {
                                val targetAlbumId = resolvedAlbumId
                                    ?: track.albumId.takeIf { !AlbumMetadataResolver.isRedundantOrSingle(track.album, track.title) }
                                onGoToAlbum?.invoke(
                                    targetAlbumId,
                                    displayAlbum,
                                    resolvedArtistName ?: track.artist,
                                    resolvedAlbumArt ?: track.thumbnail
                                )
                                onDismiss()
                            }
                        )
                    }

                    // ── GROUP 6: LISTEN TOGETHER (IF IN ROOM) ──
                    if (isInListenTogetherRoom && onRecommendToRoom != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(CARD_CONTAINER_COLOR)
                        ) {
                            TrackOptionRow(
                                icon = Icons.Default.Group,
                                title = "Recommend to room",
                                subtitle = "Share track with everyone in the room",
                                onClick = {
                                    onRecommendToRoom(track)
                                    onDismiss()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                }
            } else {
                // ── PLAYLIST PICKER VIEW ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { showPlaylistPicker = false }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Save to playlist",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = { showCreatePlaylistDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Playlist",
                            tint = dynamicPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (userPlaylists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No custom playlists yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showCreatePlaylistDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = dynamicPrimary,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Create Playlist", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(userPlaylists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onAddToPlaylist(playlist)
                                        showPlaylistPicker = false
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkCard(
                                    url = playlist.coverUrl ?: playlist.tracks.firstOrNull()?.thumbnail,
                                    modifier = Modifier.size(42.dp),
                                    cornerRadius = 8.dp,
                                    contentDescription = playlist.title
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${playlist.tracks.size} songs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add",
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // ── CREATE PLAYLIST DIALOG ──
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreatePlaylistDialog = false
                newPlaylistName = ""
            },
            title = {
                Text("New Playlist", fontWeight = FontWeight.Bold)
            },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = dynamicPrimary,
                        focusedLabelColor = dynamicPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onCreatePlaylistAndAdd(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreatePlaylistDialog = false
                            showPlaylistPicker = false
                            onDismiss()
                        }
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) {
                    Text("Create & Add", fontWeight = FontWeight.Bold, color = dynamicPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreatePlaylistDialog = false
                    newPlaylistName = ""
                }) {
                    Text("Cancel")
                }
            },
            containerColor = dynamicSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TrackOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = Color.White.copy(alpha = 0.9f),
    titleColor: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

