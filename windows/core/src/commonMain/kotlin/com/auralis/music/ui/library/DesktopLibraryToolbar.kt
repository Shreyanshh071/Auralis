package com.auralis.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.components.clickable
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.UiLabels
import com.auralis.music.ui.i18n.str

/** The art in the grid sits 10dp inside each card, so 18dp of grid padding lines up with this. */
internal val LIBRARY_PAGE_MARGIN = 28.dp

/** The Windows Library header: title, search, filters, sort, layout and New playlist together. */
@Composable
internal fun DesktopLibraryToolbar(
    itemCount: Int,
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    sortOrder: String,
    sortOptions: List<String>,
    onSortChange: (String) -> Unit,
    isGridView: Boolean,
    onToggleLayout: () -> Unit,
    onNewPlaylist: () -> Unit,
    filters: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 560.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR MUSIC, IN ONE PLACE", fontSize = 10.sp, letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(title, fontSize = 42.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("$itemCount items in your collection", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NewPlaylistButton(onNewPlaylist)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LibrarySearchField(query, onQueryChange)
                if (!narrow) {
                    Spacer(Modifier.weight(1f))
                    SortAndLayout(sortOrder, sortOptions, onSortChange, isGridView, onToggleLayout)
                }
            }
            if (narrow) SortAndLayout(sortOrder, sortOptions, onSortChange, isGridView, onToggleLayout)
            // Narrow panels retain the navigation as a horizontal strip.
            filters()
        }
    }
}


@Composable
private fun LibrarySearchField(query: String, onQueryChange: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.width(230.dp).height(40.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(str(Strings.search_library_2), color = muted, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(query, onQueryChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = onSurface, fontSize = 14.sp), cursorBrush = SolidColor(onSurface))
        }
        if (query.isNotEmpty()) Icon(Icons.Default.Close, contentDescription = str(Strings.clear), tint = muted,
            modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onQueryChange("") })
    }
}

@Composable
private fun SortAndLayout(
    sortOrder: String,
    sortOptions: List<String>,
    onSortChange: (String) -> Unit,
    isGridView: Boolean,
    onToggleLayout: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Row(
                Modifier.height(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { open = true }.padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(UiLabels.of(sortOrder), color = onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = str(Strings.sort_playlists_by),
                    tint = onSurface, modifier = Modifier.size(20.dp))
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
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onToggleLayout),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                // Shows the layout a click switches to.
                if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                contentDescription = if (isGridView) str(Strings.switch_to_list_view) else str(Strings.switch_to_grid_view),
                tint = onSurface, modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun NewPlaylistButton(onClick: () -> Unit) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick).padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = onPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(str(Strings.new_playlist), color = onPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A titled group on the Library page, with how many items it holds. */
@Composable
internal fun LibrarySectionHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** Dedicated navigation pane, independent of the scrollable cover browser. */
@Composable
internal fun DesktopLibraryRail(
    selected: LibrarySourceFilter?,
    onSelect: (LibrarySourceFilter?) -> Unit,
    favoriteCount: Int,
    showLiked: Boolean,
    onLiked: () -> Unit,
    onNew: () -> Unit,
    collections: @Composable () -> Unit
) {
    Column(Modifier.width(194.dp).fillMaxHeight()
        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .035f))
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("BROWSE LIBRARY", fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp, bottom = 16.dp))
        LibraryRailEntry("Overview", Icons.Default.LibraryMusic, selected == null) { onSelect(null) }
        LibraryRailEntry("Playlists", Icons.Default.GridView, selected == LibrarySourceFilter.PLAYLISTS) { onSelect(LibrarySourceFilter.PLAYLISTS) }
        LibraryRailEntry("Albums", Icons.Default.Album, selected == LibrarySourceFilter.ALBUMS) { onSelect(LibrarySourceFilter.ALBUMS) }
        LibraryRailEntry("Artists", Icons.Default.Person, selected == LibrarySourceFilter.ARTISTS) { onSelect(LibrarySourceFilter.ARTISTS) }
        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .09f))
        Text("CONNECTED SOURCES", fontSize = 10.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp))
        LibraryRailEntry("Spotify", Icons.Default.PlayArrow, selected == LibrarySourceFilter.SPOTIFY) { onSelect(LibrarySourceFilter.SPOTIFY) }
        LibraryRailEntry("YouTube Music", Icons.Default.PlayArrow, selected == LibrarySourceFilter.YOUTUBE) { onSelect(LibrarySourceFilter.YOUTUBE) }
        Spacer(Modifier.height(28.dp))
        if (showLiked) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .24f), MaterialTheme.colorScheme.primary.copy(alpha = .06f))))
            .clickable(onClick = onLiked).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text("Liked songs", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("$favoriteCount favorites", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        collections()
        Spacer(Modifier.height(16.dp))
        LibraryRailEntry("Create playlist", Icons.Default.Add, false, onNew)
    }
}

@Composable
internal fun LibraryRailEntry(title: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .15f) else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(title, color = tint, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
internal fun LibrarySpotlight(playlist: com.auralis.music.domain.model.Playlist, onOpen: () -> Unit, onPlay: (() -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        val compact = maxWidth < 600.dp
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .23f), MaterialTheme.colorScheme.primary.copy(alpha = .04f))))
            .padding(if (compact) 20.dp else 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (!compact) com.auralis.music.ui.components.ArtworkCard(
                url = playlist.coverUrl ?: playlist.tracks.firstOrNull()?.thumbnail,
                fallbackTrack = playlist.tracks.firstOrNull(),
                modifier = Modifier.size(154.dp), cornerRadius = 12.dp, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("FROM YOUR COLLECTION", fontSize = 10.sp, letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(playlist.title, fontSize = if (compact) 28.sp else 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(str(Strings.x_songs, playlist.tracks.size), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onPlay != null) Row(Modifier.height(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlay).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        Text("Play", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Box(Modifier.height(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground.copy(alpha = .08f))
                        .clickable(onClick = onOpen).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text("Open playlist", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
