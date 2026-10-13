package com.auralis.music.windows.home

import androidx.compose.ui.layout.layout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.auralis.music.domain.model.*
import com.auralis.music.ui.viewmodel.HomeUiState
import java.time.LocalTime
import kotlinx.coroutines.launch

/** Windows-only Home: bounded cards, readable titles and responsive desktop columns. */
@Composable
internal fun DesktopHomeScreen(
    state: HomeUiState,
    currentTrackId: String? = null,
    isPlaying: Boolean = false,
    onPlay: (Track, List<Track>) -> Unit,
    onShortcut: (SpeedDialItem) -> Unit,
    onAlbum: (PlaylistResult) -> Unit,
    onMenu: (Track) -> Unit,
    onSearch: () -> Unit,
    onSurprise: () -> Unit,
    onChip: (HomeChip?) -> Unit,
    onAlbumMenu: (PlaylistResult) -> Unit = {},
    onUnpinShortcut: (SpeedDialItem) -> Unit = {},
    shortcutCandidates: List<SpeedDialItem> = emptyList(),
    onPinShortcut: (SpeedDialItem) -> Unit = {},
    playlistCovers: Map<String, Playlist> = emptyMap(),
    onShortcutMenu: (SpeedDialItem) -> Unit = {},
    modifier: Modifier = Modifier,
    findMusic: (@Composable () -> Unit)? = null,
    onRefresh: () -> Unit = {},
    recentsGrid: Boolean = false,
    onToggleRecents: () -> Unit = {},
    loadingMore: Boolean = false,
    canLoadMore: Boolean = false,
    loadMoreFailed: Boolean = false,
    onLoadMore: () -> Unit = {},
    moreSections: List<HomeSection> = emptyList(),
    serviceShelves: List<DesktopHomeShelf>? = null, serviceRecents: List<Track>? = null,
    serviceLoading: Boolean = false, serviceError: String? = null, libraryShelf: List<PlaylistResult> = emptyList(),
    /** The listener's most-played songs, shown as Quick picks above Albums & singles. */
    quickPicks: List<Track> = emptyList(),
    /** The listener's most-played artists (Familiar Artists, in place of Quick picks). */
    familiarArtists: List<FamiliarArtist> = emptyList(),
    greetingName: String? = null,
    onOpenFamiliarArtist: (FamiliarArtist) -> Unit = {},
    familiarActions: FamiliarArtistActions? = null,
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: (Track) -> Unit = {}
) {
    val content = remember(state, moreSections) { desktopHomeContent(state, moreSections) }
    val colors = MaterialTheme.colorScheme
    val scroll = rememberLazyListState()
    val recentsScroll = rememberLazyListState()
    val loadingScroll = rememberLazyListState()
    val scrollHover = remember { MutableInteractionSource() }
    val scrollHovered by scrollHover.collectIsHoveredAsState()
    val scrollbarAlpha by animateFloatAsState(if (scrollHovered) 1f else 0f, tween(160))
    val recents = serviceRecents?.takeIf { it.isNotEmpty() } ?: content.recents
    val shelves = serviceShelves ?: buildList {
        addAll(content.shelves)
        if (content.picks.isNotEmpty()) add(DesktopHomeShelf("quick-picks", "Quick picks", tracks = content.picks))
    }
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(scroll, loadingMore, canLoadMore, loadMoreFailed) {
        if (canLoadMore && !loadingMore && !loadMoreFailed) snapshotFlow {
            val layout = scroll.layoutInfo
            layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
        }.collect { nearEnd -> if (nearEnd) loadMore() }
    }
    val idle = currentTrackId == null && colors.background.luminance() < .5f
    BoxWithConstraints(modifier.fillMaxSize().hoverable(scrollHover).then(if (idle) Modifier.background(Color(0xFF202020)) else Modifier)) {
        // Four tracks per column; a partial next column invites horizontal scrolling.
        val columnWidth = ((maxWidth - 32.dp) / when { maxWidth >= 1300.dp -> 4; maxWidth >= 900.dp -> 3; maxWidth >= 600.dp -> 2; else -> 1 })
            .coerceIn(260.dp, 410.dp)
        LazyColumn(Modifier.fillMaxSize(), state = scroll,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(34.dp)) {
            item("greeting") { HomeGreeting(greetingName) }
            item("shortcuts") {
                HomeShortcuts(content.shortcuts.filter { it.isPinned || it.id in state.pinnedSpeedDialIds || it.track?.id in state.pinnedSpeedDialIds },
                    shortcutCandidates, onShortcut, onPinShortcut, onUnpinShortcut, onShortcutMenu, familiarActions, playlistCovers)
            }
            item("recents") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeHeading("Recents", null, trailing = {
                        if (findMusic != null) findMusic() else IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Find music") }
                        IconButton(onClick = onToggleRecents) {
                            Icon(if (recentsGrid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                                if (recentsGrid) "Show recents as a list" else "Show recents as covers", Modifier.size(18.dp))
                        }
                    })
                    if (recents.isEmpty()) {
                        Text(if (serviceLoading || state.isLoading) "Loading recents…" else "Your recently played music will appear here.",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp))
                    } else LazyRow(state = recentsScroll, modifier = Modifier.mouseDragScroll(recentsScroll), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (recentsGrid) items(recents, key = { it.id }) { track ->
                            ArtworkCard(track.title, track.artist, track.thumbnail, currentTrackId == track.id && isPlaying,
                                { onPlay(track, recents) }, { onMenu(track) })
                        } else items(recents.chunked(4), key = { it.first().id }) { tracks ->
                            Column(Modifier.width(columnWidth)) {
                                tracks.forEach { track -> CompactSong(track, currentTrackId == track.id && isPlaying,
                                    { onPlay(track, recents) }, { onMenu(track) }, Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                }
            }
            if (familiarArtists.size >= 3) item("familiar-artists") {
                FamiliarArtistsRow(familiarArtists, onOpenFamiliarArtist, familiarActions ?: FamiliarArtistActions(
                    { null }, { false }, {}, {}, { false }, {}, {}, { false }, {}, {}))
            } else if (quickPicks.isNotEmpty()) item("most-played") {
                QuickPicksGrid(quickPicks, currentTrackId, isPlaying, favoriteIds, onPlay, onMenu, onToggleFavorite)
            }
            if (serviceLoading && shelves.isEmpty()) item("loading") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeHeading("Albums & singles", null)
                    LazyRow(state = loadingScroll, modifier = Modifier.mouseDragScroll(loadingScroll), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(10) { Box(Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh)) }
                    }
                }
            }
            items(shelves, key = { it.id }) { shelf ->
                HomeCarousel(shelf, currentTrackId, isPlaying, onPlay, onAlbum, onMenu, onAlbumMenu, playlistCovers)
            }
            if (libraryShelf.isNotEmpty() && shelves.none { it.title.equals("From your library", true) }) item("library") {
                HomeCarousel(DesktopHomeShelf("library", "From your library", albums = libraryShelf),
                    currentTrackId, isPlaying, onPlay, onAlbum, onMenu, onAlbumMenu, playlistCovers)
            }
            if (serviceError != null || (serviceShelves == null && state.error != null)) item("error") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(serviceError ?: state.error.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
            }
            if (loadingMore || canLoadMore) item("more") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (loadingMore) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = onLoadMore) { Text(if (loadMoreFailed) "Try loading more again" else "More music") }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp).alpha(scrollbarAlpha))
    }
}

