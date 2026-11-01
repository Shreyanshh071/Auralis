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

/** Everything fits on one line from this width; narrower panels move the filters to a second line. */
private val ONE_LINE_TOOLBAR_MIN_WIDTH = 1240.dp

/** The art in the grid sits 10dp inside each card, so 18dp of grid padding lines up with this. */
internal val LIBRARY_PAGE_MARGIN = 28.dp

/** The Windows Library header: title, search, filters, sort, layout and New playlist together. */
@Composable
internal fun DesktopLibraryToolbar(
    itemCount: Int,
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
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = LIBRARY_PAGE_MARGIN, end = LIBRARY_PAGE_MARGIN, top = 18.dp, bottom = 8.dp)) {
        val oneLine = maxWidth >= ONE_LINE_TOOLBAR_MIN_WIDTH
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(str(Strings.library), fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                Text(itemCount.toString(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.width(24.dp))
                if (oneLine) {
                    Box(Modifier.weight(1f)) { filters() }
                    Spacer(Modifier.width(16.dp))
                    SortAndLayout(sortOrder, sortOptions, onSortChange, isGridView, onToggleLayout)
                    Spacer(Modifier.width(8.dp))
                } else Spacer(Modifier.weight(1f))
                LibrarySearchField(query, onQueryChange)
                Spacer(Modifier.width(8.dp))
                NewPlaylistButton(onNewPlaylist)
            }
            if (!oneLine) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { filters() }
                Spacer(Modifier.width(16.dp))
                SortAndLayout(sortOrder, sortOptions, onSortChange, isGridView, onToggleLayout)
            }
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
