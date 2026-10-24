package com.auralis.music.windows.search

import com.auralis.music.ui.i18n.desktopText

import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import com.auralis.music.ui.components.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.animateContentSize
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
import com.auralis.music.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.auralis.music.ui.components.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
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
    artwork: String? = null,
    compact: Boolean = false,
    /** The slim field centred in the title bar; the results open centred beneath it. */
    bar: Boolean = false,
    focusRequests: Int = 0
) {
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    var mounted by remember { androidx.compose.runtime.mutableStateOf(false) }
    val reveal = remember { androidx.compose.animation.core.Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusRequests) { if (bar && focusRequests > 0) focus.requestFocus() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { (if (bar) 40.dp else if (compact) 46.dp else 72.dp).roundToPx() }
    val position = remember(gap, bar) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
                val x = (if (bar) anchorBounds.center.x - popupContentSize.width / 2 else anchorBounds.right - popupContentSize.width)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top + gap).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    // Title bar: the bar itself is the text field (no second field inside the dropdown).
    var fieldFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun dismiss() { expanded = false; onTypingChange(false) }
    fun submit(text: String) {
        if (text.isNotBlank()) { dismiss(); if (bar) focusManager.clearFocus(); onSearch(text) }
    }
    fun open() { mounted = true; expanded = true }
    Box {
        if (bar) {
            val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Row(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = if (hovered || expanded) .14f else .08f))
                .hoverable(hover)
                .clickable(interactionSource = hover, indication = null) { focus.requestFocus(); open() }
                .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color(0xFFBDB7C5))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (state.query.isEmpty()) Text(desktopText("What do you want to play?"), fontSize = 14.sp,
                        color = androidx.compose.ui.graphics.Color(0xFF9E98A6), maxLines = 1)
                    BasicTextField(
                        value = state.query,
                        onValueChange = { state.onQueryChange(it); if (!expanded) open() },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = androidx.compose.ui.graphics.Color(0xFFE8E4EB)),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(0xFFE8E4EB)),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            .onFocusChanged { f ->
                                fieldFocused = f.isFocused
                                if (f.isFocused) { open(); onTypingChange(true) }
                                // Clicking a result moves focus first; give the click a moment to land.
                                else scope.launch { kotlinx.coroutines.delay(200); if (!fieldFocused) dismiss() }
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                                else when (event.key) {
                                    androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter -> { submit(state.query); true }
                                    androidx.compose.ui.input.key.Key.Escape -> { dismiss(); focusManager.clearFocus(); true }
                                    else -> false
                                }
                            }
                    )
                }
                if (state.query.isNotEmpty()) Icon(Icons.Default.Close, desktopText("Clear search"), Modifier.size(16.dp)
                    .clickable { state.clear(); focus.requestFocus() }, tint = androidx.compose.ui.graphics.Color(0xFFBDB7C5))
            }
        } else
        if (compact) com.auralis.music.ui.components.IconButton(onClick = {
            if (expanded) dismiss() else { mounted = true; expanded = true }
        }) { Icon(Icons.Default.Search, desktopText("Find music"), Modifier.size(20.dp)) }
        else
        com.auralis.music.ui.components.FilledTonalButton(
            onClick = {
                if (expanded) dismiss() else {
                    mounted = true
                    expanded = true
                }
            }, modifier = Modifier.height(64.dp).widthIn(min = 176.dp),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = .10f),
                contentColor = MaterialTheme.colorScheme.onSurface),
            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(18.dp, 16.dp)
        ) {
            Icon(Icons.Default.Search, null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(desktopText("Find music"), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp))
        }
        if (mounted) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = position,
                // Title bar: typing stays in the bar, so the dropdown never takes focus.
                // A click outside closes it and leaves the field; a click on the field itself focuses it again.
                onDismissRequest = { dismiss(); if (bar) focusManager.clearFocus() },
                properties = androidx.compose.ui.window.PopupProperties(focusable = !bar)
            ) {
                LaunchedEffect(expanded) {
                    if (expanded) {
                        androidx.compose.runtime.withFrameNanos { }
                        focus.requestFocus()
                        onTypingChange(true)
                    }
                }
                LaunchedEffect(expanded) {
                    if (expanded) {
                        // Start on this popup's clock. The parent
                        // clock can finish the animation while a new popup is being created.
                        reveal.animateTo(1f, androidx.compose.animation.core.tween(220,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing))
                        if (state.hasSubmitted) state.onQueryChange(state.query)
                    } else {
                        reveal.animateTo(0f, androidx.compose.animation.core.tween(140,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing))
                        mounted = false
                    }
                }
                // Use the already cached cover for the frosted backdrop. GPU readback of the
                // whole window here stalled the event thread before the first popup frame.
                SearchDropdownSurface(liquidify, artwork, glass = bar, modifier = Modifier.width(if (bar) 620.dp else 480.dp)
                    .heightIn(max = 460.dp)
                    .then(Modifier.graphicsLayer {
                        // Make the very first painted frame visible, before the frame clock
                        // begins advancing the rest of the fade.
                        alpha = if (expanded) maxOf(.08f, reveal.value) else reveal.value
                        translationY = -10.dp.toPx() * (1f - reveal.value)
                        scaleX = .97f + .03f * reveal.value
                        scaleY = scaleX
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (bar) .5f else 1f, 0f)
                    })) {
                    Column(Modifier.fillMaxWidth().padding(top = if (bar) 8.dp else 4.dp)) {
                        if (!bar) SearchBar(state.query, state::onQueryChange, { submit(state.query) }, state::clear, focus,
                            Modifier.fillMaxWidth().padding(12.dp))
                        Box(Modifier.fillMaxWidth()) {
                            SuggestionsOrRecent(state, currentTrackId, isPlaying,
                                onPlay = { track, tracks -> dismiss(); onPlay(track, tracks) },
                                onPlayNext = onPlayNext, onAddToQueue = onAddToQueue,
                                onMenuClick = { dismiss(); onMenuClick(it) }, onSearch = ::submit,
                                fillAvailableHeight = false)
                        }
                    }
                }
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onTypingChange(false) } }
}

