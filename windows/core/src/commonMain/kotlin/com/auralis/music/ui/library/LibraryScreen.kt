package com.auralis.music.ui.library

import com.auralis.music.ui.i18n.Strings

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.LockOpen
import com.auralis.music.ui.components.contextMenuAnchor
import com.auralis.music.ui.components.tapMenuAnchor
import com.auralis.music.ui.components.captureForTap
import com.auralis.music.ui.components.GlassContextMenu
import com.auralis.music.ui.components.GlassMenuItem
import com.auralis.music.ui.components.AuralisRefreshBox
import com.auralis.music.ui.i18n.str

import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.auralis.music.ui.components.clickable
import com.auralis.music.ui.components.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.components.smoothScrollToTop
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.theme.auralisDetailBackwardEnter
import com.auralis.music.ui.theme.auralisDetailBackwardExit
import com.auralis.music.ui.theme.auralisDetailForwardEnter
import com.auralis.music.ui.theme.auralisDetailForwardExit
import com.auralis.music.ui.theme.auralisNavigationEnter
import com.auralis.music.ui.theme.auralisNavigationExit
import com.auralis.music.ui.theme.dynamicOnBackground
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.unit.Dp
import com.auralis.music.ui.components.LiquidGlassPageHeader
import com.auralis.music.ui.components.LiquidGlassPageTitle
import com.auralis.music.ui.components.rememberPageHeaderGlass
import com.auralis.music.ui.glass.LiquidGlassContext
import com.auralis.music.ui.glass.liquidGlass
import com.auralis.music.ui.glass.liquidGlassDialog
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.foundation.gestures.detectDragGestures
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
import com.auralis.music.ui.components.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import com.auralis.music.ui.components.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.auralis.music.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import com.auralis.music.ui.components.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.AlertDialogDefaults
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
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.ui.viewmodel.LibraryFilter
import com.auralis.music.ui.viewmodel.LibraryUiState
import com.auralis.music.ui.viewmodel.SmartCollectionType
import com.auralis.music.ui.components.bottomChromePadding
import java.util.Locale

val CREAM_ICON_COLOR: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer

enum class PlaylistSortOption(val label: String) {
    CUSTOM("Custom order"),
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    ALPHABETICAL("Alphabetical (A-Z)"),
    BY_ARTIST("Artist (A-Z)")
}

fun sortLibraryPlaylists(
    playlists: List<Playlist>,
    order: String,
    recentPlayedAtByTrackId: Map<String, Long>
): List<Playlist> {
    val byTitle = compareBy<Playlist> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
    return when (order) {
        "Recently played" -> playlists.sortedWith(
            compareByDescending<Playlist> { playlist ->
                playlist.tracks.maxOfOrNull { recentPlayedAtByTrackId[it.id] ?: 0L } ?: 0L
            }.then(byTitle)
        )
        "Alphabetical (A to Z)" -> playlists.sortedWith(byTitle)
        "Alphabetical (Z to A)" -> playlists.sortedWith(byTitle.reversed())
        "Track count" -> playlists.sortedWith(
            compareByDescending<Playlist> { it.tracks.size }.then(byTitle)
        )
        else -> playlists.sortedWith(
            compareByDescending<Playlist> { it.createdAt }.then(byTitle)
        )
    }
}

private fun smartCollectionTypeFor(id: String): SmartCollectionType? = when (id) {
    "smart_liked" -> SmartCollectionType.LIKED
    "smart_downloaded" -> SmartCollectionType.DOWNLOADED
    "smart_top_50" -> SmartCollectionType.MY_TOP_50
    "smart_weekly_most" -> SmartCollectionType.WEEKLY_MOST
    "smart_monthly_most" -> SmartCollectionType.MONTHLY_MOST
    "smart_cached" -> SmartCollectionType.CACHED
    else -> null
}


private fun smartCollectionIconFor(id: String, isJobDownloading: Boolean): ImageVector = when (id) {
    "smart_liked" -> Icons.Default.FavoriteBorder
    "smart_downloaded" -> if (isJobDownloading) Icons.Default.Sync else Icons.Default.DownloadDone
    "smart_top_50", "smart_weekly_most", "smart_monthly_most" -> Icons.Default.Leaderboard
    else -> Icons.Default.CloudDownload
}

private fun smartCollectionSubtitleFor(playlist: Playlist, isJobDownloading: Boolean): String =
    if (playlist.id == "smart_downloaded" && isJobDownloading) "Downloading..."
    else "${playlist.tracks.size} songs"

/** Below this the playlist page switches from the song table to a plain list. */
private val PLAYLIST_TABLE_MIN_WIDTH = 480.dp