@Composable
private fun HomeCarousel(shelf: DesktopHomeShelf, currentTrackId: String?, playing: Boolean,
    onPlay: (Track, List<Track>) -> Unit, onAlbum: (PlaylistResult) -> Unit,
    onMenu: (Track) -> Unit, onAlbumMenu: (PlaylistResult) -> Unit, playlistCovers: Map<String, Playlist>) {
    val order = shelf.order.ifEmpty { shelf.tracks.map { "track:${it.id}" } + shelf.albums.map { "album:${it.id}" } }
    val tracks = shelf.tracks.associateBy { it.id }; val albums = shelf.albums.associateBy { it.id }
    val row = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeHeading(shelf.title, shelf.subtitle, trailing = {
            if (row.canScrollBackward) IconButton(onClick = { scope.launch { row.animateScrollToItem((row.firstVisibleItemIndex - 4).coerceAtLeast(0)) } }) {
                Icon(Icons.Outlined.ChevronLeft, "Previous in ${shelf.title}")
            }
            if (row.canScrollForward) IconButton(onClick = { scope.launch { row.animateScrollToItem((row.firstVisibleItemIndex + 4).coerceAtMost(order.lastIndex)) } }) {
                Icon(Icons.Outlined.ChevronRight, "Next in ${shelf.title}")
            }
        })
        LazyRow(state = row, modifier = Modifier.mouseDragScroll(row), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(order, key = { it }) { key ->
                if (key.startsWith("track:")) tracks[key.removePrefix("track:")]?.let { track ->
                    ArtworkCard(track.title, track.artist, track.thumbnail, currentTrackId == track.id && playing,
                        { onPlay(track, shelf.tracks) }, { onMenu(track) })
                } else albums[key.removePrefix("album:")]?.let { album ->
                    ArtworkCard(album.title, album.author.orEmpty(), album.thumbnail, false,
                        { onAlbum(album) }, { onAlbumMenu(album) }, opensPage = true, playlistCover = playlistCovers[album.id])
                }
            }
        }
    }
}
@Composable
private fun HomeHeading(title: String, subtitle: String?, action: String? = null, onAction: () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 25.sp, lineHeight = 31.sp),
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
        trailing()
    }
}