/** Frost the actual app beneath search while keeping recommendation rows sharp. */
@Composable
internal fun SearchDropdownSurface(liquidify: Boolean, artwork: String?, modifier: Modifier = Modifier,
    backdrop: androidx.compose.ui.graphics.ImageBitmap? = null,
    backdropOffset: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset.Zero,
    /** Title bar: translucent frosted glass (the playing cover blurred under a see-through tint). */
    glass: Boolean = false,
    content: @Composable () -> Unit) {
    val light = MaterialTheme.colorScheme.onSurface.luminance() < .5f
    val reducedMotion = com.auralis.music.ui.theme.LocalReducedMotion.current
    com.auralis.music.windows.LiquidPopupSurface(modifier,
        tint = if (light) Color.White.copy(alpha = .42f) else Color(0xFF101010).copy(alpha = .38f)) {
        // Animate the measured content inside the glass. Animating outside it made
        // the glass/border jump to their final size and merely clipped them open.
        // A spring also carries velocity when text suggestions and songs arrive apart.
        Box(Modifier.fillMaxWidth().animateContentSize(animationSpec = if (reducedMotion)
            androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(
                dampingRatio = 1f, stiffness = 650f), alignment = Alignment.TopStart)) {
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
    // The title bar owns the single search field; results use the whole content panel.
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                        text = desktopText("Search Auralis"),
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
                        contentDescription = desktopText("Clear search"),
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
    onSearch: (String) -> Unit = { state.search(it) },
    fillAvailableHeight: Boolean = true
) {
    val recent by state.recentQueries.collectAsState(initial = emptyList())
    if (state.query.isBlank() && recent.isEmpty()) {
        if (fillAvailableHeight) CenteredMessage("Search for songs, albums and artists")
        else Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), contentAlignment = Alignment.Center) {
            Text(desktopText("Search for songs, albums and artists"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        }
        return
    }
    CompositionLocalProvider(LocalSearchUi provides DesktopSearchUi) {
        com.auralis.music.ui.search.SearchSuggestionsList(
            query = state.query,
            suggestions = state.suggestions,
            liveSongs = state.liveSongs,
            recentQueries = recent,
            fillAvailableHeight = fillAvailableHeight,
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
