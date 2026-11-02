package com.auralis.music.ui.components

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track

/**
 * Opened by the '+' button on the MiniPlayer: one tap adds to a playlist and the sheet stays open,
 * so a song can go into several.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerBottomSheet(
    track: Track,
    userPlaylists: List<Playlist>,
    onAddToPlaylist: (Playlist) -> Unit,
    onCreatePlaylistAndAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var recentlyAddedId by remember { mutableStateOf<String?>(null) }

    val glass = com.auralis.music.ui.glass.LocalLiquidGlass.current
    val modalVisual = com.auralis.music.ui.player.LocalPlayerModalVisualState.current
    DisposableEffect(modalVisual, glass != null) {
        if (glass != null) modalVisual?.opened()
        onDispose { if (glass != null) modalVisual?.closed() }
    }

    AuralisOptionSheet(
        onDismiss = onDismiss,
        title = {
            PlaylistPickerHeader(
                title = str(R.string.add_to_playlist),
                subtitle = "${track.title} • ${track.artist}",
                artworkUrl = track.thumbnail
            )
        }
    ) {
        PlaylistPickerList(
            playlists = userPlaylists,
            isInPlaylist = { playlist -> recentlyAddedId == playlist.id || playlist.tracks.any { it.id == track.id } },
            onCreate = { showCreateDialog = true },
            onPick = { playlist ->
                if (playlist.tracks.any { it.id == track.id }) {
                    com.auralis.music.ui.components.AppPillManager.showPill(str(R.string.already_in_x, playlist.title))
                } else {
                    recentlyAddedId = playlist.id
                    onAddToPlaylist(playlist)
                }
            }
        )
    }

    // ── CREATE PLAYLIST DIALOG ──
    val playlistCreator = com.auralis.music.ui.library.LocalPlaylistCreation.current
    if (showCreateDialog && playlistCreator != null) {
        LaunchedEffect(Unit) {
            showCreateDialog = false
            playlistCreator(com.auralis.music.ui.library.PlaylistCreationRequest(listOf(track))); onDismiss()
        }
    } else if (showCreateDialog) {
        com.auralis.music.ui.components.AuralisAlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(str(R.string.new_playlist), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text(str(R.string.playlist_name)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onCreatePlaylistAndAdd(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text(str(R.string.create_add), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(str(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}