@Composable
private fun ShortcutCard(item: SpeedDialItem, currentTrackId: String?, playing: Boolean, click: () -> Unit,
    menu: (() -> Unit)?, unpin: (() -> Unit)?, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val active = item.track?.id == currentTrackId && playing
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (hovered) colors.surfaceContainerHigh else colors.surfaceContainer, tween(150))
    val playAlpha by animateFloatAsState(if (active) 1f else 0f, tween(150))
    Box(modifier) {
      ContextMenuArea(items = { buildList {
          menu?.let { add(ContextMenuItem(if (item.type == SpeedDialType.ALBUM) "Album options" else "Song options", it)) }
          unpin?.let { add(ContextMenuItem("Unpin from Home", it)) }
      } }) {
        Row(Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(12.dp))
            .background(background)
            .hoverable(interactions).clickable(onClick = click), verticalAlignment = Alignment.CenterVertically) {
            HomeArtwork(item.image ?: item.track?.thumbnail, Modifier.size(78.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                    color = if (active) colors.primary else colors.onSurface)
                Text(item.track?.artist ?: when (item.type) {
                    SpeedDialType.ALBUM -> "Album"; SpeedDialType.PLAYLIST -> "Playlist"; SpeedDialType.ARTIST -> "Artist"; else -> "Song"
                }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(if (active) Icons.Outlined.GraphicEq else Icons.Outlined.PlayArrow,
                if (active) "Playing" else null, tint = colors.primary, modifier = Modifier.padding(end = 12.dp).size(20.dp).alpha(playAlpha))
        }
      }
    }
}

/**
 * "Good morning, Name" at the top of Home, by the time of day with Li Music's hours: night before
 * 5, morning before 12, afternoon before 18, evening after. Picked when Home opens, as Li Music
 * does (a greeting that flips while you look at it is uncanny).
 */
