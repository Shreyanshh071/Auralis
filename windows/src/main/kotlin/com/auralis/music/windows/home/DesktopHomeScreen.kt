package com.auralis.music.windows.home

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier
) {
    val content = remember(state) { desktopHomeContent(state) }
    val hour = remember { LocalTime.now().hour }
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
    val colors = MaterialTheme.colorScheme
    val scroll = rememberLazyListState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = when { maxWidth >= 1000.dp -> 4; maxWidth >= 780.dp -> 3; else -> 2 }
        val songColumns = if (maxWidth >= 950.dp) 3 else if (maxWidth >= 650.dp) 2 else 1
        Box(Modifier.fillMaxWidth().height(290.dp).background(Brush.verticalGradient(
            listOf(colors.primary.copy(alpha = .12f), colors.background.copy(alpha = 0f)))))
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp)) {
            item("greeting") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(greeting, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(if (content.personalized) "Your music, with room for something new." else "Find your first favourite. We'll take it from there.",
                            style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = onSearch, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(18.dp, 12.dp)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)); Text("Find music")
                    }
                }
            }
            if (state.homeChips.isNotEmpty()) item("filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(state.selectedChip == null, { onChip(null) }, label = { Text("All") }) }
                    items(state.homeChips, key = { "${it.title}:${it.params}" }) { chip ->
                        FilterChip(state.selectedChip == chip, { onChip(chip) }, label = { Text(chip.title) })
                    }
                }
            }
            if (content.shortcuts.isNotEmpty()) item("shortcuts") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HomeHeading(if (content.personalized) "Your rotation" else "Start here",
                        if (content.personalized) "Recent favourites and your pinned music" else "Curated picks to get you started",
                        action = "Surprise me", onAction = onSurprise)
                    content.shortcuts.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { item ->
                                val pinned = item.isPinned || item.id in state.pinnedSpeedDialIds || item.track?.id in state.pinnedSpeedDialIds
                                ShortcutCard(item, currentTrackId, isPlaying, { onShortcut(item) },
                                    item.track?.let { track -> { onMenu(track) } } ?: item.album?.let { album -> { onAlbumMenu(album) } },
                                    if (pinned) ({ onUnpinShortcut(item) }) else null, Modifier.weight(1f))
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (!content.personalized && state.selectedChip == null) item("learning") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer)
                    .padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    Text("This is a starting mix. As you listen, your rotation fills with your songs and recommendations draw on your listening history.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (content.picks.isNotEmpty()) item("quick-picks") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HomeHeading("Quick picks", "Press play and let it run", action = "Play all",
                        onAction = { onPlay(content.picks.first(), content.picks) })
                    content.picks.chunked(songColumns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            row.forEach { track -> CompactSong(track, currentTrackId == track.id && isPlaying,
                                { onPlay(track, content.picks) }, { onMenu(track) }, Modifier.weight(1f)) }
                            repeat(songColumns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            items(content.shelves, key = { it.id }) { shelf ->
                val shelfScroll = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val count = shelf.tracks.size + shelf.albums.size
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeHeading(shelf.title, shelf.subtitle,
                        action = if (shelf.tracks.isNotEmpty()) "Play all" else null,
                        onAction = { shelf.tracks.firstOrNull()?.let { onPlay(it, shelf.tracks) } },
                        trailing = {
                            IconButton(enabled = shelfScroll.canScrollBackward, onClick = {
                                scope.launch { shelfScroll.animateScrollToItem((shelfScroll.firstVisibleItemIndex - 3).coerceAtLeast(0)) }
                            }) { Icon(Icons.Outlined.ChevronLeft, "Previous in ${shelf.title}") }
                            IconButton(enabled = shelfScroll.canScrollForward, onClick = {
                                scope.launch { shelfScroll.animateScrollToItem((shelfScroll.firstVisibleItemIndex + 3).coerceAtMost(count - 1)) }
                            }) { Icon(Icons.Outlined.ChevronRight, "Next in ${shelf.title}") }
                        })
                    LazyRow(state = shelfScroll, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(shelf.tracks, key = { "track:${it.id}" }) { track ->
                            ArtworkCard(track.title, track.artist, track.thumbnail, currentTrackId == track.id && isPlaying,
                                { onPlay(track, shelf.tracks) }, { onMenu(track) })
                        }
                        items(shelf.albums, key = { "album:${it.id}" }) { album ->
                            ArtworkCard(album.title, album.author.orEmpty(), album.thumbnail, false, { onAlbum(album) }, { onAlbumMenu(album) }, opensPage = true)
                        }
                    }
                }
            }
            if (state.isLoading || state.isRefreshing) item("loading") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Updating your recommendations…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), modifier = Modifier.align(Alignment.CenterEnd)
            .fillMaxHeight().padding(vertical = 16.dp, horizontal = 6.dp),
            style = LocalScrollbarStyle.current.copy(thickness = 6.dp, shape = RoundedCornerShape(8.dp),
                unhoverColor = colors.onSurfaceVariant.copy(alpha = .35f), hoverColor = colors.primary.copy(alpha = .7f)))
    }
}

@Composable
private fun HomeHeading(title: String, subtitle: String?, action: String? = null, onAction: () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val playAlpha by animateFloatAsState(if (hovered || active) 1f else 0f, tween(150))
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

@Composable
private fun CompactSong(track: Track, active: Boolean, click: () -> Unit, menu: () -> Unit, modifier: Modifier) {
    Box(modifier) {
      ContextMenuArea(items = { listOf(ContextMenuItem("Song options", menu)) }) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = click).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeArtwork(track.thumbnail, Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
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
        Column(Modifier.width(156.dp).clip(RoundedCornerShape(12.dp)).hoverable(interactions).clickable(onClick = click)) {
            Box(Modifier.size(156.dp).clip(RoundedCornerShape(12.dp))) {
                HomeArtwork(image, Modifier.fillMaxSize())
                androidx.compose.animation.AnimatedVisibility(visible = hovered || active, enter = fadeIn(tween(150)), exit = fadeOut(tween(150)),
                    modifier = Modifier.align(Alignment.BottomEnd)) {
                  FilledIconButton(onClick = click, modifier = Modifier.padding(9.dp).size(36.dp)) {
                    Icon(when { active -> Icons.Outlined.GraphicEq; opensPage -> Icons.Outlined.ArrowOutward; else -> Icons.Outlined.PlayArrow },
                        when { active -> "Playing"; opensPage -> "Open"; else -> "Play" })
                }
                }
            }
            Text(title, Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, Modifier.padding(top = 4.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