/**
 * Pure Jetpack Compose Library Screen with top App Bar (Library title, History, Listen Together, Profile),
 * "Date added ↓" sorting bar, Grid vs List toggle, Search filtering, 2-column grid or single-column list of Liked default playlist
 * + user & synced playlists with 4-cover collages, and bottom right floating '+' button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onRefresh: suspend () -> Unit = {},
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
    onOpenAlbum: ((com.auralis.music.domain.model.PlaylistResult) -> Unit)? = null,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    onReorderPlaylistTracks: ((String, Int, Int) -> Unit)? = null,
    isExternalCreateDialogOpen: Boolean = false,
    onCloseExternalCreateDialog: () -> Unit = {},
    onCloseSmartCollection: () -> Unit = {},
    onDeletePlaylistJob: (String) -> Unit = {},
    onRetryPlaylistJob: (String) -> Unit = {},
    isTrackPinned: ((String) -> Boolean)? = null,
    onPinTrackToSpeedDial: ((Track) -> Unit)? = null,
    floatingHeaderState: com.auralis.music.ui.components.LiquidGlassHeaderPageState? = null,
    /** The phone's Downloads hub (offline folders), shown when Downloaded is opened; null where there are no downloads. */
    downloadsHub: (@Composable (DownloadsHubArgs) -> Unit)? = null,
    /** Optional platform message when the chosen filter or search has no saved items. */
    emptyLibraryContent: (@Composable (LibrarySourceFilter?, String) -> Unit)? = null,
    /** Desktop also recognises older imports that predate source IDs. */
    playlistSource: ((Playlist) -> LibrarySourceFilter?)? = null,
    modifier: Modifier = Modifier
) {
    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    val appearanceStore = com.auralis.music.ui.theme.LocalAppearanceSettingsStore.current
    val appearanceScope = rememberCoroutineScope()
    fun removePlaylist(id: String) {
        if (isRemovableMostPlayedPlaylist(id)) {
            appearanceScope.launch { appearanceStore?.hideMostPlayedPlaylist(id) }
        } else {
            onDeletePlaylist(id)
        }
    }
    val themePrimary = MaterialTheme.colorScheme.primary
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
    var selectedPlaylistForMenu by remember { mutableStateOf<Playlist?>(null) }

    val likedPlaylist = remember(uiState.favorites) {
        Playlist(
            id = "smart_liked",
            title = str(Strings.liked),
            description = str(Strings.auto_saved_tracks),
            tracks = uiState.favorites,
            createdAt = Long.MAX_VALUE
        )
    }
    val downloadedPlaylist = remember(uiState.downloadedTracks) {
        Playlist(
            id = "smart_downloaded",
            title = str(Strings.downloaded),
            description = "${uiState.downloadedTracks.size} offline songs",
            tracks = uiState.downloadedTracks,
            createdAt = Long.MAX_VALUE - 1
        )
    }
    val isJobDownloading = platformActions.hasActiveDownloads() || uiState.downloadedJobs.any { it.status == "DOWNLOADING" }
    val hasDownloads = uiState.downloadedTracks.isNotEmpty() || isJobDownloading
    val top50Playlist = remember(uiState.top50Tracks) {
        Playlist(
            id = "smart_top_50",
            title = str(Strings.top_most_played),
            description = str(Strings.your_most_played_tracks),
            tracks = uiState.top50Tracks,
            createdAt = Long.MAX_VALUE - 2
        )
    }
    val weeklyMostPlaylist = remember(uiState.weeklyMostTracks) {
        Playlist(
            id = "smart_weekly_most",
            title = str(Strings.weekly_most_played),
            description = str(Strings.most_played_in_the_last_7_days),
            tracks = uiState.weeklyMostTracks,
            createdAt = Long.MAX_VALUE - 4
        )
    }
    val monthlyMostPlaylist = remember(uiState.monthlyMostTracks) {
        Playlist(
            id = "smart_monthly_most",
            title = str(Strings.monthly_most_played),
            description = str(Strings.most_played_in_the_last_30_days),
            tracks = uiState.monthlyMostTracks,
            createdAt = Long.MAX_VALUE - 5
        )
    }
    val cachedPlaylist = remember(uiState.cachedTracks) {
        Playlist(
            id = "smart_cached",
            title = str(Strings.cached_streamed),
            description = str(Strings.locally_buffered_tracks),
            tracks = uiState.cachedTracks,
            createdAt = Long.MAX_VALUE - 3
        )
    }

    com.auralis.music.ui.components.PlatformBackHandler(
        enabled = uiState.selectedPlaylist != null || uiState.selectedSmartCollection == SmartCollectionType.DOWNLOADED || isSearchActive || showSortMenu || showCreateDialog || selectedPlaylistForMenu != null
    ) {
        if (selectedPlaylistForMenu != null) selectedPlaylistForMenu = null
        else if (showCreateDialog) showCreateDialog = false
        else if (showSortMenu) showSortMenu = false
        else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (uiState.selectedPlaylist != null) {
            onPlaylistSelect(null)
        } else if (uiState.selectedSmartCollection == SmartCollectionType.DOWNLOADED) {
            onCloseSmartCollection()
        }
    }

    val detailForwardEnter = auralisDetailForwardEnter()
    val detailForwardExit = auralisDetailForwardExit()
    val detailBackwardEnter = auralisDetailBackwardEnter()
    val detailBackwardExit = auralisDetailBackwardExit()

    // ── STATE PRESERVATION: SaveableStateHolder keeps playlist detail scroll
    // state alive when navigating back to the library list. ──
    val librarySaveableStateHolder = rememberSaveableStateHolder()

    AnimatedContent(
        targetState = when {
            uiState.selectedPlaylist != null -> "playlist_${uiState.selectedPlaylist.id}"
            uiState.selectedSmartCollection == SmartCollectionType.DOWNLOADED -> "downloaded_hub"
            else -> null
        },
        transitionSpec = {
            if (targetState != null && initialState == null) {
                detailForwardEnter togetherWith detailForwardExit
            } else if (targetState == null && initialState != null) {
                detailBackwardEnter togetherWith detailBackwardExit
            } else {
                detailForwardEnter togetherWith detailForwardExit
            }
        },
        label = "PlaylistDetailTransition"
    ) { screenKey ->
        librarySaveableStateHolder.SaveableStateProvider(screenKey ?: "library_root") {
        if (screenKey != null && screenKey.startsWith("playlist_")) {
            val targetPlaylistId = screenKey.removePrefix("playlist_")
            val selectedPl = uiState.selectedPlaylist?.takeIf { it.id == targetPlaylistId }
                ?: uiState.playlists.firstOrNull { it.id == targetPlaylistId }
            if (selectedPl != null) {
                PlaylistDetailView(
                    playlist = selectedPl,
                    savedAlbums = uiState.savedAlbums,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    userName = userName,
                    userAvatarUrl = userAvatarUrl,
                    onBack = { onPlaylistSelect(null) },
                    onPlayTrack = { track, list -> onTrackClick(track, list) },
                    onRemoveTrack = { trackId -> onRemoveFromPlaylist(selectedPl.id, trackId) },
                    onDeletePlaylist = {
                        removePlaylist(selectedPl.id)
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
                    val isCustomPl = !selectedPl.id.startsWith("smart_")

                    TrackOptionsMenu(
                        track = track,
                        isFavorite = isFav,
                        userPlaylists = uiState.playlists,
                        onToggleFavorite = { onFavoriteToggle(track) },
                        isPinned = isTrackPinned?.invoke(track.id) == true,
                        onPinToSpeedDial = { onPinTrackToSpeedDial?.invoke(track) },
                        onPlayNext = {
                            onPlayNext(track)
                            selectedTrackForMenu = null
                        },
                        onAddToQueue = {
                            onAddToQueueTrack(track)
                            selectedTrackForMenu = null
                        },
                        onRemoveFromPlaylist = if (isCustomPl) {
                            {
                                onRemoveFromPlaylist(selectedPl.id, track.id)
                                selectedTrackForMenu = null
                            }
                        } else null,
                        onStartRadio = {
                            onStartRadio(track)
                            selectedTrackForMenu = null
                        },
                        onGoToArtist = { artistName ->
                            onOpenArtist(Artist(id = "", name = artistName))
                            selectedTrackForMenu = null
                        },
                        onGoToAlbum = { albumId, albumTitle, albumArtist, albumArt ->
            val cached = com.auralis.music.data.network.AlbumMetadataResolver.getCached(track.title, track.artist, track.album)
                            onOpenAlbum?.invoke(
                                com.auralis.music.domain.model.PlaylistResult(
                                    id = albumId ?: cached?.albumId ?: "album-${track.id}",
                                    title = albumTitle,
                                    author = albumArtist ?: cached?.artistName ?: track.artist,
                                    thumbnail = albumArt ?: cached?.albumArt ?: track.thumbnail
                                )
                            )
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
            }
        } else if (screenKey == "downloaded_hub") {
            downloadsHub?.invoke(
                DownloadsHubArgs(
                    downloadedTracks = uiState.downloadedTracks,
                    userPlaylists = uiState.playlists,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    onBack = onCloseSmartCollection,
                    onOpenFolder = { folderPl -> onPlaylistSelect(folderPl) },
                    onPlayTracks = { track, list -> onTrackClick(track, list) },
                    onDeleteJob = onDeletePlaylistJob,
                    onRetryJob = onRetryPlaylistJob,
                    onClearAllDownloads = { platformActions.clearAllDownloads() }
                )
            )
        } else {

    val gridScrollState = rememberLazyGridState()
    val listScrollState = rememberLazyListState()
    val reducedScrollMotion = LocalReducedMotion.current
    val headerScope = rememberCoroutineScope()
    val libraryBackground = MaterialTheme.dynamicBackground
    val headerBackdropDraw: androidx.compose.ui.graphics.drawscope.ContentDrawScope.() -> Unit =
        remember(libraryBackground, isGridView, gridScrollState, listScrollState) {
            {
                // Reading scroll offset establishes a Compose snapshot dependency so this layer
                // automatically re-records and updates the header glass as content scrolls
                @Suppress("UNUSED_VARIABLE")
                val scrollOffset = if (isGridView) gridScrollState.firstVisibleItemScrollOffset else listScrollState.firstVisibleItemScrollOffset
                @Suppress("UNUSED_VARIABLE")
                val scrollIndex = if (isGridView) gridScrollState.firstVisibleItemIndex else listScrollState.firstVisibleItemIndex
                drawRect(libraryBackground)
                drawContent()
            }
        }
    val headerBackdrop = rememberLayerBackdrop(onDraw = headerBackdropDraw)
    val headerGlass = rememberPageHeaderGlass(headerBackdrop)
    var sourceFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<LibrarySourceFilter?>(null) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    var filterOrigin by remember { mutableStateOf<Offset?>(null) }
    var filterHeight by remember { mutableStateOf(48.dp) }
    val filterDensity = androidx.compose.ui.platform.LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { pageOrigin = it.positionInRoot() }
            .background(MaterialTheme.dynamicBackground)
    ) {
        androidx.compose.runtime.DisposableEffect(floatingHeaderState, headerGlass) {
            floatingHeaderState?.glass = headerGlass
            onDispose { floatingHeaderState?.glass = null }
        }
        androidx.compose.runtime.SideEffect {
            floatingHeaderState?.scrollToTop = {
                headerScope.launch {
                    if (isGridView) {
                        if (reducedScrollMotion) gridScrollState.scrollToItem(0) else gridScrollState.smoothScrollToTop()
                    } else {
                        if (reducedScrollMotion) listScrollState.scrollToItem(0) else listScrollState.smoothScrollToTop()
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            val browseHeader: @Composable () -> Unit = {
                Column {
                    // ================================================================
                    // 1. TOP APP BAR: "Library" Title + 3 Action Icons
                    // ================================================================
                    if (headerGlass != null) {
                        LiquidGlassPageTitle(
                            str(Strings.library),
                            scrollOffsetPx = {
                                if (isGridView) {
                                    if (gridScrollState.firstVisibleItemIndex == 0) gridScrollState.firstVisibleItemScrollOffset
                                    else Int.MAX_VALUE
                                } else {
                                    if (listScrollState.firstVisibleItemIndex == 0) listScrollState.firstVisibleItemScrollOffset
                                    else Int.MAX_VALUE
                                }
                            }
                        )
                    } else {
                        val themeOnBackground = MaterialTheme.dynamicOnBackground
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(start = 16.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Windows page title: no logo, the same size as the other pages' headings.
                                Text(
                                    text = str(Strings.library),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = themeOnBackground,
                                    fontSize = 32.sp,
                                    lineHeight = 38.sp
                                )
                            }

                        }

                    }

                    // Source filter pills sit above the sort / search / layout row.
                    if (headerGlass != null) {
                        // Keep only a layout anchor in the recorded scrolling layer.
                        // The glass row is drawn below as a sibling of that layer.
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            onDispose { filterOrigin = null }
                        }
                        Spacer(Modifier.fillMaxWidth().height(filterHeight).onGloballyPositioned {
                            filterOrigin = it.positionInRoot()
                        })
                    } else {
                        LibrarySourcePills(
                            selected = sourceFilter,
                            onSelect = { tapped -> sourceFilter = if (sourceFilter == tapped) null else tapped }
                        )
                    }

                    // ================================================================
                    // 2. SORTING & CONTROLS BAR ("Date added ↓", Search & Grid/List Toggle)
                    // ================================================================
                    if (isSearchActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (headerGlass != null) 0.dp else 16.dp, vertical = 6.dp)
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
                                            text = str(Strings.search_library),
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
                                        contentDescription = str(Strings.clear),
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
                                // Tucked up under the filter pills.
                                .padding(start = if (headerGlass != null) 2.dp else 18.dp, end = if (headerGlass != null) 2.dp else 18.dp, top = 0.dp, bottom = if (headerGlass != null) 0.dp else 4.dp),
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
                                    text = "${com.auralis.music.ui.i18n.UiLabels.of(uiState.sortOrder)} ↓",
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
                                        contentDescription = str(Strings.search_library_2),
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
                                        contentDescription = if (isGridView) str(Strings.switch_to_list_view) else str(Strings.switch_to_grid_view),
                                        tint = if (isGridView) MaterialTheme.colorScheme.onBackground else LIME_TEXT,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (headerGlass == null) Spacer(modifier = Modifier.height(6.dp))

                }
            }
            if (headerGlass == null) browseHeader()

            val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
            val displayedPlaylists = sortLibraryPlaylists(
                playlists = buildList {
                    if (searchQuery.isBlank()) {
                        if (appearance.showLikedPlaylist) add(likedPlaylist)
                        if (appearance.showDownloadedPlaylist && hasDownloads) add(downloadedPlaylist)
                        if (appearance.showTopPlaylist && uiState.top50Tracks.isNotEmpty()) add(top50Playlist)
                        // Empty unless Settings → Content → Wrapped → Most playlists is on (see LibraryViewModel).
                        if (appearance.showWeeklyMostPlaylist && uiState.weeklyMostTracks.isNotEmpty()) add(weeklyMostPlaylist)
                        if (appearance.showMonthlyMostPlaylist && uiState.monthlyMostTracks.isNotEmpty()) add(monthlyMostPlaylist)
                        if (appearance.showCachedPlaylist && uiState.cachedTracks.isNotEmpty()) add(cachedPlaylist)
                    }
                    addAll(uiState.playlists.filter { it.title.contains(searchQuery, ignoreCase = true) })
                }.filter { playlist ->
                    val filter = sourceFilter ?: return@filter true
                    val isSmart = smartCollectionTypeFor(playlist.id) != null
                    when (filter) {
                        LibrarySourceFilter.PLAYLISTS -> isSmart || !isAlbumPlaylist(playlist, uiState.savedAlbums)
                        LibrarySourceFilter.ALBUMS -> !isSmart && isAlbumPlaylist(playlist, uiState.savedAlbums)
                        LibrarySourceFilter.ARTISTS -> false
                        LibrarySourceFilter.SPOTIFY -> if (playlistSource != null) playlistSource(playlist) == filter else isSpotifyImport(playlist)
                        LibrarySourceFilter.YOUTUBE -> if (playlistSource != null) playlistSource(playlist) == filter else isYouTubeMusicImport(playlist)
                    }
                },
                order = uiState.sortOrder,
                recentPlayedAtByTrackId = uiState.recentPlayedAtByTrackId
            )
            val gridAnimate = !LocalReducedMotion.current
            val displayedArtists = if (sourceFilter == null || sourceFilter == LibrarySourceFilter.ARTISTS)
                uiState.savedArtists.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    .distinctBy { it.id }.let { artists -> when (uiState.sortOrder) {
                        "Alphabetical (A to Z)" -> artists.sortedBy { it.name.lowercase(Locale.ROOT) }
                        "Alphabetical (Z to A)" -> artists.sortedByDescending { it.name.lowercase(Locale.ROOT) }
                        else -> artists.sortedByDescending { it.savedAt }
                    } }
            else emptyList()
            val gridFadeEnter = fadeIn(motionTween(AuralisDuration.Quick, AuralisEasing.Standard))
            val gridFadeExit = fadeOut(motionTween(AuralisDuration.Fast, AuralisEasing.Standard))
            
            val minGridSize = when (appearance.gridCellSize) {
                "Small" -> 135.dp
                "Large" -> 195.dp
                else -> 160.dp
            }


            AuralisRefreshBox(
                refresh = onRefresh,
                indicatorTopInset = if (headerGlass != null) com.auralis.music.ui.components.LiquidGlassHeaderHeight else 0.dp,
                modifier = Modifier.fillMaxSize()
                    .then(if (headerGlass != null) Modifier.layerBackdrop(headerBackdrop) else Modifier)
                    .background(MaterialTheme.dynamicBackground)
            ) {
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
                            state = gridScrollState,
                            columns = GridCells.Adaptive(minSize = minGridSize),
                            contentPadding = bottomChromePadding(start = 16.dp, end = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (headerGlass != null) {
                                item(key = "library_glass_header", span = { GridItemSpan(maxLineSpan) }) {
                                    // The grid adds 16dp between rows. Leave just 4dp after the controls.
                                    Box(Modifier.layout { measurable, constraints ->
                                        val header = measurable.measure(constraints)
                                        layout(header.width, (header.height - 12.dp.roundToPx()).coerceAtLeast(0)) {
                                            header.placeRelative(0, 0)
                                        }
                                    }) { browseHeader() }
                                }
                            }
                            if (displayedPlaylists.isEmpty() && displayedArtists.isEmpty() && emptyLibraryContent != null) {
                                item(key = "empty_library", span = { GridItemSpan(maxLineSpan) }) {
                                    emptyLibraryContent(sourceFilter, searchQuery)
                                }
                            }
                            items(displayedPlaylists, key = { it.id }) { playlist ->
                                Box(modifier = if (gridAnimate) Modifier.animateItem() else Modifier) {
                                    val smartType = smartCollectionTypeFor(playlist.id)
                                    if (smartType != null) {
                                        SmartLibraryCard(
                                            title = playlist.title,
                                            subtitle = smartCollectionSubtitleFor(playlist, isJobDownloading),
                                            icon = smartCollectionIconFor(playlist.id, isJobDownloading),
                                            tracks = playlist.tracks,
                                            onClick = { onSmartCollectionClick(smartType) },
                                            onLongClick = { selectedPlaylistForMenu = playlist }
                                        )
                                    } else {
                                        UserPlaylistGridCard(
                                            playlist = playlist,
                                            savedAlbums = uiState.savedAlbums,
                                            onClick = { onPlaylistSelect(playlist) },
                                            onLongClick = { selectedPlaylistForMenu = playlist }
                                        )
                                    }
                                }
                            }
                            items(displayedArtists, key = { "artist:${it.id}" }) { artist ->
                                SavedArtistCard(artist, onClick = {
                                    onOpenArtist(Artist(artist.id, artist.name, artist.thumbnail, artist.subscribers, artist.query ?: artist.name))
                                })
                            }
                        }
                    } else {
                        // ============================================================
                        // 📋 1-COLUMN LIST VIEW
                        // ============================================================
                        LazyColumn(
                            state = listScrollState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = bottomChromePadding(start = 16.dp, end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (headerGlass != null) {
                                item(key = "library_glass_header") { browseHeader() }
                            }
                            if (displayedPlaylists.isEmpty() && displayedArtists.isEmpty() && emptyLibraryContent != null) {
                                item(key = "empty_library") { emptyLibraryContent(sourceFilter, searchQuery) }
                            }
                            items(
                                items = displayedPlaylists,
                                key = { it.id },
                                contentType = { if (smartCollectionTypeFor(it.id) != null) "smart_row" else "playlist" }
                            ) { playlist ->
                                Box(modifier = if (gridAnimate) Modifier.animateItem() else Modifier) {
                                    val smartType = smartCollectionTypeFor(playlist.id)
                                    if (smartType != null) {
                                        SmartLibraryListRow(
                                            title = playlist.title,
                                            subtitle = smartCollectionSubtitleFor(playlist, isJobDownloading),
                                            icon = smartCollectionIconFor(playlist.id, isJobDownloading),
                                            tracks = playlist.tracks,
                                            onClick = { onSmartCollectionClick(smartType) },
                                            onLongClick = { selectedPlaylistForMenu = playlist }
                                        )
                                    } else {
                                        UserPlaylistListRow(
                                            playlist = playlist,
                                            savedAlbums = uiState.savedAlbums,
                                            onClick = { onPlaylistSelect(playlist) },
                                            onLongClick = { selectedPlaylistForMenu = playlist }
                                        )
                                    }
                                }
                            }
                            items(displayedArtists, key = { "artist:${it.id}" }) { artist ->
                                SavedArtistCard(artist, listMode = true, onClick = {
                                    onOpenArtist(Artist(artist.id, artist.name, artist.thumbnail, artist.subscribers, artist.query ?: artist.name))
                                })
                            }
                        }
                    }
                }
            }
        }
        // Never put a glass consumer inside its own recorded backdrop, even via
        // a cached lazy-item graphics layer: that creates a RenderNode cycle.
        filterOrigin?.let { origin ->
            headerGlass?.let { glass ->
                // Give the lazy row its final viewport on its first measure.
                // A transient zero-width viewport can scroll its first pill
                // out of view before the anchor's measured width arrives.
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).offset {
                    IntOffset(0, (origin.y - pageOrigin.y).toInt())
                }.clipToBounds().onSizeChanged {
                    filterHeight = with(filterDensity) { it.height.toDp() }
                }) {
                    LibrarySourcePills(
                        selected = sourceFilter,
                        glass = glass,
                        horizontalPadding = 0.dp,
                        onSelect = { tapped -> sourceFilter = if (sourceFilter == tapped) null else tapped }
                    )
                }
            }
        }
        if (floatingHeaderState == null) headerGlass?.let { glass ->
            LiquidGlassPageHeader(
                glass = glass,
                onLogoClick = {
                    headerScope.launch {
                        if (isGridView) {
                            if (reducedScrollMotion) gridScrollState.scrollToItem(0) else gridScrollState.smoothScrollToTop()
                        } else {
                            if (reducedScrollMotion) listScrollState.scrollToItem(0) else listScrollState.smoothScrollToTop()
                        }
                    }
                },
                onOpenProfile = onOpenProfile,
                onOpenHistory = onOpenHistory,
                onOpenListenTogether = onOpenListenTogether,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    // Sort Options Dialog
    if (showSortMenu) {
        val sortOptions = listOf("Date added", "Recently played", "Alphabetical (A to Z)", "Alphabetical (Z to A)", "Track count")
        AlertDialog(
            onDismissRequest = { showSortMenu = false },
            title = { Text(str(Strings.sort_playlists_by), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) },
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
                                text = com.auralis.music.ui.i18n.UiLabels.of(opt),
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
                TextButton(onClick = { showSortMenu = false }) { Text(str(Strings.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        )
    }

    // Create New Playlist Dialog
    if (showCreateDialog) {
        var playlistInput by remember { mutableStateOf("") }
        val dialogShape = RoundedCornerShape(28.dp)
        val pageView = com.auralis.music.ui.glass.rememberDialogPageAnchor()

        com.auralis.music.ui.components.GlassAlertDialog(
            onDismissRequest = { showCreateDialog = false },
            modifier = if (headerGlass != null) Modifier.liquidGlassDialog(headerGlass, dialogShape, pageView) else Modifier,
            shape = if (headerGlass != null) dialogShape else AlertDialogDefaults.shape,
            containerColor = if (headerGlass != null) Color.Transparent else CARD_DARK_BG,
            tonalElevation = if (headerGlass != null) 0.dp else AlertDialogDefaults.TonalElevation,
            title = {
                Text(
                    text = str(Strings.new_playlist),
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
                                str(Strings.playlist_title),
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
                        text = str(Strings.create),
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(str(Strings.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // Playlist Options Bottom Sheet (when holding a playlist in Library)
    selectedPlaylistForMenu?.let { pl ->
        PlaylistOptionsBottomSheet(
            playlist = pl,
            onDismiss = { selectedPlaylistForMenu = null },
            onEditPlaylist = onEditPlaylist,
            onAddToQueue = onAddToQueue,
            onDeletePlaylist = {
                removePlaylist(pl.id)
                selectedPlaylistForMenu = null
            },
            onClearAllDownloads = {
                platformActions.clearAllDownloads()
                selectedPlaylistForMenu = null
            }
        )
    }
    }
} // SaveableStateProvider
} // AnimatedContent lambda
} // AnimatedContent call


// ============================================================================
// 🔲 SMART LIBRARY CARD (Liked, Downloaded, Cached, My Top 50, Uploaded)
// ============================================================================





@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmartLibraryCard(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    tracks: List<Track> = emptyList(),
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val validTracks = remember(tracks) {
        tracks.filter { !it.thumbnail.isNullOrBlank() || it.id.isNotBlank() }
    }
    val distinctTracks = remember(tracks) {
        getDistinctArtworkTracks(tracks)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .contextMenuAnchor(onClick)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(CARD_DARK_BG)
        ) {
            if (distinctTracks.size >= 4) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArtworkCard(
                            url = distinctTracks[0].thumbnail,
                            fallbackTrack = distinctTracks[0],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                        ArtworkCard(
                            url = distinctTracks[1].thumbnail,
                            fallbackTrack = distinctTracks[1],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArtworkCard(
                            url = distinctTracks[2].thumbnail,
                            fallbackTrack = distinctTracks[2],
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            cornerRadius = 0.dp,
                            contentDescription = null
                        )
                        ArtworkCard(
                            url = distinctTracks[3].thumbnail,
                            fallbackTrack = distinctTracks[3],
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserPlaylistGridCard(
    playlist: Playlist,
    savedAlbums: List<SavedAlbum> = emptyList(),
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val isAlbum = remember(playlist.id, playlist.title, playlist.description, playlist.tracks, savedAlbums) {
        isAlbumPlaylist(playlist, savedAlbums)
    }
    val validTracks = remember(playlist.tracks) {
        playlist.tracks.filter { !it.thumbnail.isNullOrBlank() || it.id.isNotBlank() }
    }
    val distinctTracks = remember(playlist.tracks) {
        getDistinctArtworkTracks(playlist.tracks)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .contextMenuAnchor(onClick)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(CARD_DARK_BG)
        ) {
            if (isAlbum) {
                // Album cards must display ONE actual album cover matching Player artwork presentation (no 2x2 collage)
                val albumCoverUrl = remember(playlist.coverUrl, playlist.tracks, savedAlbums) {
                    resolveAlbumArtworkUrl(playlist, savedAlbums)
                }
                ArtworkCard(
                    url = albumCoverUrl,
                    fallbackTrack = validTracks.firstOrNull() ?: playlist.tracks.firstOrNull(),
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 18.dp,
                    contentDescription = playlist.title
                )
            } else {
                // Playlist/collection artwork behavior remains unchanged
                if (!playlist.coverUrl.isNullOrBlank() && !usesTrackCollage(playlist, distinctTracks.size)) {
                    ArtworkCard(
                        url = playlist.coverUrl,
                        fallbackTrack = validTracks.firstOrNull(),
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 18.dp,
                        contentDescription = playlist.title
                    )
                } else if (distinctTracks.size >= 4) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            ArtworkCard(
                                url = distinctTracks[0].thumbnail,
                                fallbackTrack = distinctTracks[0],
                                modifier = Modifier.weight(1f).fillMaxSize(),
                                cornerRadius = 0.dp,
                                contentDescription = null
                            )
                            ArtworkCard(
                                url = distinctTracks[1].thumbnail,
                                fallbackTrack = distinctTracks[1],
                                modifier = Modifier.weight(1f).fillMaxSize(),
                                cornerRadius = 0.dp,
                                contentDescription = null
                            )
                        }
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            ArtworkCard(
                                url = distinctTracks[2].thumbnail,
                                fallbackTrack = distinctTracks[2],
                                modifier = Modifier.weight(1f).fillMaxSize(),
                                cornerRadius = 0.dp,
                                contentDescription = null
                            )
                            ArtworkCard(
                                url = distinctTracks[3].thumbnail,
                                fallbackTrack = distinctTracks[3],
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
            text = str(Strings.x_songs, playlist.tracks.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}

// ============================================================================
// 📋 SMART LIBRARY LIST ROW (For List View Mode)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmartLibraryListRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tracks: List<Track> = emptyList(),
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .contextMenuAnchor(onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(CARD_DARK_BG)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
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
                    sizeToConstraints = true,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserPlaylistListRow(
    playlist: Playlist,
    savedAlbums: List<SavedAlbum> = emptyList(),
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val isAlbum = remember(playlist.id, playlist.title, playlist.description, playlist.tracks, savedAlbums) {
        isAlbumPlaylist(playlist, savedAlbums)
    }
    val firstValid = remember(playlist.tracks) {
        playlist.tracks.firstOrNull { !it.thumbnail.isNullOrBlank() || it.id.isNotBlank() } ?: playlist.tracks.firstOrNull()
    }
    val artworkUrl = remember(playlist.coverUrl, playlist.tracks, savedAlbums, isAlbum) {
        if (isAlbum) {
            resolveAlbumArtworkUrl(playlist, savedAlbums)
        } else {
            playlist.coverUrl ?: firstValid?.thumbnail
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .contextMenuAnchor(onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(CARD_DARK_BG)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkCard(
            sizeToConstraints = true,
            url = artworkUrl,
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
                text = str(Strings.x_songs, playlist.tracks.size),
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

// ============================================================================
// 🔄 PLAYLIST REORDER SWAP LOGIC & STALE-LAYOUT HYSTERESIS PROTECTION
// ============================================================================

data class PlaylistReorderItemInfo(
    val key: Any,
    val offset: Int,
    val size: Int
)

class PlaylistDragReorderState(
    var lastSwappedItemId: String? = null,
    var lastSwapDirection: Int = 0 // -1 for UP, 1 for DOWN
) {
    fun reset() {
        lastSwappedItemId = null
        lastSwapDirection = 0
    }
}

data class SwapAction(
    val fromIndex: Int,
    val toIndex: Int,
    val swappedItemId: String,
    val direction: Int // -1 for UP, 1 for DOWN
)

fun evaluateTargetSwap(
    currentId: String,
    localItemIds: List<String>,
    visibleSongItems: List<PlaylistReorderItemInfo>,
    pointerY: Float,
    grabOffsetY: Float,
    fallbackItemHeight: Float,
    swapHysteresisPx: Float,
    reorderState: PlaylistDragReorderState
): SwapAction? {
    val currIdx = localItemIds.indexOfFirst { it == currentId }
    if (currIdx == -1) return null

    val draggedItemInfo = visibleSongItems.find { it.key == currentId }
    val itemHeight = draggedItemInfo?.size?.toFloat() ?: fallbackItemHeight
    val draggedCenterY = pointerY - grabOffsetY + (itemHeight / 2f)

    // Check swap with item ABOVE (currIdx - 1) -> UPWARD SWAP (direction = -1)
    if (currIdx > 0) {
        val prevInstanceId = localItemIds[currIdx - 1]
        val prevItemInfo = visibleSongItems.find { it.key == prevInstanceId }
        if (prevItemInfo != null) {
            val isReversingJustSwapped = (prevInstanceId == reorderState.lastSwappedItemId && reorderState.lastSwapDirection == 1)
            val isLayoutStale = isReversingJustSwapped && (draggedItemInfo == null || draggedItemInfo.offset <= prevItemInfo.offset)
            if (!isLayoutStale) {
                val prevCenterY = prevItemInfo.offset + (prevItemInfo.size / 2f)
                if (draggedCenterY < prevCenterY - swapHysteresisPx) {
                    return SwapAction(
                        fromIndex = currIdx,
                        toIndex = currIdx - 1,
                        swappedItemId = prevInstanceId,
                        direction = -1
                    )
                }
            }
        }
    }

    // Check swap with item BELOW (currIdx + 1) -> DOWNWARD SWAP (direction = 1)
    if (currIdx < localItemIds.lastIndex) {
        val nextInstanceId = localItemIds[currIdx + 1]
        val nextItemInfo = visibleSongItems.find { it.key == nextInstanceId }
        if (nextItemInfo != null) {
            val isReversingJustSwapped = (nextInstanceId == reorderState.lastSwappedItemId && reorderState.lastSwapDirection == -1)
            val isLayoutStale = isReversingJustSwapped && (draggedItemInfo == null || draggedItemInfo.offset >= nextItemInfo.offset)
            if (!isLayoutStale) {
                val nextCenterY = nextItemInfo.offset + (nextItemInfo.size / 2f)
                if (draggedCenterY > nextCenterY + swapHysteresisPx) {
                    return SwapAction(
                        fromIndex = currIdx,
                        toIndex = currIdx + 1,
                        swappedItemId = nextInstanceId,
                        direction = 1
                    )
                }
            }
        }
    }

    return null
}

fun <T> applyTargetSwap(
    currentId: String,
    localItems: MutableList<T>,
    idSelector: (T) -> String,
    visibleSongItems: List<PlaylistReorderItemInfo>,
    pointerY: Float,
    grabOffsetY: Float,
    fallbackItemHeight: Float,
    swapHysteresisPx: Float,
    reorderState: PlaylistDragReorderState,
    onHaptic: (() -> Unit)? = null
): Boolean {
    val action = evaluateTargetSwap(
        currentId = currentId,
        localItemIds = localItems.map(idSelector),
        visibleSongItems = visibleSongItems,
        pointerY = pointerY,
        grabOffsetY = grabOffsetY,
        fallbackItemHeight = fallbackItemHeight,
        swapHysteresisPx = swapHysteresisPx,
        reorderState = reorderState
    ) ?: return false

    java.util.Collections.swap(localItems, action.fromIndex, action.toIndex)
    reorderState.lastSwappedItemId = action.swappedItemId
    reorderState.lastSwapDirection = action.direction
    onHaptic?.invoke()
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
private data class PlaylistTrackItem(
    val instanceId: String,
    val track: Track
)

@Composable
private fun PlaylistDetailView(
    playlist: Playlist,
    savedAlbums: List<SavedAlbum> = emptyList(),
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
    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    val haptic = LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var showOptionsMenu by remember { mutableStateOf(false) }
    var initialMenuDialog by remember { mutableStateOf<PlaylistDialogType?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(PlaylistSortOption.CUSTOM) }
    var showSortMenu by remember { mutableStateOf(false) }

    val validPlaylistTracks = remember(playlist.tracks) {
        playlist.tracks.filter { !it.title.startsWith("Track ") && it.title.isNotBlank() }
    }

    val isCustomSort = sortOption == PlaylistSortOption.CUSTOM && searchQuery.isBlank() && !playlist.id.startsWith("smart_")
    // Custom order is locked until the lock beside "Custom order" is opened: only then do the
    // drag handles show and a held row drag. Locked, holding a song opens its menu.
    var orderUnlocked by remember(playlist.id) { mutableStateOf(false) }
    val canReorder = isCustomSort && orderUnlocked
    val localItems = remember(playlist.id) {
        androidx.compose.runtime.mutableStateListOf<PlaylistTrackItem>()
    }

    var draggingInstanceId by remember { mutableStateOf<String?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var originalDragIndex by remember { mutableStateOf(-1) }
    var currentPointerY by remember { mutableStateOf(0f) }
    var grabOffsetY by remember { mutableStateOf(0f) }
    var listTopInRoot by remember { mutableStateOf(0f) }
    val reorderState = remember { PlaylistDragReorderState() }
    // playlistListState preserved for scroll position retention
    val playlistListState = androidx.compose.runtime.saveable.rememberSaveable(
        playlist.id,
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) {
        androidx.compose.foundation.lazy.LazyListState()
    }

    LaunchedEffect(validPlaylistTracks) {
        if (localItems.isNotEmpty() && !isDragging && draggingInstanceId == null) {
            val tracksDiffer = localItems.size != validPlaylistTracks.size ||
                    localItems.indices.any { localItems[it].track.id != validPlaylistTracks[it].id }
            if (tracksDiffer) {
                localItems.clear()
                localItems.addAll(
                    validPlaylistTracks.mapIndexed { index, track ->
                        PlaylistTrackItem(
                            instanceId = "${track.id}_$index",
                            track = track
                        )
                    }
                )
            }
        }
    }

    fun checkTargetSwap(pointerY: Float) {
        val currentId = draggingInstanceId ?: return
        val visibleSongItems = playlistListState.layoutInfo.visibleItemsInfo
            .filter { it.contentType == "song" }
            .map { PlaylistReorderItemInfo(key = it.key, offset = it.offset, size = it.size) }
        if (visibleSongItems.isEmpty()) return

        val firstVisibleIndex = playlistListState.firstVisibleItemIndex
        val firstVisibleOffset = playlistListState.firstVisibleItemScrollOffset
        val firstVisibleKey = playlistListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key

        val swapped = applyTargetSwap(
            currentId = currentId,
            localItems = localItems,
            idSelector = { it.instanceId },
            visibleSongItems = visibleSongItems,
            pointerY = pointerY,
            grabOffsetY = grabOffsetY,
            fallbackItemHeight = density.run { 56.dp.toPx() },
            swapHysteresisPx = density.run { 10.dp.toPx() },
            reorderState = reorderState,
            onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        )
        // LazyColumn keeps the first visible item's *key* on screen. When that item is one of the
        // swapped pair (dragging near the top), the whole list shifts a row instead, every row's
        // placement animation restarts, and during auto-scroll they never catch up: rows overlap
        // and bounce. Pin the index instead, as the queue's reorder library does.
        if (swapped && (firstVisibleKey == currentId || firstVisibleKey == reorderState.lastSwappedItemId)) {
            playlistListState.requestScrollToItem(firstVisibleIndex, firstVisibleOffset)
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
            val isFirstTrackAtTop = firstSongInfo != null && firstSongInfo.key == localItems.firstOrNull()?.instanceId && firstSongInfo.offset >= 0

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

    val displayedItems: List<PlaylistTrackItem> = remember(isCustomSort, localItems.size, sortOption, validPlaylistTracks, searchQuery) {
        if (isCustomSort && localItems.isNotEmpty()) {
            localItems
        } else {
            val sortedTracks = when (sortOption) {
                PlaylistSortOption.CUSTOM -> validPlaylistTracks
                PlaylistSortOption.NEWEST -> validPlaylistTracks.reversed()
                PlaylistSortOption.OLDEST -> validPlaylistTracks
                PlaylistSortOption.ALPHABETICAL -> validPlaylistTracks.sortedBy { it.title.lowercase() }
                PlaylistSortOption.BY_ARTIST -> validPlaylistTracks.sortedBy { it.artist.lowercase() }
            }
            val searchFiltered = if (searchQuery.isBlank()) {
                sortedTracks
            } else {
                sortedTracks.filter {
                    it.title.contains(searchQuery, ignoreCase = true) ||
                    it.artist.contains(searchQuery, ignoreCase = true)
                }
            }
            searchFiltered.mapIndexed { index, track ->
                PlaylistTrackItem(instanceId = "${track.id}_$index", track = track)
            }
        }
    }

    val displayedTracks = remember(displayedItems) { displayedItems.map { it.track } }
    val onPlayTrackWithList: (Track) -> Unit = remember(displayedTracks) {
        { track -> onPlayTrack(track, displayedTracks) }
    }

    val durationFormatted = remember(playlist.tracks) {
        val totalSeconds = playlist.tracks.sumOf { it.duration }
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    com.auralis.music.ui.components.PlatformBackHandler(enabled = true) {
        if (showOptionsMenu) {
            showOptionsMenu = false
        } else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else {
            onBack()
        }
    }

    // Liquid glass: the cover fills the top of the page edge to edge and fades into it, with
    // the back / search buttons floating over it. Searching collapses the header and puts the
    // buttons and search field in a plain bar.
    val glassHero = com.auralis.music.ui.glass.LocalLiquidGlass.current != null
    val heroMode = glassHero && !isSearchActive
    // The hero cover is recorded on its own, so the buttons over and under it can render it as
    // liquid glass (the page's own backdrop can't be used here: these buttons are part of it).
    val heroBackdrop = rememberLayerBackdrop()
    val heroGlass = com.auralis.music.ui.components.rememberPageHeaderGlass(heroBackdrop)?.takeIf { heroMode }
    val topBarButton: Modifier = if (heroGlass != null) {
        Modifier.liquidGlass(heroGlass, CircleShape)
    } else Modifier

    // Liquid glass: the bar always floats in the same place, so opening search never moves the
    // buttons. It fades in a solid page-coloured backing while searching, and the list makes room
    // under it with a gap that grows as the header folds away.
    val searchBarBacking by animateFloatAsState(if (glassHero && isSearchActive) 1f else 0f, tween(260), label = "searchBarBacking")
    val searchBarGap by animateDpAsState(if (glassHero && isSearchActive) 58.dp else 0.dp, tween(340, easing = FastOutSlowInEasing), label = "searchBarGap")
    val pageColor = MaterialTheme.dynamicBackground
    val topBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind { if (searchBarBacking > 0f) drawRect(pageColor.copy(alpha = searchBarBacking)) }
                .padding(start = 8.dp, end = 8.dp, top = if (glassHero) 6.dp else 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.tactileBounce(scaleDown = 0.88f).then(topBarButton)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = str(Strings.back),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            // The search field grows out of the search button.
            AnimatedVisibility(
                visible = isSearchActive,
                enter = fadeIn(tween(180, delayMillis = 60)) +
                    expandHorizontally(tween(320, easing = FastOutSlowInEasing), expandFrom = Alignment.End),
                exit = fadeOut(tween(120)) +
                    shrinkHorizontally(tween(260, easing = FastOutSlowInEasing), shrinkTowards = Alignment.End),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            ) {
                PlaylistSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    resultCount = if (searchQuery.isBlank()) null else displayedItems.size
                )
            }
            IconButton(
                onClick = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) searchQuery = ""
                },
                modifier = Modifier.tactileBounce(scaleDown = 0.88f).then(topBarButton)
            ) {
                Icon(
                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = str(Strings.search),
                    tint = if (isSearchActive) LIME_TEXT else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ================================================================
        // 1. TOP APP BAR: Back Arrow (Left) + Search Icon (Right)
        // ================================================================
        if (!glassHero) {
            BoxWithConstraints { if (maxWidth < PLAYLIST_TABLE_MIN_WIDTH) topBar() }
        }

        // Opening search brings the list to the top, where the collapsed header was.
        LaunchedEffect(isSearchActive) {
            if (isSearchActive) playlistListState.animateScrollToItem(0)
        }

        // ================================================================
        // 2. MAIN SCROLLABLE BODY
        // ================================================================
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Songs are laid out as a table (title, artist, album, length) down to fairly narrow
            // windows, so the page keeps one format when the window is made smaller.
            val wideTable = maxWidth >= PLAYLIST_TABLE_MIN_WIDTH
            // The songs are the list's last items.
            com.auralis.music.ui.components.PrefetchListArtwork(playlistListState) { index ->
                val first = playlistListState.layoutInfo.totalItemsCount - displayedItems.size
                displayedItems.getOrNull(index - first)?.track?.let { it.thumbnail to it }
            }
            LazyColumn(
                state = playlistListState,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { listTopInRoot = it.positionInRoot().y },
                contentPadding = bottomChromePadding()
            ) {
                if (glassHero && !wideTable) {
                    item(key = "search_bar_gap", contentType = "gap") { Spacer(Modifier.height(searchBarGap)) }
                }
                // Header Content
                item(key = "playlist_header", contentType = "header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (wideTable) 0.dp else 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (wideTable) DesktopPlaylistHero(
                            playlist, savedAlbums,
                            "${playlist.tracks.size} tracks • $durationFormatted",
                            searchQuery, { searchQuery = it },
                            onPlay = { displayedTracks.firstOrNull()?.let { onPlayTrack(it, displayedTracks) } },
                            onShuffle = { displayedTracks.shuffled().let { tracks -> tracks.firstOrNull()?.let { onPlayTrack(it, tracks) } } },
                            onOptions = { showOptionsMenu = true }
                        )
                        // Searching folds the cover, title and buttons away so results sit right under
                        // the search bar; closing search unfolds them again.
                        AnimatedVisibility(
                            visible = !isSearchActive && !wideTable,
                            enter = expandVertically(tween(380, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                                fadeIn(tween(260, delayMillis = 80)),
                            exit = shrinkVertically(tween(340, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
                                fadeOut(tween(180)) +
                                scaleOut(tween(340, easing = FastOutSlowInEasing), targetScale = 0.92f,
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)),
                            // Spans the full screen width: the animation clips to its own bounds, and
                            // inside the page's 24dp padding it cut the edge-to-edge cover short,
                            // leaving a dark bar down each side.
                            modifier = Modifier.layout { measurable, constraints ->
                                val side = 24.dp.roundToPx()
                                val placeable = measurable.measure(
                                    constraints.copy(
                                        minWidth = constraints.minWidth + side * 2,
                                        maxWidth = constraints.maxWidth + side * 2
                                    )
                                )
                                layout(constraints.maxWidth, placeable.height) { placeable.place(-side, 0) }
                            }
                        ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                        // Centered Hero Cover (210dp x 210dp) with Custom Cover or 4-Quadrant Collage.
                        // Liquid glass: full-bleed square cover fading into the page instead.
                        val heroOverlapPx = with(LocalDensity.current) { 72.dp.roundToPx() }
                        Box(
                            modifier = if (glassHero) {
                                Modifier
                                    .layout { measurable, constraints ->
                                        val sidePad = 24.dp.roundToPx()
                                        val width = constraints.maxWidth + sidePad * 2
                                        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, width))
                                        // Reports less height than it draws, so the title sits on the faded bottom.
                                        layout(constraints.maxWidth, (width - heroOverlapPx).coerceAtLeast(0)) {
                                            placeable.place(-sidePad, 0)
                                        }
                                    }
                                    .background(CARD_DARK_BG)
                                    .layerBackdrop(heroBackdrop)
                            } else Modifier
                                .size(210.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(CARD_DARK_BG)
                        ) {
                            PlaylistCoverArt(playlist, savedAlbums, cornerRadius = if (glassHero) 0.dp else 18.dp)

                            if (glassHero) {
                                val pageBackground = MaterialTheme.dynamicBackground
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.verticalGradient(
                                                0f to Color.Black.copy(alpha = 0.30f),
                                                0.22f to Color.Transparent,
                                                0.55f to Color.Transparent,
                                                1f to pageBackground
                                            )
                                        )
                                )
                            }

                            // Edit Pencil Overlay in bottom right corner (Opens Edit Photo & Name dialog)
                            if (!playlist.id.startsWith("smart_")) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(
                                            end = if (glassHero) 20.dp else 8.dp,
                                            bottom = if (glassHero) 84.dp else 8.dp,
                                            start = 8.dp, top = 8.dp
                                        )
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.75f))
                                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                        .clickable {
                                            initialMenuDialog = PlaylistDialogType.EDIT
                                            showOptionsMenu = true
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = str(Strings.edit_playlist_photo_and_name),
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(if (glassHero) 0.dp else 16.dp))

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
                                text = playlist.description!!,
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
                                    .then(
                                        if (heroGlass != null) Modifier.liquidGlass(heroGlass, CircleShape)
                                        else Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
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
                                    contentDescription = str(Strings.shuffle),
                                    tint = if (heroGlass != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // Large Play Button
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .then(
                                        // Glass tinted with the accent, so play still reads as the main action.
                                        if (heroGlass != null) Modifier.liquidGlass(
                                            heroGlass, CircleShape,
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.62f)
                                        )
                                        else Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                                    )
                                    .clickable {
                                        if (displayedTracks.isNotEmpty()) {
                                            onPlayTrack(displayedTracks.first(), displayedTracks)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = str(Strings.play),
                                    tint = if (heroGlass != null) Color.White else MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // 3-Dots Options Button
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .then(
                                        if (heroGlass != null) Modifier.liquidGlass(heroGlass, CircleShape)
                                        else Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                    .clickable { showOptionsMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = str(Strings.playlist_options),
                                    tint = if (heroGlass != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        }
                        }

                        // ========================================================
                        // Sort Order Dropdown Menu
                        // ========================================================
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (wideTable) 24.dp else 4.dp, vertical = 6.dp),
                            horizontalArrangement = if (wideTable) Arrangement.End else Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Sort Order Dropdown
                            Box {
                                // Liquid glass: the same glass popup as long-pressing a song or playlist.
                                val sortGlass = com.auralis.music.ui.glass.LocalLiquidGlass.current
                                val sortAnchor = remember { com.auralis.music.ui.components.ContextMenuAnchor() }
                                val sortScope = rememberCoroutineScope()
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(if (sortGlass != null) Modifier.tapMenuAnchor(sortAnchor) else Modifier)
                                        .clickable {
                                            if (sortGlass != null) sortAnchor.captureForTap(sortScope)
                                            showSortMenu = true
                                        }
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
                                        contentDescription = str(Strings.sort_options),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                if (sortGlass != null) {
                                    if (showSortMenu) {
                                        val tick = MaterialTheme.colorScheme.primary
                                        GlassContextMenu(
                                            anchor = sortAnchor,
                                            items = PlaylistSortOption.values().map { option ->
                                                GlassMenuItem(
                                                    label = com.auralis.music.ui.i18n.UiLabels.of(option.label),
                                                    icon = if (sortOption == option) Icons.Default.Check else null,
                                                    tint = if (sortOption == option) tick else null
                                                ) { sortOption = option }
                                            },
                                            onDismiss = { showSortMenu = false }
                                        )
                                    }
                                } else DropdownMenu(
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
                                                    text = com.auralis.music.ui.i18n.UiLabels.of(option.label),
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

                            if (isCustomSort) {
                                val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
                                IconButton(
                                    onClick = {
                                        orderUnlocked = !orderUnlocked
                                        platformActions.showMessage(if (orderUnlocked) str(Strings.playlist_order_unlocked) else str(Strings.playlist_order_locked))
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (orderUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                        contentDescription = if (orderUnlocked) str(Strings.lock_playlist_order) else str(Strings.unlock_playlist_order),
                                        tint = if (orderUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ================================================================
                // 4. TRACKS LIST
                // ================================================================
                if (wideTable && displayedItems.isNotEmpty()) item(key = "track_table_header", contentType = "header") {
                    // Cover (48) + gap (14) before the title; the menu button (48) after the clock.
                    com.auralis.music.ui.components.TrackTableHeader(leading = 62.dp, trailing = 48.dp, showNumber = false,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
                itemsIndexed(
                    items = displayedItems,
                    key = { _, item -> item.instanceId },
                    contentType = { _, _ -> "song" }
                ) { _, item ->
                    val track = item.track
                    val isCurrent = track.id == currentTrackId
                    val isItemBeingDragged = draggingInstanceId == item.instanceId

                    PlaylistTrackRow(
                        track = track,
                        wide = wideTable,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying,
                        isCustomSort = canReorder,
                        isItemBeingDragged = isItemBeingDragged,
                        isDragging = isDragging,
                        onPlayTrack = onPlayTrackWithList,
                        onMenuClick = onMenuClick,
                        onPlayNext = onPlayNextTrack,
                        onAddToQueue = onAddToQueueTrack,
                        listTopInRoot = listTopInRoot,
                        onReorderDragStart = { pointerY ->
                            if (localItems.isEmpty() || localItems.size != validPlaylistTracks.size) {
                                localItems.clear()
                                localItems.addAll(validPlaylistTracks.mapIndexed { index, song ->
                                    PlaylistTrackItem(instanceId = "${song.id}_$index", track = song)
                                })
                            }
                            val index = localItems.indexOfFirst { it.instanceId == item.instanceId }
                            val visibleItem = playlistListState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == item.instanceId }
                            if (index >= 0 && visibleItem != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                originalDragIndex = index
                                draggingInstanceId = item.instanceId
                                grabOffsetY = pointerY - visibleItem.offset
                                currentPointerY = pointerY
                                reorderState.reset()
                                isDragging = true
                            }
                        },
                        onReorderDrag = { deltaY ->
                            if (isDragging) {
                                currentPointerY += deltaY
                                checkTargetSwap(currentPointerY)
                            }
                        },
                        onReorderDragEnd = {
                            val finalIndex = localItems.indexOfFirst { it.instanceId == draggingInstanceId }
                            val startIndex = originalDragIndex
                            isDragging = false
                            draggingInstanceId = null
                            originalDragIndex = -1
                            reorderState.reset()
                            if (startIndex >= 0 && finalIndex >= 0 && startIndex != finalIndex) {
                                onReorderTracks?.invoke(startIndex, finalIndex)
                            }
                        },
                        onReorderDragCancel = {
                            localItems.clear()
                            localItems.addAll(validPlaylistTracks.mapIndexed { index, song ->
                                PlaylistTrackItem(instanceId = "${song.id}_$index", track = song)
                            })
                            isDragging = false
                            draggingInstanceId = null
                            originalDragIndex = -1
                            reorderState.reset()
                        },
                        onRemoveFromPlaylist = { trackId ->
                            localItems.removeAll { it.track.id == trackId }
                            onRemoveTrack(trackId)
                        }
                    )
                }
            }

            // ================================================================
            // FLOATING DRAGGED CARD OVERLAY
            // ================================================================
            val draggedTrack = localItems.find { it.instanceId == draggingInstanceId }?.track
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
                ) {
                    // The dragged row looks exactly like a normal row (no card, border, lift or
                    // accent): it just follows the finger. Painted with the page background so it
                    // doesn't show through the rows it passes over.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.dynamicBackground)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkCard(
                            sizeToConstraints = true,
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
                                contentDescription = str(Strings.drag_to_reorder_song),
                                tint = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = str(Strings.options),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    // Floats over the hero cover in liquid glass mode.
    if (glassHero) BoxWithConstraints { if (maxWidth < PLAYLIST_TABLE_MIN_WIDTH) topBar() }
    // The song-table layout: Back floats over the page (as on album and artist pages), so it
    // stays reachable however far down the songs are scrolled.
    BoxWithConstraints {
        if (maxWidth >= PLAYLIST_TABLE_MIN_WIDTH) IconButton(onClick = onBack,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp).size(40.dp).clip(CircleShape)
                .background(Color(0xFF202024).copy(alpha = .72f))
                .border(1.dp, Color.White.copy(alpha = .16f), CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, str(Strings.back), tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
    }

    if (showOptionsMenu) {
        PlaylistOptionsBottomSheet(
            playlist = playlist,
            initialDialog = initialMenuDialog,
            onDismiss = {
                showOptionsMenu = false
                initialMenuDialog = null
            },
            onEditPlaylist = onEditPlaylist,
            onAddToQueue = onAddToQueue,
            onDeletePlaylist = {
                showOptionsMenu = false
                initialMenuDialog = null
                onDeletePlaylist()
            },
            onClearAllDownloads = {
                showOptionsMenu = false
                initialMenuDialog = null
                platformActions.clearAllDownloads()
            }
        )
    }
}

// ============================================================================
// 📑 PLAYLIST OPTIONS BOTTOM SHEET & DIALOGS
// ============================================================================


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun androidx.compose.foundation.lazy.LazyItemScope.PlaylistTrackRow(
    track: Track,
    wide: Boolean = false,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isCustomSort: Boolean,
    isItemBeingDragged: Boolean,
    isDragging: Boolean,
    onPlayTrack: (Track) -> Unit,
    onMenuClick: (Track) -> Unit,
    onPlayNext: ((Track) -> Unit)?,
    onAddToQueue: ((Track) -> Unit)?,
    listTopInRoot: Float,
    onReorderDragStart: (Float) -> Unit,
    onReorderDrag: (Float) -> Unit,
    onReorderDragEnd: () -> Unit,
    onReorderDragCancel: () -> Unit,
    onRemoveFromPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackMin = track.duration / 60
    val trackSec = track.duration % 60
    val trackDurationStr = remember(track.duration) { "$trackMin:${if (trackSec < 10) "0" else ""}$trackSec" }
    val subtitleText = remember(track.artist, track.duration) {
        if (track.duration > 0 && track.duration != 210L) {
            "${track.artist} • $trackDurationStr"
        } else {
            track.artist
        }
    }
    var handleTopInRoot by remember { mutableStateOf(0f) }
    val currentListTop by androidx.compose.runtime.rememberUpdatedState(listTopInRoot)
    val currentDragStart by androidx.compose.runtime.rememberUpdatedState(onReorderDragStart)
    val currentDrag by androidx.compose.runtime.rememberUpdatedState(onReorderDrag)
    val currentDragEnd by androidx.compose.runtime.rememberUpdatedState(onReorderDragEnd)
    val currentDragCancel by androidx.compose.runtime.rememberUpdatedState(onReorderDragCancel)

    com.auralis.music.ui.components.SwipeableTrackContainer(
        onPlayNext = onPlayNext?.let { { it(track) } },
        onAddToQueue = onAddToQueue?.let { { it(track) } },
        onRemoveFromPlaylist = { onRemoveFromPlaylist(track.id) },
        isPlaylistContext = true,
        modifier = modifier
            .then(
                if (isDragging && !isItemBeingDragged) {
                    Modifier.animateItemPlacement(
                        animationSpec = tween(
                            durationMillis = 100,
                            easing = LinearOutSlowInEasing
                        )
                    )
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .then(
                // Hidden (not ghosted) while its copy is being dragged, so it reads as the row moving.
                if (isItemBeingDragged) {
                    Modifier.graphicsLayer { alpha = 0f }
                } else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!isCustomSort) Modifier.contextMenuAnchor { onPlayTrack(track) } else Modifier)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = {
                        if (!isDragging) {
                            onPlayTrack(track)
                        }
                    },
                    // While unlocked, only the handle owns a long press. Other row areas
                    // retain their normal tap behavior without starting a reorder.
                    onLongClick = if (!isCustomSort) { { onMenuClick(track) } } else null
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkCard(
                sizeToConstraints = true,
                url = track.thumbnail,
                modifier = Modifier.size(48.dp),
                cornerRadius = 8.dp,
                contentDescription = track.title,
                fallbackTrack = track
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(if (wide) 1.5f else 1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) LIME_TEXT else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!wide) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (wide) {
                com.auralis.music.ui.components.TrackLinkCellsRow(this, track)
                com.auralis.music.ui.components.TrackTimeCell(track.duration.takeIf { it != 210L } ?: 0L)
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
                    modifier = Modifier
                        .size(36.dp)
                        .onGloballyPositioned { handleTopInRoot = it.positionInRoot().y }
                        .pointerInput(isCustomSort) {
                            // Desktop handles start on mouse movement, before the list
                            // can turn it into scrolling while waiting for a long press.
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentDragStart(handleTopInRoot - currentListTop + offset.y)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    currentDrag(amount.y)
                                },
                                onDragEnd = { currentDragEnd() },
                                onDragCancel = { currentDragCancel() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = str(Strings.drag_to_reorder_song),
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            IconButton(onClick = { onMenuClick(track) }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = str(Strings.options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Library pill filters. No pill selected shows everything. */
enum class LibrarySourceFilter { PLAYLISTS, ALBUMS, ARTISTS, SPOTIFY, YOUTUBE }



@Composable
private fun LibrarySourcePills(
    selected: LibrarySourceFilter?,
    glass: LiquidGlassContext? = null,
    horizontalPadding: Dp = 16.dp,
    onSelect: (LibrarySourceFilter) -> Unit
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 4.dp, bottom = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(LibrarySourceFilter.entries.toList(), key = { it.name }) { filter ->
            val isSelected = filter == selected
            val label = when (filter) {
                LibrarySourceFilter.PLAYLISTS -> "Playlists"
                LibrarySourceFilter.ALBUMS -> "Albums"
                LibrarySourceFilter.ARTISTS -> "Artists"
                LibrarySourceFilter.SPOTIFY -> str(Strings.spotify)
                LibrarySourceFilter.YOUTUBE -> "YouTube Music"
            }
            val container = if (isSelected) LIME_TEXT else MaterialTheme.colorScheme.surfaceContainerHigh
            val content = if (glass != null) MaterialTheme.colorScheme.onSurface
                else if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val reducedMotion = LocalReducedMotion.current
            val pressFill = androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (pressed) 1f else 0f,
                animationSpec = if (reducedMotion) androidx.compose.animation.core.snap()
                    else androidx.compose.animation.core.tween(
                        durationMillis = if (pressed) 450 else 220,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                label = "libraryPillPressFill"
            )
            val pillModifier = if (glass != null) {
                val glassModifier = if (isSelected) Modifier.liquidGlass(glass, CircleShape, tint = LIME_TEXT.copy(alpha = 0.24f))
                    else Modifier.liquidGlass(glass, CircleShape)
                // Draw the pressed fill with the actual outline. The platform
                // ripple can ignore the glass layer's clip and paint a rectangle.
                glassModifier.drawWithContent {
                    drawContent()
                    if (pressFill.value > 0f) drawOutline(
                        CircleShape.createOutline(size, layoutDirection, this),
                        content.copy(alpha = 0.12f * pressFill.value)
                    )
                }
            } else Modifier.clip(CircleShape)
            androidx.compose.material3.Surface(
                modifier = pillModifier.clickable(
                    interactionSource = interactionSource,
                    indication = if (glass != null) null else LocalIndication.current,
                    role = Role.Button,
                    onClick = { onSelect(filter) }
                ),
                shape = CircleShape,
                color = if (glass != null) Color.Transparent else container,
                border = if (glass != null || isSelected) null else androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    when (filter) {
                        LibrarySourceFilter.PLAYLISTS -> Icon(
                            Icons.AutoMirrored.Filled.QueueMusic, null, tint = content, modifier = Modifier.size(18.dp)
                        )
                        LibrarySourceFilter.ALBUMS -> Icon(
                            Icons.Rounded.Album, null, tint = content, modifier = Modifier.size(18.dp)
                        )
                        LibrarySourceFilter.ARTISTS -> Icon(Icons.Default.AccountCircle, null, tint = content, modifier = Modifier.size(18.dp))
                        LibrarySourceFilter.SPOTIFY -> com.auralis.music.ui.profile.SpotifyLogoIcon(Modifier.size(18.dp))
                        LibrarySourceFilter.YOUTUBE -> YouTubeMusicMark(Modifier.size(18.dp))
                    }
                    Text(
                        text = label,
                        color = content,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** Red circle with a white play triangle, marking YouTube Music imports. */
@Composable
private fun YouTubeMusicMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        drawCircle(Color(0xFFFF0033))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.40f, w * 0.30f)
            lineTo(w * 0.72f, w * 0.50f)
            lineTo(w * 0.40f, w * 0.70f)
            close()
        }
        drawPath(path, Color.White)
    }
}

/**
 * The playlist's search pill: grows out of the search button, focuses itself so the keyboard
 * opens, lights its rim in the accent colour while focused, and shows how many songs match.
 */
@Composable
private fun PlaylistSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int?,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val onBg = MaterialTheme.colorScheme.onBackground
    val accent = MaterialTheme.colorScheme.primary
    val rim by animateColorAsState(if (focused) accent.copy(alpha = 0.75f) else onBg.copy(alpha = 0.10f), tween(200), label = "searchRim")
    val shape = RoundedCornerShape(50)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(onBg.copy(alpha = 0.08f))
            .border(1.dp, rim, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                runCatching { focusRequester.requestFocus() }
            }
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = if (focused) accent else onBg.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = str(Strings.search_in_playlist),
                    color = onBg.copy(alpha = 0.45f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = TextStyle(color = onBg, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(accent),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
            )
        }
        AnimatedVisibility(
            visible = resultCount != null,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120))
        ) {
            Text(
                text = (resultCount ?: 0).toString(),
                color = onBg.copy(alpha = 0.5f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.6f),
            exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.6f)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(onBg.copy(alpha = 0.14f))
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = str(Strings.clear),
                    tint = onBg.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/** What the Downloads hub slot gets from the Library screen. */
class DownloadsHubArgs(
    val downloadedTracks: List<Track>,
    val userPlaylists: List<Playlist>,
    val currentTrackId: String?,
    val isPlaying: Boolean,
    val onBack: () -> Unit,
    val onOpenFolder: (Playlist) -> Unit,
    val onPlayTracks: (Track, List<Track>) -> Unit,
    val onDeleteJob: (String) -> Unit,
    val onRetryJob: (String) -> Unit,
    val onClearAllDownloads: () -> Unit
)