@Composable
private fun HomeGreeting(name: String?) {
    val part = remember {
        when (java.time.LocalTime.now().hour) {
            in 0..4 -> "Good night"; in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening"
        }
    }
    Text(if (name.isNullOrBlank()) part else "$part, ${name.trim()}",
        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp),
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
        // Sits closer to Recents than the 34dp between shelves (padding can't be negative).
        modifier = Modifier.padding(top = 6.dp).layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, (placeable.height - 18.dp.roundToPx()).coerceAtLeast(0)) { placeable.place(0, 0) }
        })
}

/** Quick picks: a numbered three-column grid of the most-played songs, with like and options. */
@Composable
private fun QuickPicksGrid(tracks: List<Track>, currentTrackId: String?, isPlaying: Boolean, favoriteIds: Set<String>,
    onPlay: (Track, List<Track>) -> Unit, onMenu: (Track) -> Unit, onToggleFavorite: (Track) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeHeading("Quick picks", null, trailing = {
            TextButton(onClick = { tracks.firstOrNull()?.let { onPlay(it, tracks) } }) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Play all")
            }
        })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            tracks.take(12).chunked(4).forEachIndexed { column, chunk ->
                Column(Modifier.weight(1f)) {
                    chunk.forEachIndexed { row, track ->
                        val active = track.id == currentTrackId && isPlaying
                        val liked = track.id in favoriteIds
                        ContextMenuArea(items = { listOf(ContextMenuItem("Song options") { onMenu(track) }) }) {
                            Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp))
                                .clickable { onPlay(track, tracks) }.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${column * 4 + row + 1}", Modifier.width(18.dp), style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
                                HomeArtwork(track.thumbnail, Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)))
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                                        color = if (active) colors.primary else colors.onSurface)
                                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant)
                                }
                                IconButton(onClick = { onToggleFavorite(track) }, modifier = Modifier.size(30.dp)) {
                                    Icon(if (liked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                        if (liked) "Remove from liked songs" else "Add to liked songs", Modifier.size(18.dp),
                                        tint = if (liked) colors.primary else colors.onSurfaceVariant)
                                }
                                IconButton(onClick = { onMenu(track) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Outlined.MoreHoriz, "Song options", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            // Keep columns the same width when there are fewer than twelve picks.
            repeat(3 - (tracks.take(12).size + 3) / 4) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun CompactSong(track: Track, active: Boolean, click: () -> Unit, menu: () -> Unit, modifier: Modifier) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val highlight by animateColorAsState(Color.White.copy(alpha = if (hovered) .08f else 0f),
        tween(180), label = "recentSongHighlight")
    Box(modifier) {
      ContextMenuArea(items = { listOf(ContextMenuItem("Song options", menu)) }) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(highlight).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = click).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeArtwork(track.thumbnail, Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = menu, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.MoreHoriz, "Song options", Modifier.size(18.dp)) }
        }
      }
    }
}

@Composable
private fun ArtworkCard(title: String, subtitle: String, image: String?, active: Boolean, click: () -> Unit, menu: (() -> Unit)?, opensPage: Boolean = false) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    ContextMenuArea(items = { menu?.let { listOf(ContextMenuItem(if (opensPage) "Album options" else "Song options", it)) }.orEmpty() }) {
        Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).hoverable(interactions).clickable(onClick = click)) {
            Box(Modifier.size(150.dp).clip(RoundedCornerShape(12.dp))) {
                HomeArtwork(image, Modifier.fillMaxSize())
                // No hover play/open button (the card itself is the click target); only the song
                // that's playing keeps a small "playing" badge.
                androidx.compose.animation.AnimatedVisibility(visible = active, enter = fadeIn(tween(150)), exit = fadeOut(tween(150)),
                    modifier = Modifier.align(Alignment.BottomEnd)) {
                  FilledIconButton(onClick = click, modifier = Modifier.padding(9.dp).size(36.dp)) {
                    Icon(Icons.Outlined.GraphicEq, "Playing")
                }
                }
            }
            Text(title, Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, Modifier.padding(top = 4.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeArtwork(url: String?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
        if (!url.isNullOrBlank()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
