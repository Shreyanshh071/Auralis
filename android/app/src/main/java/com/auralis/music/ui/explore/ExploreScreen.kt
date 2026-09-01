package com.auralis.music.ui.explore

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import com.auralis.music.ui.theme.LocalReducedMotion
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
import com.auralis.music.ui.viewmodel.SearchUiState

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
    onToggleSubscribe: (com.auralis.music.domain.model.SavedArtist) -> Unit = {},
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    val handleTrackClick: (Track, List<Track>) -> Unit = { track, list ->
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onTrackClick(track, list)
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

    LaunchedEffect(currentDetail) {
        if (currentDetail != null) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    AnimatedContent(
        targetState = currentDetail,
        transitionSpec = {
            if (targetState != null) {
                detailForwardEnter togetherWith detailForwardExit
            } else {
                detailBackwardEnter togetherWith detailBackwardExit
            }
        },
        label = "SearchDetailTransition"
    ) { detail ->
        when (detail) {
            is com.auralis.music.ui.viewmodel.ExploreDetail.Artist -> {
                ArtistScreen(
                    artistPage = detail.artistPage,
                    isLoading = detail.isLoading,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    userPlaylists = userPlaylists,
                    favoriteTracks = favoriteTracks,
                    savedArtists = savedArtists,
                    onToggleSubscribe = onToggleSubscribe,
                    onTrackClick = handleTrackClick,
                    onFavoriteToggle = onFavoriteToggle,
                    onAddToPlaylist = onAddToPlaylist,
                    onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
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
                    album = detail.album,
                    tracks = detail.tracks,
                    isLoading = detail.isLoading,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    userPlaylists = userPlaylists,
                    favoriteTracks = favoriteTracks,
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
            null -> {
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
                    .background(MaterialTheme.colorScheme.background)
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
                        contentDescription = "Back",
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
                            text = "Search Auralis...",
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
                                contentDescription = "Clear search",
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
                                contentDescription = "Speak to search",
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
                uiState.isSearching && uiState.searchResults.isEmpty() -> SearchBodyState.SEARCHING
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
                        SearchResultsView(
                            results = uiState.searchResults,
                            query = uiState.query,
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
                    SearchBodyState.SUGGESTIONS -> {
                        val animateItems = !LocalReducedMotion.current
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // 1. Live Autocomplete Text Suggestions (Top 3 text recommendations)
                            if (uiState.query.isNotBlank() && uiState.suggestions.isNotEmpty()) {
                                items(
                                    items = uiState.suggestions.take(3),
                                    key = { "sug_$it" }
                                ) { suggestion ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (animateItems) Modifier.animateItem() else Modifier)
                                            .clickable {
                                                focusManager.clearFocus(force = true)
                                                keyboardController?.hide()
                                                onSearch(suggestion)
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.60f),
                                            modifier = Modifier.size(22.dp)
                                        )

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Text(
                                            text = suggestion,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            fontSize = 16.sp,
                                            modifier = Modifier.weight(1f)
                                        )

                                        IconButton(
                                            onClick = { onQueryChange(suggestion) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            DiagonalInsertArrow()
                                        }
                                    }
                                }
                            }

                            // 2. Direct Live Song Recommendations (Top Songs section right below text suggestions)
                            if (uiState.query.isNotBlank() && uiState.liveSongRecommendations.isNotEmpty()) {
                                item(key = "header_top_songs") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Top Songs",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 17.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                                    )
                                }

                                items(
                                    items = uiState.liveSongRecommendations,
                                    key = { trk -> "live_song_${trk.id}" }
                                ) { track ->
                                    val isCurrent = track.id == currentTrackId
                                    TrackRowItem(
                                        track = track,
                                        isCurrent = isCurrent,
                                        isPlaying = isPlaying,
                                        playlist = uiState.liveSongRecommendations,
                                        onTrackClick = handleTrackClick,
                                        onPlayNext = onPlayNext,
                                        onAddToQueue = onAddToQueue,
                                        onMenuClick = { selectedTrackForMenu = track }
                                    )
                                }
                            }

                            // 2. Recent Searches History Items (when input is empty)
                            if (uiState.query.isBlank()) {
                                items(
                                    items = uiState.recentQueries,
                                    key = { "recent_$it" }
                                ) { query ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (animateItems) Modifier.animateItem() else Modifier)
                                            .clickable {
                                                focusManager.clearFocus(force = true)
                                                keyboardController?.hide()
                                                onSearch(query)
                                            }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = "History",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Text(
                                            text = query,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            fontSize = 16.sp,
                                            modifier = Modifier.weight(1f)
                                        )

                                        // Remove from history
                                        IconButton(
                                            onClick = { onRemoveRecentQuery(query) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        // Diagonal insert arrow (↖)
                                        IconButton(
                                            onClick = { onQueryChange(query) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            DiagonalInsertArrow(tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
}
}
}

    // Options Menu
    selectedTrackForMenu?.let { track ->
        val isFav = favoriteTracks.any { it.id == track.id }
        TrackOptionsMenu(
            track = track,
            isFavorite = isFav,
            userPlaylists = userPlaylists,
            onToggleFavorite = { onFavoriteToggle(track) },
            onPlayNext = { onPlayNext(track) },
            onAddToQueue = { onAddToQueue(track) },
            onStartRadio = { onStartRadio(track) },
            onGoToArtist = {
                onOpenArtist(Artist(id = "", name = track.artist))
            },
            onAddToPlaylist = { playlist -> onAddToPlaylist(playlist.id, track) },
            onCreatePlaylistAndAdd = { title -> onCreatePlaylistAndAdd(title, track) },
            isInListenTogetherRoom = isInListenTogetherRoom,
            onRecommendToRoom = onRecommendToRoom,
            onDismiss = { selectedTrackForMenu = null }
        )
    }
}

/**
 * Custom 60fps canvas-rendered diagonal insert arrow (↖) matching YouTube Music.
 */
@Composable
private fun DiagonalInsertArrow(
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.60f)
) {
    Canvas(modifier = modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()

        // Main diagonal arrow shaft
        drawLine(
            color = tint,
            start = Offset(w * 0.82f, h * 0.82f),
            end = Offset(w * 0.18f, h * 0.18f),
            strokeWidth = stroke
        )
        // Top horizontal arrow bar
        drawLine(
            color = tint,
            start = Offset(w * 0.18f, h * 0.18f),
            end = Offset(w * 0.68f, h * 0.18f),
            strokeWidth = stroke
        )
        // Left vertical arrow bar
        drawLine(
            color = tint,
            start = Offset(w * 0.18f, h * 0.18f),
            end = Offset(w * 0.18f, h * 0.68f),
            strokeWidth = stroke
        )
    }
}

// ============================================================================
// 🎯 SEARCH RESULTS VIEW
// ============================================================================

@Composable
private fun SearchResultsView(
    results: com.auralis.music.domain.model.SearchResults,
    query: String = "",
    currentTrackId: String?,
    isPlaying: Boolean,
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlayNext: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    onMenuClick: (Track) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlaylistClick: (PlaylistResult) -> Unit
) {
    val allArtists = (listOfNotNull(results.primaryArtist) + results.artists).distinctBy { it.id }
    val allAlbums = (listOfNotNull(results.primaryAlbum) + results.albums + results.playlists).distinctBy { it.id }
    val primaryArtist = results.primaryArtist ?: results.artists.firstOrNull()
    val primaryAlbum = results.primaryAlbum

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 160.dp, start = 16.dp, end = 16.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ====================================================================
        // STEP 1 — TOP RESULT
        // ====================================================================
        if (results.topResult != null) {
            item(key = "header_top_result") {
                Text(
                    text = "Top result",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            item(key = "card_top_result") {
                when (val tr = results.topResult) {
                    is SearchTopResult.SongResult -> {
                        val track = tr.track
                        val isCurrent = track.id == currentTrackId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onTrackClick(track, results.songs.ifEmpty { listOf(track) }) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkCard(
                                    url = track.thumbnail,
                                    modifier = Modifier.size(72.dp),
                                    cornerRadius = 12.dp,
                                    contentDescription = track.title
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val subDetail = buildString {
                                        append("Song • ${track.artist}")
                                        if (!track.views.isNullOrBlank()) {
                                            append(" • ${track.views}")
                                        }
                                    }
                                    Text(
                                        text = subDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    is SearchTopResult.ArtistResult -> {
                        val artist = tr.artist
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onArtistClick(artist) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkCard(
                                    url = artist.thumbnail ?: "",
                                    modifier = Modifier.size(72.dp).clip(CircleShape),
                                    cornerRadius = 36.dp,
                                    contentDescription = artist.name
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = artist.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Artist",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    is SearchTopResult.AlbumResult -> {
                        val album = tr.album
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onPlaylistClick(album) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkCard(
                                    url = album.thumbnail,
                                    modifier = Modifier.size(72.dp),
                                    cornerRadius = 12.dp,
                                    contentDescription = album.title
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Album • ${album.author ?: "Various Artists"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        // ====================================================================
        // STEP 2 — ARTIST & ALBUM (EXACT SIDE-BY-SIDE CARDS)
        // ====================================================================
        if (primaryArtist != null || primaryAlbum != null) {
            item(key = "header_artist_album") {
                Text(
                    text = "Artist & Album",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }

            item(key = "row_artist_album") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Artist Card (Left)
                    if (primaryArtist != null) {
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onArtistClick(primaryArtist) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!primaryArtist.thumbnail.isNullOrBlank() && !primaryArtist.thumbnail.contains("i.ytimg.com/vi/")) {
                                    ArtworkCard(
                                        url = primaryArtist.thumbnail,
                                        modifier = Modifier.size(46.dp).clip(CircleShape),
                                        cornerRadius = 23.dp,
                                        contentDescription = primaryArtist.name
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ARTIST",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = primaryArtist.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Top songs & info",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Album Card (Right)
                    if (primaryAlbum != null) {
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onPlaylistClick(primaryAlbum) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkCard(
                                    url = primaryAlbum.thumbnail,
                                    modifier = Modifier.size(46.dp),
                                    cornerRadius = 8.dp,
                                    contentDescription = primaryAlbum.title
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ALBUM",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = primaryAlbum.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = primaryAlbum.author ?: "Album",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        // Clean 'No album' card for standalone singles / non-album songs
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ALBUM",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "No album",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = primaryArtist?.name ?: "Single",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // STEP 3 — ACTUAL SEARCH RESULTS (REAL QUERY-MATCHED SONGS)
        // ====================================================================
        item(key = "header_matching_songs") {
            Text(
                text = "Songs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            )
        }

        if (results.songs.isNotEmpty()) {
            items(results.songs, key = { "match_${it.id}" }) { track ->
                val isCurrent = track.id == currentTrackId
                TrackRowItem(
                    track = track,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    playlist = results.songs,
                    onTrackClick = onTrackClick,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onMenuClick = onMenuClick
                )
            }
        } else {
            item(key = "empty_matching_songs") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No matching songs found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (query.isNotBlank()) "No songs match \"$query\". Check spelling or try searching another artist or title."
                        else "No songs found for this search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        // ====================================================================
        // STEP 4 — RECOMMENDATIONS (EXACTLY 3 MAXIMUM, SUPPLEMENTARY)
        // ====================================================================
        val recommendations = results.recommendations.take(3)
        if (recommendations.isNotEmpty()) {
            item(key = "header_recommendations") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recommendations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${recommendations.size} suggested",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(recommendations, key = { "rec_${it.id}" }) { track ->
                val isCurrent = track.id == currentTrackId
                TrackRowItem(
                    track = track,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    playlist = recommendations,
                    onTrackClick = onTrackClick,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onMenuClick = onMenuClick
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRowItem(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    playlist: List<Track>,
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlayNext: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    onMenuClick: (Track) -> Unit
) {
    com.auralis.music.ui.components.SwipeableTrackContainer(
        onPlayNext = { onPlayNext?.invoke(track) },
        onAddToQueue = { onAddToQueue?.invoke(track) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { onTrackClick(track, playlist) },
                    onLongClick = { onMenuClick(track) }
                )
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkCard(
                url = track.thumbnail,
                modifier = Modifier.size(50.dp),
                cornerRadius = 8.dp,
                contentDescription = track.title
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitleText = buildString {
                    if (track.artist.isNotBlank()) {
                        append(track.artist)
                    }
                    if (!track.album.isNullOrBlank()) {
                        append(" • ${track.album}")
                    }
                    if (!track.views.isNullOrBlank()) {
                        append(" • ${track.views}")
                    } else if (track.album.isNullOrBlank()) {
                        append(" • no album")
                    }
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent) {
                EqualizerBars(
                    isPlaying = isPlaying,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
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
