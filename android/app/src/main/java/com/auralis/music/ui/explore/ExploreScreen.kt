package com.auralis.music.ui.explore

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

enum class SearchCategory {
    ALL,
    SONGS,
    ARTISTS,
    ALBUMS
}

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
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {},
    onCloseArtist: () -> Unit = {},
    onBack: () -> Unit = {},
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var selectedCategory by remember { mutableStateOf(SearchCategory.ALL) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }


    AnimatedContent(
        targetState = uiState.selectedArtistPage,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(300, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
                ) + fadeIn(tween(240))).togetherWith(
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth / 3 },
                        animationSpec = tween(260)
                    ) + fadeOut(tween(180))
                )
            } else {
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth / 3 },
                    animationSpec = tween(260)
                ) + fadeIn(tween(200))).togetherWith(
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(260, easing = CubicBezierEasing(0.32f, 0f, 0.8f, 0.15f))
                    ) + fadeOut(tween(180))
                )
            }
        },
        label = "ArtistScreenTransition"
    ) { artistPage ->
        if (artistPage != null) {
            ArtistScreen(
                artistPage = artistPage,
                isLoading = uiState.isLoadingArtist,
                currentTrackId = currentTrackId,
                isPlaying = isPlaying,
                userPlaylists = userPlaylists,
                favoriteTracks = favoriteTracks,
                onTrackClick = onTrackClick,
                onFavoriteToggle = onFavoriteToggle,
                onAddToPlaylist = onAddToPlaylist,
                onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onStartRadio = onStartRadio,
                onOpenArtist = onOpenArtist,
                onBack = onCloseArtist,
                isInListenTogetherRoom = isInListenTogetherRoom,
                onRecommendToRoom = onRecommendToRoom,
                modifier = modifier
            )
        } else {
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
                    focusManager.clearFocus()
                }
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
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Search Input Field
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (uiState.query.isEmpty()) {
                        Text(
                            text = "Search Auralis...",
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.50f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            )
                        )
                    }

                    BasicTextField(
                        value = uiState.query,
                        onValueChange = onQueryChange,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(Color(0xFFD4E157)),
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
                                tint = Color.White.copy(alpha = 0.8f),
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
                                tint = Color.White.copy(alpha = 0.8f),
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
                hasResults -> SearchBodyState.RESULTS
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
                                color = Color(0xFFD4E157),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    SearchBodyState.RESULTS -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            CategoryFilterBar(
                                selectedCategory = selectedCategory,
                                onSelectCategory = { selectedCategory = it }
                            )

                            SearchResultsView(
                                category = selectedCategory,
                                results = uiState.searchResults,
                                currentTrackId = currentTrackId,
                                isPlaying = isPlaying,
                                onTrackClick = { track, list -> onTrackClick(track, list) },
                                onMenuClick = { track -> selectedTrackForMenu = track },
                                onArtistClick = { artist -> onOpenArtist(artist) },
                                onPlaylistClick = { playlist -> onSearch(playlist.title) }
                            )
                        }
                    }
                    SearchBodyState.SUGGESTIONS -> {
                        val animateItems = !LocalReducedMotion.current
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Live Autocomplete Suggestions (while typing)
                            if (uiState.suggestions.isNotEmpty()) {
                                items(
                                    items = uiState.suggestions,
                                    key = { "sug_$it" }
                                ) { suggestion ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (animateItems) Modifier.animateItem() else Modifier)
                                            .clickable {
                                                focusManager.clearFocus()
                                                onSearch(suggestion)
                                            }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.60f),
                                            modifier = Modifier.size(22.dp)
                                        )

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Text(
                                            text = suggestion,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Color.White,
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
                            } else {
                                // Recent Searches History Items (when input is empty)
                                items(
                                    items = uiState.recentQueries,
                                    key = { "recent_$it" }
                                ) { query ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (animateItems) Modifier.animateItem() else Modifier)
                                            .clickable {
                                                focusManager.clearFocus()
                                                onSearch(query)
                                            }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = "History",
                                            tint = Color.White.copy(alpha = 0.65f),
                                            modifier = Modifier.size(22.dp)
                                        )

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Text(
                                            text = query,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Color.White,
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
                                                tint = Color.White.copy(alpha = 0.60f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        // Diagonal insert arrow (↖)
                                        IconButton(
                                            onClick = { onQueryChange(query) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            DiagonalInsertArrow()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // 5. FLOATING VOICE SEARCH & MUSIC RECOGNITION BUTTON (BOTTOM RIGHT)
        // ====================================================================
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 28.dp)
                .size(62.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF535D31))
                .tactileBounce(scaleDown = 0.88f) {
                    onOpenRecognition(RecognitionMode.VOICE_SEARCH)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Voice Search & Music Recognition",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // ====================================================================
        // 6. FULLSCREEN VOICE & MUSIC RECOGNITION MODAL
        // ====================================================================
        if (uiState.isRecognitionOpen) {
            VoiceAndMusicRecognitionModal(
                state = recognitionState,
                onModeSelect = onModeSelect,
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onPlayIdentifiedTrack = { track ->
                    onTrackClick(track, listOf(track))
                    onCloseRecognition()
                },
                onSearchQuery = { query ->
                    onSearch(query)
                    onCloseRecognition()
                },
                onDismiss = onCloseRecognition
            )
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
// 🎛️ CATEGORY FILTER BAR
// ============================================================================

@Composable
private fun CategoryFilterBar(
    selectedCategory: SearchCategory,
    onSelectCategory: (SearchCategory) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(SearchCategory.values()) { category ->
            val isSelected = category == selectedCategory
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFFD4E157) else Color(0xFF1E201A))
                    .border(
                        1.dp,
                        if (isSelected) Color(0xFFD4E157) else GlassBorderHairline,
                        CircleShape
                    )
                    .clickable { onSelectCategory(category) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

// ============================================================================
// 🎯 SEARCH RESULTS VIEW
// ============================================================================

@Composable
private fun SearchResultsView(
    category: SearchCategory,
    results: com.auralis.music.domain.model.SearchResults,
    currentTrackId: String?,
    isPlaying: Boolean,
    onTrackClick: (Track, List<Track>) -> Unit,
    onMenuClick: (Track) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlaylistClick: (PlaylistResult) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Songs Header on ALL Tab
        if (category == SearchCategory.ALL && results.songs.isNotEmpty()) {
            item {
                Text(
                    text = "Songs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD4E157),
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }
        }

        // Songs
        if (category == SearchCategory.ALL || category == SearchCategory.SONGS) {
            items(results.songs, key = { it.id }) { track ->
                val isCurrent = track.id == currentTrackId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { onTrackClick(track, results.songs) },
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
                            color = if (isCurrent) Color(0xFFD4E157) else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isCurrent) {
                        EqualizerBars(
                            isPlaying = isPlaying,
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFFD4E157)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
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

        // Artists (When ARTISTS tab is explicitly selected -> Full list view)
        if (category == SearchCategory.ARTISTS) {
            items(results.artists, key = { it.id }) { artist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onArtistClick(artist) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(
                        url = artist.thumbnail ?: "",
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                        cornerRadius = 28.dp,
                        contentDescription = artist.name
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Artist",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // Artists (When ALL tab is selected -> Horizontal shelf)
        if (category == SearchCategory.ALL && results.artists.isNotEmpty()) {
            item {
                Text(
                    text = "Artists",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD4E157),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(results.artists, key = { it.id }) { artist ->
                        Column(
                            modifier = Modifier
                                .width(105.dp)
                                .clickable { onArtistClick(artist) }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ArtworkCard(
                                url = artist.thumbnail ?: "",
                                modifier = Modifier.size(90.dp).clip(CircleShape),
                                cornerRadius = 45.dp,
                                contentDescription = artist.name
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Playlists / Albums (When ALL tab is selected -> Horizontal shelf, or ALBUMS tab -> Full list)
        if (category == SearchCategory.ALBUMS) {
            items(results.playlists, key = { it.id }) { pl ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlaylistClick(pl) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(
                        url = pl.thumbnail,
                        modifier = Modifier.size(56.dp),
                        cornerRadius = 8.dp,
                        contentDescription = pl.title
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pl.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = pl.author ?: "Playlist",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        } else if (category == SearchCategory.ALL && results.playlists.isNotEmpty()) {
            item {
                Text(
                    text = "Albums & Playlists",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD4E157),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(results.playlists, key = { it.id }) { pl ->
                        Column(
                            modifier = Modifier
                                .width(120.dp)
                                .clickable { onPlaylistClick(pl) }
                                .padding(4.dp)
                        ) {
                            ArtworkCard(
                                url = pl.thumbnail,
                                modifier = Modifier.size(110.dp),
                                cornerRadius = 10.dp,
                                contentDescription = pl.title
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = pl.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            pl.author?.let { auth ->
                                Text(
                                    text = auth,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f),
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
}
