package com.auralis.music.ui.library

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.theme.auralisDetailBackwardEnter
import com.auralis.music.ui.theme.auralisDetailBackwardExit
import com.auralis.music.ui.theme.auralisDetailForwardEnter
import com.auralis.music.ui.theme.auralisDetailForwardExit
import com.auralis.music.ui.theme.auralisNavigationEnter
import com.auralis.music.ui.theme.auralisNavigationExit
import com.auralis.music.ui.theme.motionTween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.isActive
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.AuralisPrimary
import com.auralis.music.ui.theme.AuralisSurfaceElevated
import com.auralis.music.ui.theme.GlassBorderHairline
import com.auralis.music.ui.viewmodel.LibraryFilter
import com.auralis.music.ui.viewmodel.LibraryUiState
import com.auralis.music.ui.viewmodel.SmartCollectionType

val CREAM_ICON_COLOR: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
val CARD_DARK_BG: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val LIME_TEXT: Color @Composable get() = MaterialTheme.colorScheme.primary

enum class PlaylistSortOption(val label: String) {
    CUSTOM("Custom order"),
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    ALPHABETICAL("Alphabetical (A-Z)"),
    BY_ARTIST("Artist (A-Z)")
}

/**
 * Pure Jetpack Compose Library Screen with top App Bar (Library title, History, Listen Together, Profile),
 * "Date added ↓" sorting bar, Grid vs List toggle, Search filtering, 2-column grid or single-column list of Liked default playlist
 * + user & synced playlists with 4-cover collages, and bottom right floating '+' button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    currentTrackId: String?,
    isPlaying: Boolean,
    userName: String = "You",
    userAvatarUrl: String? = null,
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
    onPlayNext: (Track) -> Unit = {},
    onAddToQueueTrack: (Track) -> Unit = {},
    onStartRadio: (Track) -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {},
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    onReorderPlaylistTracks: ((String, Int, Int) -> Unit)? = null,
    isExternalCreateDialogOpen: Boolean = false,
    onCloseExternalCreateDialog: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isGridView by remember { mutableStateOf(uiState.isGridView) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isExternalCreateDialogOpen) {
        if (isExternalCreateDialogOpen) {
            showCreateDialog = true
            onCloseExternalCreateDialog()
        }
    }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    val displayedPlaylists = if (searchQuery.isBlank()) {
        uiState.playlists
    } else {
        uiState.playlists.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    androidx.activity.compose.BackHandler(
        enabled = uiState.selectedPlaylist != null || isSearchActive || showSortMenu || showCreateDialog
    ) {
        if (showCreateDialog) showCreateDialog = false
        else if (showSortMenu) showSortMenu = false
        else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (uiState.selectedPlaylist != null) {
            onPlaylistSelect(null)
        }
    }

    val detailForwardEnter = auralisDetailForwardEnter()
    val detailForwardExit = auralisDetailForwardExit()
    val detailBackwardEnter = auralisDetailBackwardEnter()
    val detailBackwardExit = auralisDetailBackwardExit()

    AnimatedContent(
        targetState = uiState.selectedPlaylist?.id,
        transitionSpec = {
            if (targetState != null && initialState == null) {
                detailForwardEnter togetherWith detailForwardExit
            } else if (targetState == null && initialState != null) {
                detailBackwardEnter togetherWith detailBackwardExit
            } else {
                androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            }
        },
        label = "PlaylistDetailTransition"
    ) { _ ->
        val selectedPl = uiState.selectedPlaylist
        if (selectedPl != null) {
            PlaylistDetailView(
                playlist = selectedPl,
                currentTrackId = currentTrackId,
                isPlaying = isPlaying,
                userName = userName,
                userAvatarUrl = userAvatarUrl,
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
                onPlayNextTrack = onPlayNext,
                onAddToQueueTrack = onAddToQueueTrack,
                onReorderTracks = { from, to ->
                    onReorderPlaylistTracks?.invoke(selectedPl.id, from, to)
                },
                onMenuClick = { track -> selectedTrackForMenu = track }
            )

            // Render Track Options Menu for playlist tracks
            selectedTrackForMenu?.let { track ->
                val isFav = uiState.favorites.any { it.id == track.id }
                val trackIdx = selectedPl.tracks.indexOfFirst { it.id == track.id }
                val isCustomPl = !selectedPl.id.startsWith("smart_")

                TrackOptionsMenu(
                    track = track,
                    isFavorite = isFav,
                    userPlaylists = uiState.playlists,
                    onToggleFavorite = { onFavoriteToggle(track) },
                    onPlayNext = {
                        onPlayNext(track)
                        selectedTrackForMenu = null
                    },
                    onAddToQueue = {
                        onAddToQueueTrack(track)
                        selectedTrackForMenu = null
                    },
                    onStartRadio = {
                        onStartRadio(track)
                        selectedTrackForMenu = null
                    },
                    onGoToArtist = {
                        onOpenArtist(Artist(id = "", name = track.artist))
                        selectedTrackForMenu = null
                    },
                    onAddToPlaylist = { playlist ->
                        onAddToPlaylist(playlist.id, track)
                        selectedTrackForMenu = null
                    },
                    onCreatePlaylistAndAdd = { title ->
                        onCreatePlaylist(title)
                        selectedTrackForMenu = null
                    },
                    isInListenTogetherRoom = isInListenTogetherRoom,
                    onRecommendToRoom = { trk ->
                        onRecommendToRoom?.invoke(trk)
                        selectedTrackForMenu = null
                    },
                    onDismiss = { selectedTrackForMenu = null }
                )
            }
        } else {

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 24.sp
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenHistory,
                        modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                    ) {
                        Icon(Icons.Default.History, contentDescription = "History", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
                    }
                    IconButton(
                        onClick = onOpenListenTogether,
                        modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                    ) {
                        Icon(Icons.Default.Groups, contentDescription = "Listen Together", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
                    }
                    IconButton(
                        onClick = onOpenProfile,
                        modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Profile", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
                    }
                }
            }

            // ================================================================
            // 2. SORTING & CONTROLS BAR ("Date added ↓", Search & Grid/List Toggle)
            // ================================================================
            if (isSearchActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CARD_DARK_BG)
                        .border(1.dp, LIME_TEXT.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = LIME_TEXT,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search library...",
                                    style = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = TextStyle(
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = SolidColor(LIME_TEXT),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        IconButton(
                            onClick = {
                                if (searchQuery.isNotEmpty()) {
                                    searchQuery = ""
                                } else {
                                    isSearchActive = false
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
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
                                tint = MaterialTheme.colorScheme.onBackground,
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
                                tint = if (isGridView) MaterialTheme.colorScheme.onBackground else LIME_TEXT,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
            val gridAnimate = !LocalReducedMotion.current
            val gridFadeEnter = fadeIn(motionTween(AuralisDuration.Quick, AuralisEasing.Standard))
            val gridFadeExit = fadeOut(motionTween(AuralisDuration.Fast, AuralisEasing.Standard))
            
            val minGridSize = when (appearance.gridCellSize) {
                "Small" -> 135.dp
                "Large" -> 195.dp
                else -> 160.dp
            }

            val libraryBottomPad = if (currentTrackId != null) 180.dp else 100.dp

            AnimatedContent(
                targetState = isGridView,
                transitionSpec = {
                    gridFadeEnter togetherWith gridFadeExit using SizeTransform(clip = false)
                },
                modifier = Modifier.fillMaxSize(),
                label = "libraryLayoutMode"
            ) { gridMode ->
                if (gridMode) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = minGridSize),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = libraryBottomPad),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (searchQuery.isBlank()) {
                            // 1. Liked Playlist
                            if (appearance.showLikedPlaylist) {
                                item {
                                    SmartLibraryCard(
                                        title = "Liked",
                                        subtitle = "${uiState.favorites.size} songs",
                                        icon = Icons.Default.FavoriteBorder,
                                        tracks = uiState.favorites,
                                        onClick = { onSmartCollectionClick(SmartCollectionType.LIKED) }
                                    )
                                }
                            }

                            // 2. Downloaded Playlist (Auto appears when enabled in Appearances and downloaded songs exist)
                            if (appearance.showDownloadedPlaylist && uiState.downloadedTracks.isNotEmpty()) {
                                item {
                                    SmartLibraryCard(
                                        title = "Downloaded",
                                        subtitle = "${uiState.downloadedTracks.size} songs",
                                        icon = Icons.Default.DownloadDone,
                                        tracks = uiState.downloadedTracks,
                                        onClick = { onSmartCollectionClick(SmartCollectionType.DOWNLOADED) }
                                    )
                                }
                            }

                            // 3. Top Most Played Playlist
                            if (appearance.showTopPlaylist && uiState.top50Tracks.isNotEmpty()) {
                                item {
                                    SmartLibraryCard(
                                        title = "Top Most Played",
                                        subtitle = "${uiState.top50Tracks.size} songs",
                                        icon = Icons.Default.Leaderboard,
                                        tracks = uiState.top50Tracks,
                                        onClick = { onSmartCollectionClick(SmartCollectionType.MY_TOP_50) }
                                    )
                                }
                            }

                            // 4. Cached Playlist
                            if (appearance.showCachedPlaylist && uiState.cachedTracks.isNotEmpty()) {
                                item {
                                    SmartLibraryCard(
                                        title = "Cached Streamed",
                                        subtitle = "${uiState.cachedTracks.size} songs",
                                        icon = Icons.Default.CloudDownload,
                                        tracks = uiState.cachedTracks,
                                        onClick = { onSmartCollectionClick(SmartCollectionType.CACHED) }
                                    )
                                }
                            }
                        }

                        // User & Synced Playlists (Imported or Created)
                        items(displayedPlaylists, key = { it.id }) { playlist ->
                            Box(modifier = if (gridAnimate) Modifier.animateItem() else Modifier) {
                                UserPlaylistGridCard(
                                    playlist = playlist,
                                    onClick = { onPlaylistSelect(playlist) }
                                )
                            }
                        }
                    }
                } else {
                    // ============================================================
                    // 📋 1-COLUMN LIST VIEW
                    // ============================================================
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = libraryBottomPad),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (searchQuery.isBlank()) {
                            if (appearance.showLikedPlaylist) {
                                item {
                                    SmartLibraryListRow(
                                        title = "Liked",
                                        subtitle = "${uiState.favorites.size} songs",
                                        icon = Icons.Default.FavoriteBorder,
                                        tracks = uiState.favorites,
                                        onClick = { onSmartCollectionClick(SmartCollectionType.LIKED) }
                                    )
                                }
                            }

                            // Downloaded Playlist (Auto appears when enabled in Appearances and downloaded songs exist)
                            if (appearance.showDownloadedPlaylist && uiState.downloadedTracks.isNotEmpty()) {
                                item {
                                    SmartLibraryListRow(
                                        title = "Downloaded",
                                        subtitle = "${uiState.downloadedTracks.size} songs",
                                        icon = Icons.Default.DownloadDone,
                                        tracks = uiState.downloadedTracks,
                                        onClick = { onSmartCollectionClick(SmartCollectionType.DOWNLOADED) }
                                    )
                                }
                            }

                            if (appearance.showTopPlaylist && uiState.top50Tracks.isNotEmpty()) {
                                item {
                                    SmartLibraryListRow(
                                        title = "Top Most Played",
                                        subtitle = "${uiState.top50Tracks.size} songs",
                                        icon = Icons.Default.Leaderboard,
                                        tracks = uiState.top50Tracks,
                                        onClick = { onSmartCollectionClick(SmartCollectionType.MY_TOP_50) }
                                    )
                                }
                            }

                            if (appearance.showCachedPlaylist && uiState.cachedTracks.isNotEmpty()) {
                                item {
                                    SmartLibraryListRow(
                                        title = "Cached Streamed",
                                        subtitle = "${uiState.cachedTracks.size} songs",
                                        icon = Icons.Default.CloudDownload,
                                        tracks = uiState.cachedTracks,
                                        onClick = { onSmartCollectionClick(SmartCollectionType.CACHED) }
                                    )
                                }
                            }
                        }

                        items(
                            items = displayedPlaylists,
                            key = { it.id },
                            contentType = { "playlist" }
                        ) { playlist ->
                            Box(modifier = if (gridAnimate) Modifier.animateItem() else Modifier) {
                                UserPlaylistListRow(
                                    playlist = playlist,
                                    onClick = { onPlaylistSelect(playlist) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Sort Options Dialog
    if (showSortMenu) {
        val sortOptions = listOf("Date added", "Recently played", "Alphabetical (A to Z)", "Alphabetical (Z to A)", "Track count")
        AlertDialog(
            onDismissRequest = { showSortMenu = false },
            title = { Text("Sort Playlists By", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) },
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
                                color = if (isSelected) LIME_TEXT else MaterialTheme.colorScheme.onBackground,
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
                TextButton(onClick = { showSortMenu = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        )
    }

    // Create New Playlist Dialog
    if (showCreateDialog) {
        var playlistInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = CARD_DARK_BG,
            title = {
                Text(
                    text = "New Playlist",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = playlistInput,
                        onValueChange = { playlistInput = it },
                        placeholder = {
                            Text(
                                "Playlist title",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LIME_TEXT,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                            focusedTextColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                            cursorColor = LIME_TEXT
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
                            onCreatePlaylist(playlistInput.trim())
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LIME_TEXT
                    )
                ) {
                    Text(
                        text = "Create",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
    }
}
}

// ============================================================================
// 🔲 SMART LIBRARY CARD (Liked, Downloaded, Cached, My Top 50, Uploaded)
// ============================================================================

@Composable
private fun SmartLibraryCard(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    tracks: List<Track> = emptyList(),
    onClick: () -> Unit
) {
    val validTracks = remember(tracks) {
        tracks.filter { !it.thumbnail.isNullOrBlank() || it.id.isNotBlank() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tactileBounce(scaleDown = 0.96f, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(CARD_DARK_BG)
        ) {
            if (validTracks.size >= 4) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArtworkCard(
                            url = validTracks[0].thumbnail,
                            fallbackTrack = validTracks[0],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                        ArtworkCard(
                            url = validTracks[1].thumbnail,
                            fallbackTrack = validTracks[1],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArtworkCard(
                            url = validTracks[2].thumbnail,
                            fallbackTrack = validTracks[2],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                        ArtworkCard(
                            url = validTracks[3].thumbnail,
                            fallbackTrack = validTracks[3],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = LIME_TEXT,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else if (validTracks.isNotEmpty()) {
                ArtworkCard(
                    url = validTracks.first().thumbnail,
                    fallbackTrack = validTracks.first(),
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 18.dp,
                    contentDescription = title
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = LIME_TEXT,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = CREAM_ICON_COLOR,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
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
    val validTracks = remember(playlist.tracks) {
        playlist.tracks.filter { !it.thumbnail.isNullOrBlank() || it.id.isNotBlank() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tactileBounce(scaleDown = 0.96f, onClick = onClick)
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
                    fallbackTrack = validTracks.firstOrNull(),
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 18.dp,
                    contentDescription = playlist.title
                )
            } else if (validTracks.size >= 4) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArtworkCard(
                            url = validTracks[0].thumbnail,
                            fallbackTrack = validTracks[0],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                        ArtworkCard(
                            url = validTracks[1].thumbnail,
                            fallbackTrack = validTracks[1],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArtworkCard(
                            url = validTracks[2].thumbnail,
                            fallbackTrack = validTracks[2],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                        ArtworkCard(
                            url = validTracks[3].thumbnail,
                            fallbackTrack = validTracks[3],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                    }
                }
            } else {
                ArtworkCard(
                    url = validTracks.firstOrNull()?.thumbnail ?: playlist.tracks.firstOrNull()?.thumbnail,
                    fallbackTrack = validTracks.firstOrNull() ?: playlist.tracks.firstOrNull(),
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
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "${playlist.tracks.size} songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    tracks: List<Track> = emptyList(),
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CARD_DARK_BG)
            .tactileBounce(scaleDown = 0.97f, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            if (tracks.isNotEmpty()) {
                ArtworkCard(
                    url = tracks.first().thumbnail,
                    fallbackTrack = tracks.first(),
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 10.dp,
                    contentDescription = title
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = LIME_TEXT,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val firstValid = remember(playlist.tracks) {
        playlist.tracks.firstOrNull { !it.thumbnail.isNullOrBlank() || it.id.isNotBlank() } ?: playlist.tracks.firstOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CARD_DARK_BG)
            .tactileBounce(scaleDown = 0.97f, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkCard(
            url = playlist.coverUrl ?: firstValid?.thumbnail,
            fallbackTrack = firstValid,
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
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${playlist.tracks.size} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    userName: String = "You",
    userAvatarUrl: String? = null,
    onBack: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onDeletePlaylist: () -> Unit,
    onSyncPlaylist: ((Playlist) -> Unit)? = null,
    onEditPlaylist: ((String, String, String?, String?) -> Unit)? = null,
    onAddToQueue: ((List<Track>) -> Unit)? = null,
    onPlayNextTrack: ((Track) -> Unit)? = null,
    onAddToQueueTrack: ((Track) -> Unit)? = null,
    onReorderTracks: ((Int, Int) -> Unit)? = null,
    onMenuClick: (Track) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editTitle by remember(playlist.title) { mutableStateOf(playlist.title) }
    var editDesc by remember(playlist.description) { mutableStateOf(playlist.description ?: "") }
    var editCoverUrl by remember(playlist.coverUrl) { mutableStateOf(playlist.coverUrl ?: "") }
    val coroutineScope = rememberCoroutineScope()
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val encoded = com.auralis.music.util.ArtworkProcessor.encodeImageUriToDataUri(context, uri.toString())
                withContext(Dispatchers.Main) {
                    editCoverUrl = encoded ?: uri.toString()
                }
            }
        }
    }
    var selectedExportFormat by remember { mutableStateOf("CSV") }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(PlaylistSortOption.CUSTOM) }
    var showSortMenu by remember { mutableStateOf(false) }

    val isCustomSort = sortOption == PlaylistSortOption.CUSTOM && searchQuery.isBlank() && !playlist.id.startsWith("smart_")
    val localTracks = remember(playlist.id) { playlist.tracks.toMutableStateList() }

    var draggingIndex by remember { mutableStateOf(-1) }
    var isDragging by remember { mutableStateOf(false) }
    var originalDragIndex by remember { mutableStateOf(-1) }
    var currentPointerY by remember { mutableStateOf(0f) }
    var grabOffsetY by remember { mutableStateOf(0f) }
    var viewportTopY by remember { mutableStateOf(0f) }
    var viewportBottomY by remember { mutableStateOf(0f) }
    var lastSwapTimeNanos by remember { mutableStateOf(0L) }
    val playlistListState = androidx.compose.runtime.saveable.rememberSaveable(
        playlist.id,
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) {
        androidx.compose.foundation.lazy.LazyListState()
    }

    LaunchedEffect(playlist.tracks) {
        if (!isDragging && draggingIndex == -1) {
            localTracks.clear()
            localTracks.addAll(playlist.tracks)
        }
    }

    fun checkTargetSwap(pointerY: Float) {
        if (draggingIndex !in 0..localTracks.lastIndex) return
        val currIdx = draggingIndex

        val visibleSongItems = playlistListState.layoutInfo.visibleItemsInfo.filter { it.contentType == "song" }
        if (visibleSongItems.isEmpty()) return

        val draggedItemInfo = visibleSongItems.find { (it.index - 1) == currIdx }
        val itemHeight = draggedItemInfo?.size?.toFloat() ?: density.run { 56.dp.toPx() }
        val draggedCenterY = pointerY - grabOffsetY + (itemHeight / 2f)

        // Check swap with item ABOVE (currIdx - 1) - pure 1:1 center crossing
        if (currIdx > 0) {
            val prevItemInfo = visibleSongItems.find { (it.index - 1) == (currIdx - 1) }
            if (prevItemInfo != null) {
                val prevCenterY = prevItemInfo.offset + (prevItemInfo.size / 2f)
                if (draggedCenterY < prevCenterY) {
                    java.util.Collections.swap(localTracks, currIdx, currIdx - 1)
                    draggingIndex = currIdx - 1
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    return
                }
            }
        }

        // Check swap with item BELOW (currIdx + 1) - pure 1:1 center crossing
        if (currIdx < localTracks.lastIndex) {
            val nextItemInfo = visibleSongItems.find { (it.index - 1) == (currIdx + 1) }
            if (nextItemInfo != null) {
                val nextCenterY = nextItemInfo.offset + (nextItemInfo.size / 2f)
                if (draggedCenterY > nextCenterY) {
                    java.util.Collections.swap(localTracks, currIdx, currIdx + 1)
                    draggingIndex = currIdx + 1
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    return
                }
            }
        }
    }

    // Frame-synced auto-scroll: uses withFrameNanos (Choreographer/vsync) for 120fps smooth scrolling
    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        val edgeZonePx = density.run { 160.dp.toPx() }
        val maxSpeedPxPerSec = density.run { 750.dp.toPx() }

        var lastFrameNanos = 0L

        while (isDragging && isActive) {
            val frameNanos = withFrameNanos { it }

            if (lastFrameNanos == 0L) {
                lastFrameNanos = frameNanos
                continue
            }

            val dtSec = (frameNanos - lastFrameNanos).coerceAtMost(32_000_000L) / 1_000_000_000f
            lastFrameNanos = frameNanos

            val viewportHeight = playlistListState.layoutInfo.viewportSize.height.toFloat()
            if (viewportHeight <= 0f) continue

            val pointerY = currentPointerY
            val firstSongInfo = playlistListState.layoutInfo.visibleItemsInfo.firstOrNull { it.contentType == "song" }
            val isFirstTrackAtTop = firstSongInfo != null && (firstSongInfo.index - 1) == 0 && firstSongInfo.offset >= 0

            val scrollDelta = when {
                pointerY < edgeZonePx && playlistListState.canScrollBackward && !isFirstTrackAtTop -> {
                    val factor = ((edgeZonePx - pointerY) / edgeZonePx).coerceIn(0f, 1f)
                    -(factor * maxSpeedPxPerSec * dtSec)
                }
                pointerY > (viewportHeight - edgeZonePx) && playlistListState.canScrollForward -> {
                    val factor = ((pointerY - (viewportHeight - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1f)
                    factor * maxSpeedPxPerSec * dtSec
                }
                else -> 0f
            }

            if (scrollDelta != 0f) {
                playlistListState.scrollBy(scrollDelta)
                checkTargetSwap(pointerY)
            }
        }
    }

    val baseTracks = when (sortOption) {
        PlaylistSortOption.CUSTOM -> if (isCustomSort) localTracks else playlist.tracks
        PlaylistSortOption.NEWEST -> playlist.tracks.reversed()
        PlaylistSortOption.OLDEST -> playlist.tracks
        PlaylistSortOption.ALPHABETICAL -> playlist.tracks.sortedBy { it.title.lowercase() }
        PlaylistSortOption.BY_ARTIST -> playlist.tracks.sortedBy { it.artist.lowercase() }
    }

    val displayedTracks = (if (searchQuery.isBlank()) {
        baseTracks
    } else {
        baseTracks.filter {
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
            .background(MaterialTheme.colorScheme.background)
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
            IconButton(
                onClick = onBack,
                modifier = Modifier.tactileBounce(scaleDown = 0.88f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(
                onClick = { isSearchActive = !isSearchActive },
                modifier = Modifier.tactileBounce(scaleDown = 0.88f)
            ) {
                Icon(
                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (isSearchActive) LIME_TEXT else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        if (isSearchActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CARD_DARK_BG)
                    .border(1.dp, LIME_TEXT.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search in playlist...",
                                style = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            cursorBrush = SolidColor(LIME_TEXT),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // ================================================================
        // 2. MAIN SCROLLABLE BODY
        // ================================================================
        val playlistBottomPad = if (currentTrackId != null) 180.dp else 100.dp
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = playlistListState,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInWindow()
                        viewportTopY = pos.y
                        viewportBottomY = pos.y + coords.size.height
                    }
                    .pointerInput(isCustomSort) {
                        if (!isCustomSort) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                val visibleSongItems = playlistListState.layoutInfo.visibleItemsInfo.filter { it.contentType == "song" }
                                val hitItem = visibleSongItems.find { info ->
                                    startOffset.y.toInt() in info.offset..(info.offset + info.size)
                                }
                                if (hitItem != null) {
                                    val songIdx = hitItem.index - 1
                                    if (songIdx in 0..localTracks.lastIndex) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        originalDragIndex = songIdx
                                        draggingIndex = songIdx
                                        grabOffsetY = startOffset.y - hitItem.offset.toFloat()
                                        currentPointerY = startOffset.y
                                        isDragging = true
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                currentPointerY = change.position.y
                                checkTargetSwap(currentPointerY)
                            },
                            onDragEnd = {
                                val finalIdx = draggingIndex
                                val startIdx = originalDragIndex
                                isDragging = false
                                draggingIndex = -1
                                originalDragIndex = -1
                                if (startIdx != -1 && finalIdx != -1 && startIdx != finalIdx) {
                                    onReorderTracks?.invoke(startIdx, finalIdx)
                                }
                            },
                            onDragCancel = {
                                localTracks.clear()
                                localTracks.addAll(playlist.tracks)
                                isDragging = false
                                draggingIndex = -1
                                originalDragIndex = -1
                            }
                        )
                    },
                contentPadding = PaddingValues(bottom = playlistBottomPad)
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
                            val detailValidTracks = remember(playlist.tracks) {
                                playlist.tracks.filter { !it.thumbnail.isNullOrBlank() || it.id.isNotBlank() }
                            }

                            if (!playlist.coverUrl.isNullOrBlank()) {
                                ArtworkCard(
                                    url = playlist.coverUrl,
                                    fallbackTrack = detailValidTracks.firstOrNull(),
                                    modifier = Modifier.fillMaxSize(),
                                    cornerRadius = 18.dp,
                                    contentDescription = playlist.title
                                )
                            } else if (detailValidTracks.size >= 4) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                        ArtworkCard(
                                            url = detailValidTracks[0].thumbnail,
                                            fallbackTrack = detailValidTracks[0],
                                            modifier = Modifier.weight(1f).fillMaxSize(),
                                            cornerRadius = 0.dp,
                                            contentDescription = null
                                        )
                                        ArtworkCard(
                                            url = detailValidTracks[1].thumbnail,
                                            fallbackTrack = detailValidTracks[1],
                                            modifier = Modifier.weight(1f).fillMaxSize(),
                                            cornerRadius = 0.dp,
                                            contentDescription = null
                                        )
                                    }
                                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                        ArtworkCard(
                                            url = detailValidTracks[2].thumbnail,
                                            fallbackTrack = detailValidTracks[2],
                                            modifier = Modifier.weight(1f).fillMaxSize(),
                                            cornerRadius = 0.dp,
                                            contentDescription = null
                                        )
                                        ArtworkCard(
                                            url = detailValidTracks[3].thumbnail,
                                            fallbackTrack = detailValidTracks[3],
                                            modifier = Modifier.weight(1f).fillMaxSize(),
                                            cornerRadius = 0.dp,
                                            contentDescription = null
                                        )
                                    }
                                }
                            } else if (detailValidTracks.isNotEmpty()) {
                                ArtworkCard(
                                    url = detailValidTracks.first().thumbnail,
                                    fallbackTrack = detailValidTracks.first(),
                                    modifier = Modifier.fillMaxSize(),
                                    cornerRadius = 18.dp,
                                    contentDescription = playlist.title
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = null,
                                        tint = LIME_TEXT,
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
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

                        Spacer(modifier = Modifier.height(16.dp))

                        // Playlist Title
                        Text(
                            text = playlist.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Subtitle: Track count, duration
                        val trackCountStr = "${playlist.tracks.size} ${if (playlist.tracks.size == 1) "track" else "tracks"}"
                        val subtitle = if (playlist.tracks.isNotEmpty()) "$trackCountStr • $durationFormatted" else trackCountStr

                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Author Row
                        val authorName = if (userName.isNotBlank() && !userName.contains("listener", ignoreCase = true)) userName else "You"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!userAvatarUrl.isNullOrBlank()) {
                                    ArtworkCard(
                                        url = userAvatarUrl,
                                        fallbackTrack = null,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 11.dp,
                                        contentDescription = authorName
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = authorName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 14.sp
                            )
                        }

                        // Description text (if any)
                        if (!playlist.description.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = playlist.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        if (displayedTracks.isNotEmpty()) {
                                            val shuffled = displayedTracks.shuffled()
                                            onPlayTrack(shuffled.first(), shuffled)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // Large Play Button
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable {
                                        if (displayedTracks.isNotEmpty()) {
                                            onPlayTrack(displayedTracks.first(), displayedTracks)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // 3-Dots Options Button
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { showOptionsMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Playlist Options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // ========================================================
                        // Sort Order Button & Dropdown Menu
                        // ========================================================
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Sort Order Dropdown
                            Box {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showSortMenu = true }
                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = sortOption.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Sort Options",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                ) {
                                    PlaylistSortOption.values().forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = option.label,
                                                    color = if (sortOption == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                    fontWeight = if (sortOption == option) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 14.sp
                                                )
                                            },
                                            onClick = {
                                                sortOption = option
                                                showSortMenu = false
                                            },
                                            trailingIcon = {
                                                if (sortOption == option) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Right side: Filter & Sort icons
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Sort Playlist",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                // ================================================================
                // 4. TRACKS LIST
                // ================================================================
                itemsIndexed(
                    items = displayedTracks,
                    key = { index, t -> "${t.id}_${t.title.hashCode()}_$index" },
                    contentType = { _, _ -> "song" }
                ) { index, track ->
                    val isCurrent = track.id == currentTrackId
                    val trackMin = track.duration / 60
                    val trackSec = track.duration % 60
                    val trackDurationStr = "$trackMin:${if (trackSec < 10) "0" else ""}$trackSec"
                    val isItemBeingDragged = isDragging && draggingIndex == index

                    com.auralis.music.ui.components.SwipeableTrackContainer(
                        onPlayNext = { onPlayNextTrack?.invoke(track) },
                        onAddToQueue = { onAddToQueueTrack?.invoke(track) },
                        onRemoveFromPlaylist = { onRemoveTrack(track.id) },
                        isPlaylistContext = true,
                        modifier = Modifier
                            .then(
                                if (isCustomSort && !isItemBeingDragged) {
                                    Modifier.animateItemPlacement(
                                        animationSpec = tween(
                                            durationMillis = 100,
                                            easing = LinearOutSlowInEasing
                                        )
                                    )
                                } else Modifier
                            )
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .graphicsLayer {
                                alpha = if (isItemBeingDragged) 0.2f else 1f
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (!isDragging) {
                                        onPlayTrack(track, displayedTracks)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkCard(
                                url = track.thumbnail,
                                modifier = Modifier.size(48.dp),
                                cornerRadius = 8.dp,
                                contentDescription = track.title,
                                fallbackTrack = track
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                            if (isCustomSort) {
                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag to reorder song",
                                        tint = if (isItemBeingDragged) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.45f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            IconButton(onClick = { onMenuClick(track) }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ================================================================
            // FLOATING DRAGGED CARD OVERLAY
            // ================================================================
            val draggedTrack = if (draggingIndex in 0..localTracks.lastIndex) localTracks.getOrNull(draggingIndex) else null
            if (isDragging && draggedTrack != null) {
                val trackMin = draggedTrack.duration / 60
                val trackSec = draggedTrack.duration % 60
                val trackDurationStr = "$trackMin:${if (trackSec < 10) "0" else ""}$trackSec"

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (currentPointerY - grabOffsetY).roundToInt()
                            )
                        }
                        .zIndex(999f)
                        .graphicsLayer {
                            scaleX = 1.04f
                            scaleY = 1.04f
                            shadowElevation = 32f
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f),
                                RoundedCornerShape(12.dp)
                            )
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkCard(
                            url = draggedTrack.thumbnail,
                            modifier = Modifier.size(48.dp),
                            cornerRadius = 8.dp,
                            contentDescription = draggedTrack.title,
                            fallbackTrack = draggedTrack
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = draggedTrack.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${draggedTrack.artist} • $trackDurationStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder song",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
            containerColor = CARD_DARK_BG,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
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
                val isSmartPlaylist = playlist.id.startsWith("smart_")

                // Option 1: Edit (Only for user playlists)
                if (!isSmartPlaylist) {
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
                }

                // Option 2: Add to queue
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

                // Option 4: Download (Only for non-downloaded playlists)
                if (playlist.id != "smart_downloaded") {
                    val isAllDownloaded = playlist.tracks.isNotEmpty() && playlist.tracks.all { com.auralis.music.data.download.AuralisDownloadManager.isDownloaded(it.id) }
                    PlaylistActionRow(
                        icon = if (isAllDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                        title = if (isAllDownloaded) "Downloaded" else "Download playlist",
                        subtitle = if (isAllDownloaded) "All ${playlist.tracks.size} songs are available offline" else "Download all songs for offline playback",
                        onClick = {
                            showOptionsMenu = false
                            com.auralis.music.data.download.AuralisDownloadManager.downloadPlaylist(playlist.tracks, playlist.title)
                        }
                    )
                }

                // Option 4.5: Sync / Match Tracks
                PlaylistActionRow(
                    icon = Icons.Default.Sync,
                    title = "Sync / Match Songs",
                    subtitle = "Match songs to verified YouTube tracks",
                    onClick = {
                        showOptionsMenu = false
                        onSyncPlaylist?.invoke(playlist)
                        android.widget.Toast.makeText(context, "Matching tracks to official audio...", android.widget.Toast.LENGTH_SHORT).show()
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
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Listen to '${playlist.title}' on Auralis Music (${playlist.tracks.size} songs)\n\nDownload Auralis App: https://auralis-self-nu.vercel.app/"
                            )
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

                // Option 7: Delete (NEVER show for smart playlists or downloaded playlist)
                if (!isSmartPlaylist) {
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
    }

    // ========================================================================
    // 5. EDIT PLAYLIST DIALOG (Photo, Name, Description)
    // ========================================================================
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = CARD_DARK_BG,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Edit Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
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
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.5.dp, if (editCoverUrl.isNotBlank()) LIME_TEXT else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
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
                                Text("Reset Photo", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                    }

                    // Playlist Name TextField
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Playlist Name", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedTextColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                            focusedIndicatorColor = LIME_TEXT,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Playlist Description TextField
                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text("Description (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedTextColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                            focusedIndicatorColor = LIME_TEXT,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
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
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            containerColor = CARD_DARK_BG,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Export playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
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
                            tint = if (selectedExportFormat == "CSV") LIME_TEXT else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Export as CSV",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
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
                            tint = if (selectedExportFormat == "M3U") LIME_TEXT else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Export as M3U",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
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
            containerColor = CARD_DARK_BG,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Delete Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete '${playlist.title}'?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeletePlaylist()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel", color = LIME_TEXT, fontWeight = FontWeight.SemiBold)
                }
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
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}
