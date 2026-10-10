package com.auralis.music.ui.explore

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.auralisDetailBackwardEnter
import com.auralis.music.ui.theme.auralisDetailBackwardExit
import com.auralis.music.ui.theme.auralisDetailForwardEnter
import com.auralis.music.ui.theme.auralisDetailForwardExit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.SearchTopResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recognition.RecognitionMode
import com.auralis.music.domain.recognition.RecognitionState
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.search.VoiceAndMusicRecognitionModal
import com.auralis.music.ui.theme.GlassBorderHairline
import com.auralis.music.ui.screens.ArtistScreen
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.theme.auralisContentEnter
import com.auralis.music.ui.theme.auralisContentExit
import com.auralis.music.ui.theme.auralisIconSwapEnter
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.viewmodel.SearchUiState
import com.auralis.music.ui.components.bottomChromePadding

private enum class SearchBodyState { SEARCHING, RESULTS, SUGGESTIONS }

/**
 * Pure Jetpack Compose Search Screen matching the exact reference:
 * Minimalist top search bar ("Search Auralis...", Back arrow, Globe icon),
 * interactive history items with clock icon, clear cross, and diagonal insert arrow (↖),
 * live autocomplete suggestions, category filter pills, and bottom right voice / music recognition button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExploreScreen(
    uiState: SearchUiState,
    onRefresh: suspend () -> Unit = {},
    recognitionState: RecognitionState = RecognitionState(),
    currentTrackId: String?,
    isPlaying: Boolean,
    userPlaylists: List<Playlist> = emptyList(),
    favoriteTracks: List<Track> = emptyList(),
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onStartRadio: (Track) -> Unit = {},
    onRemoveRecentQuery: (String) -> Unit = {},
    onClearRecentQueries: () -> Unit = {},
    onOpenRecognition: (RecognitionMode) -> Unit = {},
    onCloseRecognition: () -> Unit = {},
    onModeSelect: (RecognitionMode) -> Unit = {},
    savedArtists: List<com.auralis.music.domain.model.SavedArtist> = emptyList(),
    savedAlbums: List<com.auralis.music.domain.model.SavedAlbum> = emptyList(),
    onToggleSubscribe: (com.auralis.music.domain.model.SavedArtist) -> Unit = {},
    onToggleSaveAlbum: (com.auralis.music.domain.model.SavedAlbum) -> Unit = {},
    onPlayNextAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddToQueueAlbum: ((PlaylistResult) -> Unit)? = null,
    onShuffleAlbum: ((PlaylistResult) -> Unit)? = null,
    onDownloadAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddAlbumToPlaylist: ((String, PlaylistResult) -> Unit)? = null,
    onCreatePlaylistAndAddAlbum: ((String, PlaylistResult) -> Unit)? = null,
    isAlbumPinned: ((String) -> Boolean)? = null,
    pinnedSpeedDialIds: Set<String> = emptySet(),
    onPinAlbumToSpeedDial: ((PlaylistResult) -> Unit)? = null,
    isTrackPinned: ((String) -> Boolean)? = null,
    onPinTrackToSpeedDial: ((Track) -> Unit)? = null,
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {},
    onCloseArtist: () -> Unit = {},
    onOpenAlbum: (PlaylistResult) -> Unit = {},
    onCloseAlbum: () -> Unit = {},
    onAlbumClick: (PlaylistResult) -> Unit = {},
    onBack: () -> Unit = {},
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // The search results UI is shared with the Windows app; these are the phone's own pieces of it.
    val phoneSearchUi = remember {
        com.auralis.music.ui.search.SearchUiPlatform(
            artwork = { url, modifier, cornerRadius, contentDescription ->
                ArtworkCard(sizeToConstraints = true, url = url, modifier = modifier, cornerRadius = cornerRadius, contentDescription = contentDescription)
            },
            nowPlayingIndicator = { isPlaying, modifier, color -> EqualizerBars(isPlaying = isPlaying, modifier = modifier, color = color) },
            strings = com.auralis.music.ui.search.SearchUiStrings(
                albumBy = { str(R.string.album_x, it) },
                songBy = { str(R.string.song_x, it) },
                variousArtists = { str(R.string.various_artists) },
                single = { str(R.string.single) },
                artist = { str(R.string.artist) },
                options = { str(R.string.options) }
            ),
            trackRowContainer = { onPlayNext, onAddToQueue, content ->
                com.auralis.music.ui.components.SwipeableTrackContainer(onPlayNext = onPlayNext, onAddToQueue = onAddToQueue, content = content)
            }
        )
    }
    val themePrimary = MaterialTheme.dynamicPrimary
    val themeBackground = MaterialTheme.dynamicBackground
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val retryScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    val handleTrackClick: (Track, List<Track>) -> Unit = { track, list ->
        if (!uiState.isSearching) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onTrackClick(track, list)
        }
    }

    val handleOpenArtist: (Artist) -> Unit = { artist ->
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onOpenArtist(artist)
    }

    val handleOpenAlbum: (PlaylistResult) -> Unit = { album ->
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onOpenAlbum(album)
    }

    val handleAlbumClick: (PlaylistResult) -> Unit = { album ->
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onAlbumClick(album)
    }

    val detailForwardEnter = auralisDetailForwardEnter()
    val detailForwardExit = auralisDetailForwardExit()
    val detailBackwardEnter = auralisDetailBackwardEnter()
    val detailBackwardExit = auralisDetailBackwardExit()

    val currentDetail = uiState.detailStack.lastOrNull() ?: when {
        uiState.selectedArtistPage != null -> com.auralis.music.ui.viewmodel.ExploreDetail.Artist(uiState.selectedArtistPage, uiState.isLoadingArtist)
        uiState.selectedAlbum != null -> com.auralis.music.ui.viewmodel.ExploreDetail.Album(uiState.selectedAlbum, uiState.selectedAlbumTracks, uiState.isLoadingAlbum)
        else -> null
    }

    // ── PERF FIX #1: Extract stable navigation identity from ExploreDetail ──
    val detailKey: String? = currentDetail?.key

    LaunchedEffect(detailKey) {
        if (detailKey != null) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    // ── STATE PRESERVATION: SaveableStateHolder keeps each screen's saveable
    // state (scroll position, expanded sections, etc.) alive even when
    // AnimatedContent removes the composable from the tree. ──
    val saveableStateHolder = rememberSaveableStateHolder()

    // Cache of recent details by key so exiting composables render their genuine content
    val detailMap = remember { mutableMapOf<String, com.auralis.music.ui.viewmodel.ExploreDetail>() }
    val depthMap = remember { mutableMapOf<String?, Int>() }
    depthMap[null] = 0

    uiState.detailStack.forEachIndexed { index, detail ->
        detailMap[detail.key] = detail
        depthMap[detail.key] = index + 1
    }
    if (uiState.selectedArtistPage != null) {
        val d = com.auralis.music.ui.viewmodel.ExploreDetail.Artist(uiState.selectedArtistPage, uiState.isLoadingArtist)
        detailMap[d.key] = d
        if (!depthMap.containsKey(d.key)) depthMap[d.key] = 1
    }
    if (uiState.selectedAlbum != null) {
        val d = com.auralis.music.ui.viewmodel.ExploreDetail.Album(uiState.selectedAlbum, uiState.selectedAlbumTracks, uiState.isLoadingAlbum)
        detailMap[d.key] = d
        if (!depthMap.containsKey(d.key)) depthMap[d.key] = 2
    }

    AnimatedContent(
        targetState = detailKey,
        transitionSpec = {
            val initialDepth = depthMap[initialState] ?: 0
            val targetDepth = depthMap[targetState] ?: 0
            val isNavigatingBack = targetDepth < initialDepth || (targetState == null && initialState != null)

            if (isNavigatingBack) {
                detailBackwardEnter togetherWith detailBackwardExit
            } else {
                detailForwardEnter togetherWith detailForwardExit
            }
        },
        label = "SearchDetailTransition"
    ) { targetKey ->
        val renderedDetail = if (targetKey != null) {
            uiState.detailStack.lastOrNull { it.key == targetKey } ?: detailMap[targetKey]
        } else null

        // Wrap each branch in SaveableStateProvider so scroll state survives navigation
        saveableStateHolder.SaveableStateProvider(targetKey ?: "explore_root") {
            com.auralis.music.ui.components.AuralisRefreshBox(refresh = onRefresh) {
                when (renderedDetail) {
                    is com.auralis.music.ui.viewmodel.ExploreDetail.Artist -> {
                        ArtistScreen(
                            artistPage = renderedDetail.artistPage,
                            isLoading = renderedDetail.isLoading,
                            loadFailed = renderedDetail.loadFailed,
                            onRetry = { retryScope.launch { onRefresh() } },
                            currentTrackId = currentTrackId,
                            isPlaying = isPlaying,
                            userPlaylists = userPlaylists,
                            favoriteTracks = favoriteTracks,
                            savedArtists = savedArtists,
                            savedAlbums = savedAlbums,
                            onToggleSubscribe = onToggleSubscribe,
                            onToggleSaveAlbum = onToggleSaveAlbum,
                            onTrackClick = handleTrackClick,
                            onFavoriteToggle = onFavoriteToggle,
                            onAddToPlaylist = onAddToPlaylist,
                            onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onPlayNextAlbum = onPlayNextAlbum,
                            onAddToQueueAlbum = onAddToQueueAlbum,
                            onShuffleAlbum = onShuffleAlbum,
                            onDownloadAlbum = onDownloadAlbum,
                            onAddAlbumToPlaylist = onAddAlbumToPlaylist,
                            onCreatePlaylistAndAddAlbum = onCreatePlaylistAndAddAlbum,
                            isAlbumPinned = isAlbumPinned,
                            pinnedSpeedDialIds = pinnedSpeedDialIds,
                            onPinAlbumToSpeedDial = onPinAlbumToSpeedDial,
                            isTrackPinned = isTrackPinned,
                            onPinTrackToSpeedDial = onPinTrackToSpeedDial,
                            onStartRadio = onStartRadio,
                            onOpenArtist = handleOpenArtist,
                            onAlbumClick = handleOpenAlbum,
                            onBack = onCloseArtist,
                            isInListenTogetherRoom = isInListenTogetherRoom,
                            onRecommendToRoom = onRecommendToRoom,
                            modifier = modifier
                        )
                    }
                    is com.auralis.music.ui.viewmodel.ExploreDetail.Album -> {
                        com.auralis.music.ui.screens.AlbumScreen(
                            album = renderedDetail.album,
                            tracks = renderedDetail.tracks,
                            isLoading = renderedDetail.isLoading,
                            currentTrackId = currentTrackId,
                            isPlaying = isPlaying,
                            userPlaylists = userPlaylists,
                            favoriteTracks = favoriteTracks,
                            savedAlbums = savedAlbums,
                            onToggleSaveAlbum = onToggleSaveAlbum,
                            onPlayNextAlbum = onPlayNextAlbum,
                            onAddToQueueAlbum = onAddToQueueAlbum,
                            onShuffleAlbum = onShuffleAlbum,
                            onDownloadAlbum = onDownloadAlbum,
                            onAddAlbumToPlaylist = onAddAlbumToPlaylist,
                            onCreatePlaylistAndAddAlbum = onCreatePlaylistAndAddAlbum,
                            isAlbumPinned = isAlbumPinned,
                            pinnedSpeedDialIds = pinnedSpeedDialIds,
                            onPinAlbumToSpeedDial = onPinAlbumToSpeedDial,
                            isTrackPinned = isTrackPinned,
                            onPinTrackToSpeedDial = onPinTrackToSpeedDial,
                            onTrackClick = handleTrackClick,
                            onFavoriteToggle = onFavoriteToggle,
                            onAddToPlaylist = onAddToPlaylist,
                            onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
                            onPlayNext = onPlayNext,
                            onAddToQueue = onAddToQueue,
                            onStartRadio = onStartRadio,
                            onOpenArtist = handleOpenArtist,
                            onBack = onCloseAlbum,
                            isInListenTogetherRoom = isInListenTogetherRoom,
                            onRecommendToRoom = onRecommendToRoom,
                            modifier = modifier
                        )
                    }
                    else -> {
                val hasResults = uiState.query.isNotBlank() && (
                    uiState.searchResults.songs.isNotEmpty() ||
                    uiState.searchResults.artists.isNotEmpty() ||
                    uiState.searchResults.playlists.isNotEmpty()
                )

                androidx.activity.compose.BackHandler(
                    enabled = uiState.isRecognitionOpen || uiState.query.isNotEmpty() || hasResults
                ) {
                    if (uiState.isRecognitionOpen) {
                        onCloseRecognition()
                    } else if (uiState.query.isNotEmpty() || hasResults) {
                        onClearSearch()
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }
                }

                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .background(themeBackground)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                            )
                        }
                ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // ================================================================
                // 1. TOP MINIMALIST SEARCH BAR (Back Arrow + Input + Globe Icon)
                // ================================================================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back Arrow Button
                    IconButton(
                        onClick = {
                            if (uiState.isRecognitionOpen) {
                                onCloseRecognition()
                            } else if (uiState.query.isNotEmpty() || hasResults) {
                                onClearSearch()
                                focusManager.clearFocus()
                            } else {
                                focusManager.clearFocus()
                                onBack()
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = str(R.string.back),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Search Input Field
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (uiState.query.isEmpty()) {
                            Text(
                                text = str(R.string.search_auralis),
                                style = TextStyle(
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            )
                        }

                        BasicTextField(
                            value = uiState.query,
                            onValueChange = onQueryChange,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                    onSearch(uiState.query)
                                }
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    }

                    // Dynamic Trailing Button: Clear Cross when query/results exist; else Microphone button for quick Speak & Search
                    val showClear = uiState.query.isNotEmpty() || hasResults
                    val searchIconEnter = auralisIconSwapEnter()
                    val searchIconExit = auralisContentExit()
                    AnimatedContent(
                        targetState = showClear,
                        transitionSpec = { searchIconEnter togetherWith searchIconExit },
                        label = "searchTrailingIcon"
                    ) { isClear ->
                        if (isClear) {
                            IconButton(
                                onClick = {
                                    onClearSearch()
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = str(R.string.clear_search),
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    onOpenRecognition(RecognitionMode.VOICE_SEARCH)
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = str(R.string.speak_to_search),
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // ================================================================
                // 2. SEARCH BODY: SPINNER | RESULTS | LIVE SUGGESTIONS
                // ================================================================
                val bodyState = when {
                    uiState.isSearching -> SearchBodyState.SEARCHING
                    uiState.hasSubmittedSearch -> SearchBodyState.RESULTS
                    else -> SearchBodyState.SUGGESTIONS
                }

                val bodyEnter = auralisContentEnter()
                val bodyExit = auralisContentExit()

                AnimatedContent(
                    targetState = bodyState,
                    transitionSpec = { bodyEnter togetherWith bodyExit using SizeTransform(clip = false) },
                    modifier = Modifier.fillMaxSize(),
                    label = "searchBodyTransition"
                ) { targetBody ->
                    val isCurrentTarget = targetBody == bodyState
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (!isCurrentTarget) {
                                    Modifier.pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                                } else Modifier
                            )
                    ) {
                        when (targetBody) {
                        SearchBodyState.SEARCHING -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 40.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        SearchBodyState.RESULTS -> {
                            androidx.compose.runtime.CompositionLocalProvider(com.auralis.music.ui.search.LocalSearchUi provides phoneSearchUi) {
                            com.auralis.music.ui.search.SearchResultsList(
                                contentPadding = bottomChromePadding(start = 16.dp, end = 16.dp, top = 8.dp),
                                results = uiState.searchResults,
                                query = uiState.query,
                                searchFailed = uiState.searchFailed,
                                onRetry = { onSearch(uiState.query) },
                                currentTrackId = currentTrackId,
                                isPlaying = isPlaying,
                                onTrackClick = handleTrackClick,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onMenuClick = { track -> selectedTrackForMenu = track },
                                onArtistClick = handleOpenArtist,
                                onPlaylistClick = handleOpenAlbum
                            )
                            }
                        }
                        SearchBodyState.SUGGESTIONS -> {
                            androidx.compose.runtime.CompositionLocalProvider(com.auralis.music.ui.search.LocalSearchUi provides phoneSearchUi) {
                            com.auralis.music.ui.search.SearchSuggestionsList(
                                query = uiState.query,
                                suggestions = uiState.suggestions,
                                liveSongs = uiState.liveSongRecommendations,
                                recentQueries = uiState.recentQueries,
                                currentTrackId = currentTrackId,
                                isPlaying = isPlaying,
                                contentPadding = bottomChromePadding(start = 16.dp, end = 16.dp, top = 8.dp),
                                onSearch = { text ->
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    onSearch(text)
                                },
                                onInsert = onQueryChange,
                                onRemoveRecent = onRemoveRecentQuery,
                                onTrackClick = handleTrackClick,
                                onPlayNext = onPlayNext,
                                onAddToQueue = onAddToQueue,
                                onMenuClick = { track -> selectedTrackForMenu = track }
                            )
                            }
                        }
                    }
                }
    }
    }
    }
    }
    }
            }
} // SaveableStateProvider
} // AnimatedContent

    // Options Menu
    selectedTrackForMenu?.let { track ->
        val isFav = favoriteTracks.any { it.id == track.id }
        val isPinned = isTrackPinned?.invoke(track.id) ?: pinnedSpeedDialIds.contains(track.id)
        TrackOptionsMenu(
            track = track,
            isFavorite = isFav,
            userPlaylists = userPlaylists,
            onToggleFavorite = { onFavoriteToggle(track) },
            onPlayNext = { onPlayNext(track) },
            onAddToQueue = { onAddToQueue(track) },
            onStartRadio = { onStartRadio(track) },
            isPinned = isPinned,
            onPinToSpeedDial = { onPinTrackToSpeedDial?.invoke(track) },
            onGoToArtist = { artistName ->
                onOpenArtist(Artist(id = "", name = artistName))
            },
            onGoToAlbum = { albumId, albumTitle, albumArtist, albumArt ->
                val cached = com.auralis.music.data.network.AlbumMetadataResolver.getCached(track.title, track.artist, track.album)
                onAlbumClick(
                    com.auralis.music.domain.model.PlaylistResult(
                        id = albumId ?: cached?.albumId ?: "album-${track.id}",
                        title = albumTitle,
                        author = albumArtist ?: cached?.artistName ?: track.artist,
                        thumbnail = albumArt ?: cached?.albumArt ?: track.thumbnail
                    )
                )
            },
            onAddToPlaylist = { playlist -> onAddToPlaylist(playlist.id, track) },
            onCreatePlaylistAndAdd = { title -> onCreatePlaylistAndAdd(title, track) },
            isInListenTogetherRoom = isInListenTogetherRoom,
            onRecommendToRoom = onRecommendToRoom,
            onDismiss = { selectedTrackForMenu = null }
        )
    }
}
