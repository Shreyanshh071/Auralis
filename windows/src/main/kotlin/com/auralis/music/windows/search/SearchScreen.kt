package com.auralis.music.windows.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.luminance
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

/** Home's anchored search: the same live songs and recent queries as the full search page. */
@Composable
fun FindMusicDropdown(
    state: DesktopSearchState,
    currentTrackId: String?,
    isPlaying: Boolean,
    onPlay: (com.auralis.music.domain.model.Track, List<com.auralis.music.domain.model.Track>) -> Unit,
    onSearch: (String) -> Unit,
    onPlayNext: (com.auralis.music.domain.model.Track) -> Unit,
    onAddToQueue: (com.auralis.music.domain.model.Track) -> Unit,
    onMenuClick: (com.auralis.music.domain.model.Track) -> Unit,
    onTypingChange: (Boolean) -> Unit,
    liquidify: Boolean = false,
    artwork: String? = null
) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    val backdrop = LocalSearchBackdrop.current
    var backdropImage by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var backdropOffset by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) }
    val focus = remember { FocusRequester() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(expanded, liquidify) {
        backdropImage = if (expanded && liquidify) runCatching { backdrop?.toImageBitmap() }.getOrNull() else null
    }
    val gap = with(density) { 62.dp.roundToPx() }
    val position = remember(gap) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top + gap).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return androidx.compose.ui.unit.IntOffset(x, y).also { backdropOffset = it }
            }
        }
    }
    fun dismiss() { expanded = false; onTypingChange(false) }
    fun submit(text: String) {
        if (text.isNotBlank()) { dismiss(); onSearch(text) }
    }
    Box {
        androidx.compose.material3.FilledTonalButton(
            onClick = {
                if (expanded) dismiss() else {
                    if (state.hasSubmitted) state.onQueryChange(state.query)
                    expanded = true
                }
            }, modifier = Modifier.height(54.dp).widthIn(min = 176.dp),
            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(22.dp, 14.dp)
        ) {
            Icon(Icons.Default.Search, null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Find music", style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = position,
                onDismissRequest = ::dismiss,
                properties = androidx.compose.ui.window.PopupProperties(focusable = true)
            ) {
                LaunchedEffect(Unit) { focus.requestFocus(); onTypingChange(true) }
                SearchDropdownSurface(liquidify, artwork, Modifier.width(480.dp).height(460.dp), backdropImage, backdropOffset) {
                    Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                        SearchBar(state.query, state::onQueryChange, { submit(state.query) }, state::clear, focus,
                            Modifier.fillMaxWidth().padding(12.dp))
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            SuggestionsOrRecent(state, currentTrackId, isPlaying,
                                onPlay = { track, tracks -> dismiss(); onPlay(track, tracks) },
                                onPlayNext = onPlayNext, onAddToQueue = onAddToQueue,
                                onMenuClick = { dismiss(); onMenuClick(it) }, onSearch = ::submit)
                        }
                    }
                }
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onTypingChange(false) } }
}

/** Match Liquidify's blurred cover backdrop while keeping the search rows sharp. */
@Composable
internal fun SearchDropdownSurface(liquidify: Boolean, artwork: String?, modifier: Modifier = Modifier,
    backdrop: androidx.compose.ui.graphics.ImageBitmap? = null,
    backdropOffset: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset.Zero,
    content: @Composable () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val light = colours.onSurface.luminance() < .5f
    Surface(modifier, shape = RoundedCornerShape(18.dp),
        color = if (liquidify) { if (light) Color(0xFFF7F4F9) else Color(0xFF171519) } else colours.surface.copy(alpha = 1f),
        tonalElevation = if (liquidify) 0.dp else 6.dp, shadowElevation = 12.dp,
        border = if (liquidify) BorderStroke(1.dp, colours.onSurface.copy(alpha = .12f)) else null) {
        Box(Modifier.fillMaxSize()) {
            if (liquidify) {
                if (backdrop != null) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().then(Modifier.blur(24.dp))) {
                        drawImage(backdrop, topLeft = androidx.compose.ui.geometry.Offset(-backdropOffset.x.toFloat(), -backdropOffset.y.toFloat()))
                    }
                } else com.auralis.music.windows.player.BlurredArtworkBackground(artwork, blurRadius = 40.dp)
                Box(Modifier.fillMaxSize().background(
                    if (light) Color(0xFFF7F4F9).copy(alpha = .70f) else Color(0xFF171519).copy(alpha = .50f)))
            }
            content()
        }
    }
}

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
    onMenuClick: (com.auralis.music.domain.model.Track) -> Unit,
    onSearch: (String) -> Unit = { state.search(it) }
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
            onSearch = onSearch,
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
