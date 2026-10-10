package com.auralis.music.windows.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.search.LocalSearchUi
import com.auralis.music.ui.search.SearchResultsList
import com.auralis.music.ui.search.SearchUiPlatform
import com.auralis.music.ui.search.SearchUiStrings

/** Width of the results column: a phone-like list stays readable in a wide window. */
private val ContentMaxWidth = 860.dp

/** The Windows pieces of the shared search UI. Text is English until the app's translations are shared. */
private val DesktopSearchUi = SearchUiPlatform(
    artwork = { url, modifier, cornerRadius, contentDescription -> DesktopArtwork(url, modifier, cornerRadius, contentDescription) },
    nowPlayingIndicator = { _, _, _ -> },
    strings = SearchUiStrings(
        albumBy = { "Album • $it" },
        songBy = { "Song • $it" },
        variousArtists = { "Various Artists" },
        single = { "Single" },
        artist = { "Artist" },
        options = { "Options" }
    )
)

@Composable
fun SearchScreen(
    state: DesktopSearchState,
    currentTrackId: String?,
    isPlaying: Boolean,
    onPlay: ((com.auralis.music.domain.model.Track, List<com.auralis.music.domain.model.Track>) -> Unit)?,
    onUnavailable: (String) -> Unit,
    onOpenArtist: (com.auralis.music.domain.model.Artist) -> Unit = { onUnavailable("Artist pages on Windows come next") },
    onOpenAlbum: (com.auralis.music.domain.model.PlaylistResult) -> Unit = { onUnavailable("Album pages on Windows come next") },
    onPlayNext: (com.auralis.music.domain.model.Track) -> Unit = {},
    onAddToQueue: (com.auralis.music.domain.model.Track) -> Unit = {},
    onMenuClick: (com.auralis.music.domain.model.Track) -> Unit = { onUnavailable("Song options on Windows come next") },
    /** Changes each time Ctrl+F asks for the search field. */
    focusRequests: Int = 0,
    onTypingChange: (Boolean) -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequests) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        SearchBar(
            query = state.query,
            onQueryChange = state::onQueryChange,
            onSearch = { state.search() },
            onClear = state::clear,
            focusRequester = focusRequester,
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .onFocusChanged { onTypingChange(it.hasFocus) }
        )

        Box(modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().weight(1f)) {
            val results = state.results
            when {
                state.isSearching && results.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                }
                state.hasSubmitted && results.isNotEmpty() -> CompositionLocalProvider(LocalSearchUi provides DesktopSearchUi) {
                    SearchResultsList(
                        results = results,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        query = state.query,
                        searchFailed = state.searchFailed,
                        onRetry = { state.search() },
                        currentTrackId = currentTrackId,
                        isPlaying = isPlaying,
                        onTrackClick = { track, list ->
                            if (onPlay != null) onPlay(track, list) else onUnavailable("Playback needs VLC (64-bit)")
                        },
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onMenuClick = onMenuClick,
                        onArtistClick = onOpenArtist,
                        onPlaylistClick = onOpenAlbum
                    )
                }
                state.hasSubmitted && state.searchFailed -> CenteredMessage("Couldn't reach YouTube Music.", action = "Retry") { state.search() }
                state.hasSubmitted -> CenteredMessage("No results for “${state.query}”")
                else -> SuggestionsOrRecent(
                    state = state,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    onPlay = { track, list ->
                        if (onPlay != null) onPlay(track, list) else onUnavailable("Playback needs VLC (64-bit)")
                    },
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onMenuClick = onMenuClick
                )
            }
        }
    }
}

/** The phone's minimal search row: icon, borderless field with "Search Auralis", clear button. */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search Auralis",
                        style = TextStyle(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f), fontSize = 16.sp)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Normal),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // Enter searches, Esc clears (desktop keyboards have no IME search key).
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> { onSearch(); true }
                                Key.Escape -> { onClear(); true }
                                else -> false
                            }
                        }
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** Before a search is submitted: the phone's suggestions while typing, recent searches when empty. */
@Composable
private fun SuggestionsOrRecent(
    state: DesktopSearchState,
    currentTrackId: String?,
    isPlaying: Boolean,
    onPlay: (com.auralis.music.domain.model.Track, List<com.auralis.music.domain.model.Track>) -> Unit,
    onPlayNext: (com.auralis.music.domain.model.Track) -> Unit,
    onAddToQueue: (com.auralis.music.domain.model.Track) -> Unit,
    onMenuClick: (com.auralis.music.domain.model.Track) -> Unit
) {
    val recent by state.recentQueries.collectAsState(initial = emptyList())
    if (state.query.isBlank() && recent.isEmpty()) {
        CenteredMessage("Search for songs, albums and artists")
        return
    }
    CompositionLocalProvider(LocalSearchUi provides DesktopSearchUi) {
        com.auralis.music.ui.search.SearchSuggestionsList(
            query = state.query,
            suggestions = state.suggestions,
            liveSongs = state.liveSongs,
            recentQueries = recent,
            currentTrackId = currentTrackId,
            isPlaying = isPlaying,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            onSearch = { state.search(it) },
            onInsert = state::onQueryChange,
            onRemoveRecent = state::removeRecent,
            onTrackClick = onPlay,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onMenuClick = onMenuClick
        )
    }
}

@Composable
private fun CenteredMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
