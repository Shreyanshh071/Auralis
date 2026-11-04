package com.auralis.music.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.clickable
import com.auralis.music.ui.components.secondaryClick
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.UiLabels
import com.auralis.music.ui.i18n.str

/** One row of the Library list. A built-in collection has an [icon]; playlists show their cover. */
internal data class LibraryPaneRow(
    val playlist: Playlist,
    val subtitle: String,
    val icon: ImageVector? = null
)

// The pane sits on the app's artwork background, so its fills are tints of the text colour.
@Composable private fun faint(alpha: Float) = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)

/**
 * The left half of the Windows Library: everything saved, as a list. The chosen playlist opens
 * beside it instead of replacing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopLibraryPane(
    rows: List<LibraryPaneRow>,
    artists: List<SavedArtist>,
    selectedId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: LibrarySourceFilter?,
    onFilterChange: (LibrarySourceFilter?) -> Unit,
    sortOrder: String,
    sortOptions: List<String>,
    onSortChange: (String) -> Unit,
    onNewPlaylist: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onMenu: (Playlist) -> Unit,
    onOpenArtist: (SavedArtist) -> Unit,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxHeight().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(str(Strings.library), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            SortButton(sortOrder, sortOptions, onSortChange)
            PaneIconButton(Icons.Default.Add, str(Strings.new_playlist), onNewPlaylist)
        }
        Spacer(Modifier.height(10.dp))
        PaneSearchField(query, onQueryChange, Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(10.dp))
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LibrarySourceFilter.entries.forEach { option ->
                FilterChip(
                    when (option) {
                        LibrarySourceFilter.PLAYLISTS -> "Playlists"
                        LibrarySourceFilter.ALBUMS -> "Albums"
                        LibrarySourceFilter.ARTISTS -> "Artists"
                        LibrarySourceFilter.SPOTIFY -> str(Strings.spotify)
                        LibrarySourceFilter.YOUTUBE -> "YouTube"
                    },
                    selected = option == filter,
                    onClick = { onFilterChange(if (option == filter) null else option) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 20.dp)) {
            if (rows.isEmpty() && artists.isEmpty()) item(key = "empty") { empty() }
            items(rows, key = { it.playlist.id }) { row ->
                PaneRow(row, selected = row.playlist.id == selectedId, onClick = { onOpen(row.playlist) }, onMenu = { onMenu(row.playlist) })
            }
            items(artists, key = { "artist:${it.id}" }) { artist ->
                SavedArtistCard(artist, listMode = true, onClick = { onOpenArtist(artist) })
            }
        }
    }
}

@Composable
private fun PaneRow(row: LibraryPaneRow, selected: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when {
            selected -> faint(.14f)
            hovered -> faint(.07f)
            else -> Color.Transparent
        }, tween(120), label = "libraryPaneRow"
    )
    val playlist = row.playlist
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)).background(fill)
            .hoverable(hover).secondaryClick(onMenu)
            .clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (row.icon != null) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(faint(.1f)), contentAlignment = Alignment.Center) {
                Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
            }
        } else {
            val first = playlist.tracks.firstOrNull()
            ArtworkCard(
                url = playlist.coverUrl?.takeIf { it.isNotBlank() } ?: first?.thumbnail,
                fallbackTrack = first,
                modifier = Modifier.size(48.dp),
                cornerRadius = 8.dp,
                contentDescription = null
            )
        }
        Column(Modifier.weight(1f)) {
            Text(playlist.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PaneSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val text = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(faint(.08f)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(str(Strings.search_library_2), color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(query, onQueryChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = text, fontSize = 13.sp), cursorBrush = SolidColor(text))
        }
        if (query.isNotEmpty()) Icon(Icons.Default.Close, contentDescription = str(Strings.clear), tint = muted,
            modifier = Modifier.size(17.dp).clip(CircleShape).clickable { onQueryChange("") })
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.height(28.dp).clip(CircleShape).background(if (selected) colors.onBackground else faint(.08f))
            .clickable(onClick = onClick).padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            color = if (selected) colors.background else colors.onBackground)
    }
}

@Composable
private fun PaneIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SortButton(sortOrder: String, sortOptions: List<String>, onSortChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PaneIconButton(Icons.AutoMirrored.Filled.Sort, str(Strings.sort_playlists_by)) { open = true }
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
