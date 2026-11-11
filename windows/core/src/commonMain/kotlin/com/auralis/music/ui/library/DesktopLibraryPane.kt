package com.auralis.music.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.clickable
import com.auralis.music.ui.components.reportTypingFocus
import com.auralis.music.ui.components.secondaryClick
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.UiLabels
import com.auralis.music.ui.i18n.str

/** One Library entry. A built-in collection (Liked, Downloads…) has an [icon]; playlists show their cover. */
internal data class LibraryPaneRow(
    val playlist: Playlist,
    val subtitle: String,
    val icon: ImageVector? = null
)

/**
 * One-line labels inside pills: the font's built-in descent sat the text low in the pill, so the
 * line box is trimmed to the glyphs and the glyphs centred in it.
 */
private val CenteredLine = TextStyle(
    lineHeight = 1.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
)

// The page sits on the app's artwork background, so its fills are tints of the text colour.
@Composable private fun faint(alpha: Float) = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)

/** Each built-in collection keeps its own colour, so Liked, Downloads and Most played tell apart at a glance. */
private fun collectionBrush(id: String): Brush = Brush.linearGradient(
    when {
        id.contains("liked") -> listOf(Color(0xFF3D2BC8), Color(0xFF7B6CFF), Color(0xFFB9C4FF))
        id.contains("downloaded") -> listOf(Color(0xFF0B6B57), Color(0xFF17A889), Color(0xFF8CE3C8))
        id.contains("top") -> listOf(Color(0xFFB0261E), Color(0xFFE5542F), Color(0xFFFFB27A))
        id.contains("weekly") || id.contains("monthly") -> listOf(Color(0xFF8A1F6B), Color(0xFFD14C9A), Color(0xFFFFB3D9))
        else -> listOf(Color(0xFF29313F), Color(0xFF4D5A73), Color(0xFF9AA8C4))
    }
)

/**
 * The Windows Library: one full-width page. Built-in collections sit in a row of colour cards on
 * top, then every playlist and album as a cover grid, then saved artists. Opening one replaces this
 * page; its Back button returns here. No second sidebar beside the app's own.
 */
