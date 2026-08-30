package com.auralis.music.ui.components

import android.content.Intent
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
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track

val OPTIONS_BG: Color
    @Composable get() = MaterialTheme.colorScheme.surface
val OPTIONS_LIME: Color
    @Composable get() = MaterialTheme.colorScheme.primary
val OPTIONS_CARD_BG: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

/**
 * YouTube Music & Spotify style Expandable Modal Bottom Sheet for Track Options.
 * Supports partial drag preview and pull-up expansion with dynamic theme styling.
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
    onGoToArtist: (() -> Unit)? = null,
    onAddToPlaylist: (Playlist) -> Unit,
    onCreatePlaylistAndAdd: (String) -> Unit,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dynamicPrimary = MaterialTheme.colorScheme.primary
    val dynamicSurface = MaterialTheme.colorScheme.surface
    val dynamicSurfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var localIsFavorite by remember(isFavorite) { mutableStateOf(isFavorite) }

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
            // ── COMPACT DRAG HANDLE (Removes excessive empty whitespace) ──
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
                        .background(Color.White.copy(alpha = 0.35f))
                )
            }

            // ── TRACK HEADER (Artwork, Title, Artist, Album / Duration, Quick Actions) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkCard(
                    url = track.thumbnail,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                    cornerRadius = 10.dp,
                    contentDescription = track.title
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val durationText = if (track.duration > 0) {
                        val mins = track.duration / 60
                        val secs = track.duration % 60
                        "%d:%02d".format(mins, secs)
                    } else null

                    val subtitle = listOfNotNull(
                        track.artist.takeIf { it.isNotBlank() },
                        track.album.takeIf { !it.isNullOrBlank() },
                        durationText
                    ).joinToString(" • ")

                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Quick Header Action Icons (Like Toggle + Share)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = {
                            val newFav = !localIsFavorite
                            localIsFavorite = newFav
                            onToggleFavorite()
                            android.widget.Toast.makeText(
                                context,
                                if (newFav) "Added to Liked songs" else "Removed from Liked songs",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (localIsFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (localIsFavorite) "Liked" else "Like",
                            tint = if (localIsFavorite) OPTIONS_LIME else Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = {
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
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(bottom = 6.dp)
            )

            if (!showPlaylistPicker) {
                // ── ACTIONS LIST (Scrollable for smooth pull-up expansion) ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 1. Play Next
                    TrackOptionItem(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        label = "Play next",
                        onClick = {
                            onPlayNext()
                            onDismiss()
                        }
                    )

                    // 2. Add to Queue
                    TrackOptionItem(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        label = "Add to queue",
                        onClick = {
                            onAddToQueue()
                            onDismiss()
                        }
                    )

                    // 3. Save to Playlist
                    TrackOptionItem(
                        icon = Icons.Default.BookmarkBorder,
                        label = "Save to playlist",
                        onClick = { showPlaylistPicker = true }
                    )

                    // 4. Download Song / Remove Download
                    val isDownloaded = com.auralis.music.data.download.AuralisDownloadManager.isDownloaded(track.id)
                    val isDownloading = com.auralis.music.data.download.AuralisDownloadManager.isDownloading(track.id)
                    TrackOptionItem(
                        icon = if (isDownloaded) Icons.Default.DownloadDone else if (isDownloading) Icons.Default.CloudDownload else Icons.Default.Download,
                        label = if (isDownloaded) "Remove download" else if (isDownloading) "Downloading..." else "Download song",
                        iconTint = if (isDownloaded) OPTIONS_LIME else Color.White.copy(alpha = 0.85f),
                        labelColor = if (isDownloaded) OPTIONS_LIME else Color.White,
                        onClick = {
                            if (isDownloaded) {
                                com.auralis.music.data.download.AuralisDownloadManager.removeDownload(track.id)
                            } else {
                                com.auralis.music.data.download.AuralisDownloadManager.downloadTrack(track)
                            }
                            onDismiss()
                        }
                    )

                    // 4. Favorite / Liked Songs Toggle
                    TrackOptionItem(
                        icon = if (localIsFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        label = if (localIsFavorite) "Remove from Liked songs" else "Add to Liked songs",
                        iconTint = if (localIsFavorite) OPTIONS_LIME else Color.White.copy(alpha = 0.85f),
                        labelColor = if (localIsFavorite) OPTIONS_LIME else Color.White,
                        onClick = {
                            val newFav = !localIsFavorite
                            localIsFavorite = newFav
                            onToggleFavorite()
                            android.widget.Toast.makeText(
                                context,
                                if (newFav) "Added to Liked songs" else "Removed from Liked songs",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        }
                    )

                    // 5. View Artist Page
                    if (!track.artist.isNullOrBlank() && onGoToArtist != null) {
                        TrackOptionItem(
                            icon = Icons.Default.AccountCircle,
                            label = "Go to artist (${track.artist})",
                            onClick = {
                                onGoToArtist()
                                onDismiss()
                            }
                        )
                    }


                    // 8. Share Song
                    TrackOptionItem(
                        icon = Icons.Default.Share,
                        label = "Share",
                        onClick = {
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
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
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
                            imageVector = Icons.Default.ArrowBack,
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
                            tint = OPTIONS_LIME
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
                                    containerColor = OPTIONS_LIME,
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
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(userPlaylists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(OPTIONS_CARD_BG)
                                    .clickable {
                                        onAddToPlaylist(playlist)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(dynamicPrimary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                        contentDescription = null,
                                        tint = OPTIONS_LIME,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
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
                                        text = "${playlist.tracks.size} tracks",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add",
                                    tint = Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                }
            }
        }
    }

    // ── CREATE PLAYLIST DIALOG ──
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            containerColor = Color(0xFF1E2117),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "New Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 20.sp
                )
            },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name", color = Color.White.copy(alpha = 0.6f)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OPTIONS_LIME,
                        focusedLabelColor = OPTIONS_LIME,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onCreatePlaylistAndAdd(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreatePlaylistDialog = false
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OPTIONS_LIME,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Create & Add", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreatePlaylistDialog = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun TrackOptionItem(
    icon: ImageVector,
    label: String,
    iconTint: Color = Color.White.copy(alpha = 0.85f),
    labelColor: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
