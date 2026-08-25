package com.auralis.music.ui.explore

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.auralis.music.ui.viewmodel.SearchUiState

enum class SearchCategory {
    ALL,
    SONGS,
    ARTISTS,
    PLAYLISTS
}

/**
 * Pure Jetpack Compose Search Screen matching the exact reference:
 * Minimalist top search bar ("Search YouTube Music...", Back arrow, Globe icon),
 * interactive history items with clock icon, clear cross, and diagonal insert arrow (↖),
 * live autocomplete suggestions, category filter pills, and bottom right voice / music recognition button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    uiState: SearchUiState,
    recognitionState: RecognitionState = RecognitionState(),
    currentTrackId: String?,
    isPlaying: Boolean,
    userPlaylists: List<Playlist> = emptyList(),
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onRemoveRecentQuery: (String) -> Unit = {},
    onClearRecentQueries: () -> Unit = {},
    onOpenRecognition: (RecognitionMode) -> Unit = {},
    onCloseRecognition: () -> Unit = {},
    onModeSelect: (RecognitionMode) -> Unit = {},
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var selectedCategory by remember { mutableStateOf(SearchCategory.ALL) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    val hasResults = uiState.searchResults.songs.isNotEmpty() ||
            uiState.searchResults.artists.isNotEmpty() ||
            uiState.searchResults.playlists.isNotEmpty()

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
                        onClearSearch()
                        focusManager.clearFocus()
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
                            text = "Search YouTube Music...",
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
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Dynamic Clear Cross Button (only visible when text is typed)
                if (uiState.query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ================================================================
            // 2. SEARCH PROGRESS SPINNER
            // ================================================================
            if (uiState.isSearching) {
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
                return
            }

            // ================================================================
            // 3. SEARCH RESULTS (When search executed)
            // ================================================================
            if (hasResults) {
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
                    onArtistClick = { artist -> onSearch(artist.query) },
                    onPlaylistClick = { playlist -> onSearch(playlist.title) }
                )
            } else {
                // ============================================================
                // 4. LIVE SUGGESTIONS & RECENT SEARCHES LIST
                // ============================================================
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Live Autocomplete Suggestions (while typing)
                    if (uiState.suggestions.isNotEmpty()) {
                        items(uiState.suggestions) { suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                        items(uiState.recentQueries) { query ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
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

    // Options Menu
    selectedTrackForMenu?.let { track ->
        TrackOptionsMenu(
            track = track,
            isFavorite = false,
            userPlaylists = userPlaylists,
            onToggleFavorite = { onFavoriteToggle(track) },
            onPlayNext = {},
            onAddToQueue = {},
            onAddToPlaylist = { playlist -> onAddToPlaylist(playlist.id, track) },
            onCreatePlaylistAndAdd = { title -> onCreatePlaylistAndAdd(title, track) },
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
        // Songs
        if (category == SearchCategory.ALL || category == SearchCategory.SONGS) {
            items(results.songs, key = { it.id }) { track ->
                val isCurrent = track.id == currentTrackId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onTrackClick(track, results.songs) }
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

        // Artists
        if (category == SearchCategory.ALL || category == SearchCategory.ARTISTS) {
            if (results.artists.isNotEmpty()) {
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
        }

        // Playlists
        if (category == SearchCategory.ALL || category == SearchCategory.PLAYLISTS) {
            if (results.playlists.isNotEmpty()) {
                item {
                    Text(
                        text = "Playlists",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD4E157),
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(results.playlists, key = { it.id }) { pl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPlaylistClick(pl) }
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkCard(
                            url = pl.thumbnail ?: "",
                            modifier = Modifier.size(50.dp),
                            cornerRadius = 8.dp,
                            contentDescription = pl.title
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pl.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = pl.author ?: "Playlist",
                                style = MaterialTheme.typography.bodySmall,
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