@Composable
internal fun DesktopLibraryHome(
    rows: List<LibraryPaneRow>,
    artists: List<SavedArtist>,
    savedAlbums: List<SavedAlbum>,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: LibrarySourceFilter?,
    onFilterChange: (LibrarySourceFilter?) -> Unit,
    sortOrder: String,
    sortOptions: List<String>,
    onSortChange: (String) -> Unit,
    onNewPlaylist: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
    onMenu: (Playlist) -> Unit,
    onOpenArtist: (SavedArtist) -> Unit,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val collections = rows.filter { it.icon != null }
    val playlists = rows.filter { it.icon == null }
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(172.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(key = "header", span = full) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(str(Strings.library), fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                    DesktopPaneSearchField(query, onQueryChange, str(Strings.search_library_2), Modifier.width(260.dp))
                    Spacer(Modifier.width(8.dp))
                    SortButton(sortOrder, sortOptions, onSortChange)
                    Spacer(Modifier.width(8.dp))
                    NewPlaylistButton(onNewPlaylist)
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("All", selected = filter == null) { onFilterChange(null) }
                    LibrarySourceFilter.entries.forEach { option ->
                        FilterChip(
                            when (option) {
                                LibrarySourceFilter.PLAYLISTS -> "Playlists"
                                LibrarySourceFilter.ALBUMS -> "Albums"
                                LibrarySourceFilter.ARTISTS -> "Artists"
                                LibrarySourceFilter.SPOTIFY -> str(Strings.spotify)
                                LibrarySourceFilter.YOUTUBE -> "YouTube Music"
                            },
                            selected = option == filter
                        ) { onFilterChange(if (option == filter) null else option) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        if (rows.isEmpty() && artists.isEmpty()) item(key = "empty", span = full) { empty() }

        if (collections.isNotEmpty()) item(key = "collections", span = full) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp)) {
                val perRow = (maxWidth / 250.dp).toInt().coerceIn(1, 4)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    collections.chunked(perRow).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            line.forEach { row ->
                                CollectionCard(row, Modifier.weight(1f), onOpen = { onOpen(row.playlist) },
                                    onPlay = { onPlay(row.playlist) }, onMenu = { onMenu(row.playlist) })
                            }
                            repeat(perRow - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item(key = "label:playlists", span = full) {
                SectionTitle(if (filter == LibrarySourceFilter.ALBUMS) "Albums" else "Playlists & albums", playlists.size)
            }
            items(playlists, key = { it.playlist.id }) { row ->
                CoverCard(row, savedAlbums, onOpen = { onOpen(row.playlist) }, onPlay = { onPlay(row.playlist) },
                    onMenu = { onMenu(row.playlist) })
            }
        }

        if (artists.isNotEmpty()) {
            item(key = "label:artists", span = full) { SectionTitle("Artists", artists.size) }
            items(artists, key = { "artist:${it.id}" }) { artist -> ArtistCard(artist) { onOpenArtist(artist) } }
        }
    }
}

@Composable
private fun SectionTitle(text: String, count: Int) {
    Row(Modifier.padding(start = 10.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(10.dp))
        Text("$count", fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** A built-in collection: a wide colour card with its icon, name and count. */
@Composable
private fun CollectionCard(row: LibraryPaneRow, modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit, onMenu: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) 1.015f else 1f, tween(160), label = "collectionLift")
    Box(
        modifier.height(112.dp).graphicsLayer { scaleX = lift; scaleY = lift }
            .shadow(if (hovered) 18.dp else 6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)).background(collectionBrush(row.playlist.id))
            .hoverable(hover).secondaryClick(onMenu)
            .clickable(interactionSource = hover, indication = null, onClick = onOpen)
    ) {
        // A soft dark wash at the bottom keeps the white text readable on the light end of the gradient.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .28f)))))
        Icon(row.icon!!, null, tint = Color.White.copy(alpha = .22f),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp).size(64.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 14.dp, end = 72.dp)) {
            Text(row.playlist.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.subtitle, fontSize = 13.sp, color = Color.White.copy(alpha = .82f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.playlist.tracks.isNotEmpty()) HoverPlay(hovered, onPlay, Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

/** A playlist or album: the cover, its name and one line about it. */
@Composable
private fun CoverCard(row: LibraryPaneRow, savedAlbums: List<SavedAlbum>, onOpen: () -> Unit, onPlay: () -> Unit, onMenu: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) faint(.08f) else Color.Transparent, tween(140), label = "coverCardFill")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(fill)
            .hoverable(hover).secondaryClick(onMenu)
            .clickable(interactionSource = hover, indication = null, onClick = onOpen)
            .padding(10.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).shadow(10.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))) {
            PlaylistCoverArt(row.playlist, savedAlbums, 10.dp)
            if (row.playlist.tracks.isNotEmpty()) HoverPlay(hovered, onPlay, Modifier.align(Alignment.BottomEnd).padding(10.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(row.playlist.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(row.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArtistCard(artist: SavedArtist, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) faint(.08f) else Color.Transparent, tween(140), label = "artistCardFill")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).shadow(10.dp, CircleShape).clip(CircleShape)) {
            ArtworkCard(url = artist.thumbnail, fallbackTrack = null, modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp, contentDescription = artist.name)
        }
        Spacer(Modifier.height(10.dp))
        Text(artist.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Artist", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The round play button that rises into a card's corner on hover. */
@Composable
private fun HoverPlay(visible: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(160), label = "hoverPlay")
    if (shown > 0f) Box(
        modifier.graphicsLayer { alpha = shown; translationY = (1f - shown) * 10.dp.toPx() }
            .size(46.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.PlayArrow, str(Strings.play), tint = Color.Black, modifier = Modifier.size(26.dp))
    }
}

/** The pill search box used by the Library page and the toolbar over a playlist's songs. */
@Composable
internal fun DesktopPaneSearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val text = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().height(38.dp).clip(CircleShape).background(faint(.09f)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(placeholder, color = muted, style = CenteredLine, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(query, onQueryChange, singleLine = true, modifier = Modifier.fillMaxWidth().reportTypingFocus(),
                textStyle = CenteredLine.copy(color = text, fontSize = 13.sp), cursorBrush = SolidColor(text))
        }
        if (query.isNotEmpty()) Icon(Icons.Default.Close, contentDescription = str(Strings.clear), tint = muted,
            modifier = Modifier.size(17.dp).clip(CircleShape).clickable { onQueryChange("") })
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.height(34.dp).clip(CircleShape).background(if (selected) colors.onBackground else faint(.09f))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = CenteredLine, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (selected) Color(0xFF111114) else colors.onBackground)
    }
}

@Composable
private fun NewPlaylistButton(onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground)
            .clickable(onClick = onClick).padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Add, null, tint = Color(0xFF111114), modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        Text(str(Strings.new_playlist), style = CenteredLine, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111114))
    }
}

@Composable
private fun SortButton(sortOrder: String, sortOptions: List<String>, onSortChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(38.dp).clip(CircleShape).background(faint(.09f)).clickable { open = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.Sort, str(Strings.sort_playlists_by), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(19.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            sortOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(UiLabels.of(option)) },
                    trailingIcon = if (option == sortOrder) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                    onClick = { open = false; onSortChange(option) }
                )
            }
        }
    }
}
