package com.auralis.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.theme.AuralisPrimary
import com.auralis.music.ui.theme.AuralisSurfaceElevated
import com.auralis.music.ui.theme.GlassBorderHairline
import com.auralis.music.ui.viewmodel.LibraryFilter
import com.auralis.music.ui.viewmodel.LibraryUiState
import com.auralis.music.ui.viewmodel.SmartCollectionType

val CREAM_ICON_COLOR = Color(0xFFDCE2BD)
val CARD_DARK_BG = Color(0xFF1B1D16)
val LIME_TEXT = Color(0xFFD4E157)

/**
 * Pure Jetpack Compose Library Screen with top App Bar (Library title, History, Listen Together, Profile),
 * "Date added ↓" sorting bar, Grid vs List toggle, Search filtering, 2-column grid or single-column list of smart collection cards
 * (Liked, Downloaded, Cached, My Top 50, Uploaded) + user & synced playlists with 4-cover collages,
 * and bottom right floating '+' button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    currentTrackId: String?,
    isPlaying: Boolean,
    onFilterSelect: (LibraryFilter) -> Unit = {},
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onPlaylistSelect: (Playlist?) -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onRemoveFromPlaylist: (String, String) -> Unit = { _, _ -> },
    onImportYouTubePlaylist: (String) -> Unit = {},
    onImportSpotifyPlaylist: (String) -> Unit = {},
    onExportBackup: suspend () -> String = { "" },
    onImportBackup: (String) -> Unit = {},
    onSmartCollectionClick: (SmartCollectionType) -> Unit = {},
    onSortChange: (String) -> Unit = {},
    onToggleGridView: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenListenTogether: () -> Unit = {},
    onSyncPlaylist: (Playlist) -> Unit = {},
    onEditPlaylist: (String, String, String?, String?) -> Unit = { _, _, _, _ -> },
    onAddToQueue: (List<Track>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isGridView by remember { mutableStateOf(uiState.isGridView) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showYouTubeImportDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    val displayedPlaylists = if (searchQuery.isBlank()) {
        uiState.playlists
    } else {
        uiState.playlists.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    androidx.activity.compose.BackHandler(
        enabled = isSearchActive || showSortMenu || showCreateDialog || showYouTubeImportDialog
    ) {
        if (showYouTubeImportDialog) showYouTubeImportDialog = false
        else if (showCreateDialog) showCreateDialog = false
        else if (showSortMenu) showSortMenu = false
        else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        }
    }

    // If a playlist or smart collection is selected, display detail view
    if (uiState.selectedPlaylist != null) {
        val selectedPl = uiState.selectedPlaylist
        PlaylistDetailView(
            playlist = selectedPl,
            currentTrackId = currentTrackId,
            isPlaying = isPlaying,
            onBack = { onPlaylistSelect(null) },
            onPlayTrack = { track, list -> onTrackClick(track, list) },
            onRemoveTrack = { trackId -> onRemoveFromPlaylist(selectedPl.id, trackId) },
            onDeletePlaylist = {
                onDeletePlaylist(selectedPl.id)
                onPlaylistSelect(null)
            },
            onSyncPlaylist = onSyncPlaylist,
            onEditPlaylist = onEditPlaylist,
            onAddToQueue = onAddToQueue,
            onMenuClick = { track -> selectedTrackForMenu = track }
        )

        // Render Track Options Menu for playlist tracks
        if (selectedTrackForMenu != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedTrackForMenu = null },
                containerColor = CARD_DARK_BG
            ) {
                TrackOptionsMenu(
                    track = selectedTrackForMenu!!,
                    isFavorite = false,
                    userPlaylists = uiState.playlists,
                    onToggleFavorite = { onFavoriteToggle(selectedTrackForMenu!!) },
                    onPlayNext = {
                        onAddToQueue?.invoke(listOf(selectedTrackForMenu!!))
                        selectedTrackForMenu = null
                    },
                    onAddToQueue = {
                        onAddToQueue?.invoke(listOf(selectedTrackForMenu!!))
                        selectedTrackForMenu = null
                    },
                    onAddToPlaylist = { playlist ->
                        onAddToPlaylist(playlist.id, selectedTrackForMenu!!)
                        selectedTrackForMenu = null
                    },
                    onCreatePlaylistAndAdd = { title ->
                        onCreatePlaylist(title)
                        selectedTrackForMenu = null
                    },
                    onDismiss = { selectedTrackForMenu = null }
                )
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E0F0C))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // ================================================================
            // 1. TOP APP BAR: "Library" Title + 3 Action Icons
            // ================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 24.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "History", tint = Color.White.copy(alpha = 0.85f))
                    }
                    IconButton(onClick = onOpenListenTogether) {
                        Icon(Icons.Default.Groups, contentDescription = "Listen Together", tint = Color.White.copy(alpha = 0.85f))
                    }
                    IconButton(onClick = onOpenProfile) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Profile", tint = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            // ================================================================
            // 2. SORTING & CONTROLS BAR ("Date added ↓", Search & Grid/List Toggle)
            // ================================================================
            if (isSearchActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search library...", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = LIME_TEXT, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            IconButton(onClick = {
                                if (searchQuery.isNotEmpty()) {
                                    searchQuery = ""
                                } else {
                                    isSearchActive = false
                                }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = CARD_DARK_BG,
                            unfocusedContainerColor = CARD_DARK_BG,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = LIME_TEXT,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sorting Selector
                    Row(
                        modifier = Modifier
                            .clickable { showSortMenu = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.sortOrder} ↓",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = LIME_TEXT,
                            fontSize = 15.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search Library",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = {
                            isGridView = !isGridView
                            onToggleGridView()
                        }) {
                            Icon(
                                imageVector = if (isGridView) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
                                contentDescription = if (isGridView) "Switch to List View" else "Switch to Grid View",
                                tint = if (isGridView) Color.White else LIME_TEXT,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ================================================================
            // 3. LIBRARY CONTENT (2-COLUMN GRID OR 1-COLUMN LIST)
            // ================================================================
            if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (searchQuery.isBlank()) {
                        // Smart Collection Card 1: Liked
                        item {
                            SmartLibraryCard(
                                title = "Liked",
                                icon = Icons.Default.FavoriteBorder,
                                onClick = { onSmartCollectionClick(SmartCollectionType.LIKED) }
                            )
                        }

                        // Smart Collection Card 2: Downloaded
                        item {
                            SmartLibraryCard(
                                title = "Downloaded",
                                icon = Icons.Default.DownloadDone,
                                onClick = { onSmartCollectionClick(SmartCollectionType.DOWNLOADED) }
                            )
                        }

                        // Smart Collection Card 3: Cached
                        item {
                            SmartLibraryCard(
                                title = "Cached",
                                icon = Icons.Default.Autorenew,
                                onClick = { onSmartCollectionClick(SmartCollectionType.CACHED) }
                            )
                        }

                        // Smart Collection Card 4: My Top 50
                        item {
                            SmartLibraryCard(
                                title = "My Top 50",
                                icon = Icons.Default.Leaderboard,
                                onClick = { onSmartCollectionClick(SmartCollectionType.MY_TOP_50) }
                            )
                        }

                        // Smart Collection Card 5: Uploaded
                        item {
                            SmartLibraryCard(
                                title = "Uploaded",
                                icon = Icons.Default.CloudUpload,
                                onClick = { onSmartCollectionClick(SmartCollectionType.UPLOADED) }
                            )
                        }
                    }

                    // User & Synced Playlists
                    items(displayedPlaylists, key = { it.id }) { playlist ->
                        UserPlaylistGridCard(
                            playlist = playlist,
                            onClick = { onPlaylistSelect(playlist) }
                        )
                    }
                }
            } else {
                // ============================================================
                // 📋 1-COLUMN LIST VIEW
                // ============================================================
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (searchQuery.isBlank()) {
                        item {
                            SmartLibraryListRow(
                                title = "Liked",
                                subtitle = "${uiState.favorites.size} songs",
                                icon = Icons.Default.FavoriteBorder,
                                onClick = { onSmartCollectionClick(SmartCollectionType.LIKED) }
                            )
                        }
                        item {
                            SmartLibraryListRow(
                                title = "Downloaded",
                                subtitle = "Offline storage",
                                icon = Icons.Default.DownloadDone,
                                onClick = { onSmartCollectionClick(SmartCollectionType.DOWNLOADED) }
                            )
                        }
                        item {
                            SmartLibraryListRow(
                                title = "Cached",
                                subtitle = "${uiState.cachedTracks.size} cached tracks",
                                icon = Icons.Default.Autorenew,
                                onClick = { onSmartCollectionClick(SmartCollectionType.CACHED) }
                            )
                        }
                        item {
                            SmartLibraryListRow(
                                title = "My Top 50",
                                subtitle = "Most listened tracks",
                                icon = Icons.Default.Leaderboard,
                                onClick = { onSmartCollectionClick(SmartCollectionType.MY_TOP_50) }
                            )
                        }
                        item {
                            SmartLibraryListRow(
                                title = "Uploaded",
                                subtitle = "Local audio files",
                                icon = Icons.Default.CloudUpload,
                                onClick = { onSmartCollectionClick(SmartCollectionType.UPLOADED) }
                            )
                        }
                    }

                    items(displayedPlaylists, key = { it.id }) { playlist ->
                        UserPlaylistListRow(
                            playlist = playlist,
                            onClick = { onPlaylistSelect(playlist) }
                        )
                    }
                }
            }
        }

        // ====================================================================
        // 4. FLOATING ACTION BUTTON '+' (BOTTOM RIGHT)
        // ====================================================================
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 28.dp)
                .size(62.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF535D31))
                .clickable { showCreateDialog = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New Playlist / Import",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }

    // Sort Options Dialog
    if (showSortMenu) {
        val sortOptions = listOf("Date added", "Recently played", "Alphabetical (A to Z)", "Alphabetical (Z to A)", "Track count")
        AlertDialog(
            onDismissRequest = { showSortMenu = false },
            title = { Text("Sort Playlists By", fontWeight = FontWeight.Bold, color = Color.White) },
            containerColor = CARD_DARK_BG,
            text = {
                Column {
                    sortOptions.forEach { opt ->
                        val isSelected = uiState.sortOrder == opt
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onSortChange(opt)
                                    showSortMenu = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = opt,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) LIME_TEXT else Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = LIME_TEXT, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSortMenu = false }) { Text("Cancel", color = Color.White.copy(alpha = 0.8f)) }
            }
        )
    }

    // Create / Import Options Dialog
    if (showCreateDialog) {
        var playlistInput by remember { mutableStateOf("") }
        var importMode by remember { mutableStateOf(0) } // 0: Custom, 1: YouTube, 2: Spotify

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = CARD_DARK_BG,
            title = {
                Text(
                    text = when (importMode) {
                        1 -> "Import YouTube Playlist"
                        2 -> "Import Spotify Playlist"
                        else -> "New Playlist"
                    },
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column {
                    // Mode Selector Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Custom Mode Chip
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (importMode == 0) LIME_TEXT else Color.Transparent)
                                .clickable { importMode = 0 },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Custom",
                                fontWeight = FontWeight.Bold,
                                color = if (importMode == 0) Color.Black else Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }

                        // YouTube Mode Chip
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (importMode == 1) Color(0xFFEF4444) else Color.Transparent)
                                .clickable { importMode = 1 },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "YouTube",
                                fontWeight = FontWeight.Bold,
                                color = if (importMode == 1) Color.White else Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }

                        // Spotify Mode Chip
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (importMode == 2) Color(0xFF1DB954) else Color.Transparent)
                                .clickable { importMode = 2 },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Spotify",
                                fontWeight = FontWeight.Bold,
                                color = if (importMode == 2) Color.Black else Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = playlistInput,
                        onValueChange = { playlistInput = it },
                        placeholder = {
                            Text(
                                when (importMode) {
                                    1 -> "Paste YouTube playlist link"
                                    2 -> "Paste Spotify playlist / album link"
                                    else -> "Playlist title"
                                },
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 13.sp
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = when (importMode) {
                                1 -> Color(0xFFEF4444)
                                2 -> Color(0xFF1DB954)
                                else -> LIME_TEXT
                            },
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (playlistInput.isNotBlank()) {
                            when (importMode) {
                                1 -> onImportYouTubePlaylist(playlistInput.trim())
                                2 -> onImportSpotifyPlaylist(playlistInput.trim())
                                else -> onCreatePlaylist(playlistInput.trim())
                            }
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (importMode) {
                            1 -> Color(0xFFEF4444)
                            2 -> Color(0xFF1DB954)
                            else -> LIME_TEXT
                        }
                    )
                ) {
                    Text(
                        text = when (importMode) {
                            1 -> "Import YouTube"
                            2 -> "Import Spotify"
                            else -> "Create"
                        },
                        color = if (importMode == 1) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}

// ============================================================================
// 🔲 SMART LIBRARY CARD (Liked, Downloaded, Cached, My Top 50, Uploaded)
// ============================================================================

@Composable
private fun SmartLibraryCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(CARD_DARK_BG),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = CREAM_ICON_COLOR,
                modifier = Modifier.size(54.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 15.sp
        )
    }
}

// ============================================================================
// 🎨 USER PLAYLIST GRID CARD (With 4-Cover Collage or Single Artwork)
// ============================================================================

@Composable
private fun UserPlaylistGridCard(
    playlist: Playlist,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(CARD_DARK_BG)
        ) {
            // If custom cover is set, display it; otherwise show 4-quadrant collage or single artwork
            if (!playlist.coverUrl.isNullOrBlank()) {
                ArtworkCard(
                    url = playlist.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 18.dp,
                    contentDescription = playlist.title
                )
            } else if (playlist.tracks.size >= 4) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArtworkCard(
                            url = playlist.tracks[0].thumbnail,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                        ArtworkCard(
                            url = playlist.tracks[1].thumbnail,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArtworkCard(
                            url = playlist.tracks[2].thumbnail,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                        ArtworkCard(
                            url = playlist.tracks[3].thumbnail,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                    }
                }
            } else {
                ArtworkCard(
                    url = playlist.tracks.firstOrNull()?.thumbnail,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 18.dp,
                    contentDescription = playlist.title
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = playlist.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "${playlist.tracks.size} songs",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp
        )
    }
}

// ============================================================================
// 📋 SMART LIBRARY LIST ROW (For List View Mode)
// ============================================================================

@Composable
private fun SmartLibraryListRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CARD_DARK_BG)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF25281E)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = CREAM_ICON_COLOR,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}

// ============================================================================
// 🎨 USER PLAYLIST LIST ROW (For List View Mode)
// ============================================================================

@Composable
private fun UserPlaylistListRow(
    playlist: Playlist,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CARD_DARK_BG)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkCard(
            url = playlist.coverUrl ?: playlist.tracks.firstOrNull()?.thumbnail,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
            cornerRadius = 10.dp,
            contentDescription = playlist.title
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${playlist.tracks.size} songs",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}

// ============================================================================
// 📑 PLAYLIST DETAIL VIEW (Matching Photos 2, 3, and 4)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDetailView(
    playlist: Playlist,
    currentTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onDeletePlaylist: () -> Unit,
    onSyncPlaylist: ((Playlist) -> Unit)? = null,
    onEditPlaylist: ((String, String, String?, String?) -> Unit)? = null,
    onAddToQueue: ((List<Track>) -> Unit)? = null,
    onMenuClick: (Track) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editTitle by remember(playlist.title) { mutableStateOf(playlist.title) }
    var editDesc by remember(playlist.description) { mutableStateOf(playlist.description ?: "") }
    var editCoverUrl by remember(playlist.coverUrl) { mutableStateOf(playlist.coverUrl ?: "") }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            editCoverUrl = uri.toString()
        }
    }
    var selectedExportFormat by remember { mutableStateOf("CSV") }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val displayedTracks = (if (searchQuery.isBlank()) {
        playlist.tracks
    } else {
        playlist.tracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }).filter { !it.title.startsWith("Track ") && it.title.isNotBlank() }

    val totalSeconds = playlist.tracks.map { it.duration }.sum()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val durationFormatted = if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (showEditDialog) {
            showEditDialog = false
        } else if (showExportDialog) {
            showExportDialog = false
        } else if (showOptionsMenu) {
            showOptionsMenu = false
        } else if (showDeleteConfirm) {
            showDeleteConfirm = false
        } else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else {
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0F0C))
    ) {
        // ================================================================
        // 1. TOP APP BAR: Back Arrow (Left) + Search Icon (Right)
        // ================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = { isSearchActive = !isSearchActive }) {
                Icon(
                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (isSearchActive) LIME_TEXT else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        if (isSearchActive) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search in playlist...", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CARD_DARK_BG,
                    unfocusedContainerColor = CARD_DARK_BG,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = LIME_TEXT,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(50.dp)
            )
        }

        // ================================================================
        // 2. MAIN SCROLLABLE BODY
        // ================================================================
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Header Content
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Centered Hero Cover (210dp x 210dp) with Custom Cover or 4-Quadrant Collage
                    Box(
                        modifier = Modifier
                            .size(210.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(CARD_DARK_BG)
                    ) {
                        if (!playlist.coverUrl.isNullOrBlank()) {
                            ArtworkCard(
                                url = playlist.coverUrl,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 18.dp,
                                contentDescription = playlist.title
                            )
                        } else if (playlist.tracks.size >= 4) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    ArtworkCard(
                                        url = playlist.tracks[0].thumbnail,
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                        cornerRadius = 0.dp,
                                        contentDescription = null
                                    )
                                    ArtworkCard(
                                        url = playlist.tracks[1].thumbnail,
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                        cornerRadius = 0.dp,
                                        contentDescription = null
                                    )
                                }
                                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    ArtworkCard(
                                        url = playlist.tracks[2].thumbnail,
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                        cornerRadius = 0.dp,
                                        contentDescription = null
                                    )
                                    ArtworkCard(
                                        url = playlist.tracks[3].thumbnail,
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                        cornerRadius = 0.dp,
                                        contentDescription = null
                                    )
                                }
                            }
                        } else {
                            ArtworkCard(
                                url = playlist.tracks.firstOrNull()?.thumbnail,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 18.dp,
                                contentDescription = playlist.title
                            )
                        }

                        // Edit Pencil Overlay in bottom right corner (Opens Edit Photo & Name dialog)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.75f))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                .clickable { showEditDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Playlist Photo and Name",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Title
                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 24.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Tracks Count & Total Duration
                    Text(
                        text = "${playlist.tracks.size} songs $durationFormatted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Author Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2E3324)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = LIME_TEXT,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Shreyanshh",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp
                        )
                    }

                    // Description text (if any)
                    if (!playlist.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = playlist.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ========================================================
                    // 3. ACTION BUTTONS ROW (Shuffle, Big Play, 3-Dots)
                    // ========================================================
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle Button
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22251B))
                                .clickable {
                                    if (playlist.tracks.isNotEmpty()) {
                                        val shuffled = playlist.tracks.shuffled()
                                        onPlayTrack(shuffled.first(), shuffled)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(20.dp))

                        // Large Play Button
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFDCE7A1))
                                .clickable {
                                    if (playlist.tracks.isNotEmpty()) {
                                        onPlayTrack(playlist.tracks.first(), playlist.tracks)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(20.dp))

                        // 3-Dots Options Button
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22251B))
                                .clickable { showOptionsMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Playlist Options",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Custom Order Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Custom order",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = LIME_TEXT,
                            fontSize = 14.sp
                        )
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // ================================================================
            // 4. TRACKS LIST (Matching Photo 2)
            // ================================================================
            itemsIndexed(displayedTracks, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                val isCurrent = track.id == currentTrackId
                val trackMin = track.duration / 60
                val trackSec = track.duration % 60
                val trackDurationStr = String.format("%d:%02d", trackMin, trackSec)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlayTrack(track, playlist.tracks) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(
                        url = track.thumbnail,
                        modifier = Modifier.size(48.dp),
                        cornerRadius = 8.dp,
                        contentDescription = track.title
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrent) LIME_TEXT else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val subtitleText = if (track.duration > 0 && track.duration != 210L) {
                                "${track.artist} • $trackDurationStr"
                            } else {
                                track.artist
                            }
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isCurrent) {
                        EqualizerBars(
                            isPlaying = isPlaying,
                            modifier = Modifier.size(18.dp),
                            color = LIME_TEXT
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    IconButton(onClick = { onMenuClick(track) }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // ========================================================================
    // 5. PLAYLIST OPTIONS BOTTOM SHEET (Matching Photo 3)
    // ========================================================================
    if (showOptionsMenu) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsMenu = false },
            containerColor = Color(0xFF141610),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Option 1: Edit
                PlaylistActionRow(
                    icon = Icons.Default.Edit,
                    title = "Edit",
                    subtitle = "Edit playlist",
                    onClick = {
                        showOptionsMenu = false
                        editTitle = playlist.title
                        editDesc = playlist.description ?: ""
                        showEditDialog = true
                    }
                )

                // Option 2: Sync
                PlaylistActionRow(
                    icon = Icons.Default.Autorenew,
                    title = "Sync",
                    subtitle = "Sync this playlist with YouTube Music",
                    onClick = {
                        showOptionsMenu = false
                        Toast.makeText(context, "Syncing '${playlist.title}' with YouTube Music...", Toast.LENGTH_SHORT).show()
                        onSyncPlaylist?.invoke(playlist)
                    }
                )

                // Option 3: Add to queue
                PlaylistActionRow(
                    icon = Icons.Default.PlaylistAdd,
                    title = "Add to queue",
                    subtitle = "Add to the end of the queue",
                    onClick = {
                        showOptionsMenu = false
                        onAddToQueue?.invoke(playlist.tracks)
                        Toast.makeText(context, "Added ${playlist.tracks.size} tracks to queue", Toast.LENGTH_SHORT).show()
                    }
                )

                // Option 4: Download
                PlaylistActionRow(
                    icon = Icons.Default.DownloadDone,
                    title = "Download",
                    subtitle = "Download all songs for offline playback",
                    onClick = {
                        showOptionsMenu = false
                        Toast.makeText(context, "Downloading playlist for offline playback...", Toast.LENGTH_SHORT).show()
                    }
                )

                // Option 5: Share
                PlaylistActionRow(
                    icon = Icons.Default.Share,
                    title = "Share",
                    subtitle = "Share this playlist with others",
                    onClick = {
                        showOptionsMenu = false
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, playlist.title)
                            putExtra(Intent.EXTRA_TEXT, "Listen to '${playlist.title}' on Auralis Music: ${playlist.tracks.size} songs")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Playlist"))
                    }
                )

                // Option 6: Export playlist
                PlaylistActionRow(
                    icon = Icons.Default.Share,
                    title = "Export playlist",
                    subtitle = null,
                    onClick = {
                        showOptionsMenu = false
                        showExportDialog = true
                    }
                )

                // Option 7: Delete
                PlaylistActionRow(
                    icon = Icons.Default.Delete,
                    title = "Delete",
                    subtitle = "Remove this playlist permanently",
                    onClick = {
                        showOptionsMenu = false
                        showDeleteConfirm = true
                    }
                )
            }
        }
    }

    // ========================================================================
    // 5. EDIT PLAYLIST DIALOG (Photo, Name, Description)
    // ========================================================================
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = Color(0xFF1E2117),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Edit Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 20.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Cover Photo Preview with Tap-to-Change badge
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF13150F))
                            .border(1.5.dp, if (editCoverUrl.isNotBlank()) LIME_TEXT else Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                            .clickable { photoPickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        val previewUrl = editCoverUrl.ifBlank { playlist.tracks.firstOrNull()?.thumbnail }
                        ArtworkCard(
                            url = previewUrl,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 16.dp,
                            contentDescription = "Playlist Cover Preview"
                        )

                        // Camera overlay badge
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.40f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = "Change photo",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Change Photo",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Action buttons for Photo: Choose Photo or Reset
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { photoPickerLauncher.launch("image/*") }
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp), tint = LIME_TEXT)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Choose Photo", color = LIME_TEXT, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        if (editCoverUrl.isNotBlank()) {
                            TextButton(
                                onClick = { editCoverUrl = "" }
                            ) {
                                Text("Reset Photo", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            }
                        }
                    }

                    // Playlist Name TextField
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Playlist Name", color = Color.White.copy(alpha = 0.6f)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF13150F),
                            unfocusedContainerColor = Color(0xFF13150F),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = LIME_TEXT,
                            unfocusedIndicatorColor = Color.White.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Playlist Description TextField
                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text("Description (optional)", color = Color.White.copy(alpha = 0.6f)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF13150F),
                            unfocusedContainerColor = Color(0xFF13150F),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = LIME_TEXT,
                            unfocusedIndicatorColor = Color.White.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEditDialog = false
                        if (editTitle.isNotBlank()) {
                            onEditPlaylist?.invoke(playlist.id, editTitle, editDesc, editCoverUrl.ifBlank { null })
                            Toast.makeText(context, "Playlist updated", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LIME_TEXT),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }

    // ========================================================================
    // 6. EXPORT PLAYLIST DIALOG (Matching Photo 4)
    // ========================================================================
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = Color(0xFF1E2117),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Export playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 20.sp
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    // Radio Button 1: Export as CSV
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedExportFormat = "CSV" }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selectedExportFormat == "CSV") Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selectedExportFormat == "CSV") LIME_TEXT else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Export as CSV",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }

                    // Radio Button 2: Export as M3U
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedExportFormat = "M3U" }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selectedExportFormat == "M3U") Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selectedExportFormat == "M3U") LIME_TEXT else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Export as M3U",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("Cancel", color = LIME_TEXT, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = {
                        showExportDialog = false
                        val cleanTitle = playlist.title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        val fileName = if (selectedExportFormat == "CSV") "$cleanTitle.csv" else "$cleanTitle.m3u"
                        val content = if (selectedExportFormat == "CSV") {
                            buildString {
                                appendLine("Title,Artist,Album,Duration")
                                playlist.tracks.forEach { t ->
                                    val albumStr = (t.album ?: "").replace("\"", "\"\"")
                                    appendLine("\"${t.title.replace("\"", "\"\"")}\",\"${t.artist.replace("\"", "\"\"")}\",\"$albumStr\",${t.duration}")
                                }
                            }
                        } else {
                            buildString {
                                appendLine("#EXTM3U")
                                appendLine("#PLAYLIST:${playlist.title}")
                                playlist.tracks.forEach { t ->
                                    appendLine("#EXTINF:${t.duration},${t.artist} - ${t.title}")
                                    appendLine("https://music.youtube.com/watch?v=${t.id}")
                                }
                            }
                        }

                        try {
                            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            val file = java.io.File(downloadsDir, fileName)
                            file.writeText(content)
                            Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            try {
                                val file = java.io.File(context.getExternalFilesDir(null), fileName)
                                file.writeText(content)
                                Toast.makeText(context, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                            } catch (e2: Exception) {
                                Toast.makeText(context, "Error saving: ${e2.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text("Save to Documents", color = LIME_TEXT, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = {
                        showExportDialog = false
                        val content = if (selectedExportFormat == "CSV") {
                            buildString {
                                appendLine("Title,Artist,Album,Duration")
                                playlist.tracks.forEach { t ->
                                    val albumStr = (t.album ?: "").replace("\"", "\"\"")
                                    appendLine("\"${t.title.replace("\"", "\"\"")}\",\"${t.artist.replace("\"", "\"\"")}\",\"$albumStr\",${t.duration}")
                                }
                            }
                        } else {
                            buildString {
                                appendLine("#EXTM3U")
                                appendLine("#PLAYLIST:${playlist.title}")
                                playlist.tracks.forEach { t ->
                                    appendLine("#EXTINF:${t.duration},${t.artist} - ${t.title}")
                                    appendLine("https://music.youtube.com/watch?v=${t.id}")
                                }
                            }
                        }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Playlist Export: ${playlist.title}")
                            putExtra(Intent.EXTRA_TEXT, content)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Playlist Export"))
                    }) {
                        Text("Share", color = LIME_TEXT, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {}
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Playlist", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete '${playlist.title}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeletePlaylist()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ============================================================================
// 🔘 PLAYLIST ACTION ROW (For Playlist Options Bottom Sheet)
// ============================================================================

@Composable
private fun PlaylistActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B1E15))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                fontSize = 16.sp
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp
                )
            }
        }
    }
}
