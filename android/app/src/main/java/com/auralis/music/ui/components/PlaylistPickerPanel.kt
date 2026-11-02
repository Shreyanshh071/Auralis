package com.auralis.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.domain.model.Playlist
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.glass.glassContentColor
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicPrimary

/** Past this many playlists the list gets a search field. */
private const val SEARCH_FROM_PLAYLISTS = 6

private val RowShape = RoundedCornerShape(18.dp)
private val CoverSize = 54.dp

/** Readable on real glass and on the normal theme's card, in light and dark. */
@Composable
private fun pickerForeground(): Color =
    LocalLiquidGlass.current?.let { glassContentColor(it.isDark) } ?: MaterialTheme.colorScheme.onSurface

/** What's being added: its artwork and name, under the sheet's title. [onBack] adds a back button. */
@Composable
internal fun PlaylistPickerHeader(title: String, subtitle: String?, artworkUrl: String?, onBack: (() -> Unit)? = null) {
    val foreground = pickerForeground()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(foreground.copy(alpha = .08f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back), tint = foreground, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        if (!artworkUrl.isNullOrBlank()) {
            ArtworkCard(url = artworkUrl, modifier = Modifier.size(46.dp), cornerRadius = 12.dp, contentDescription = null)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = foreground.copy(alpha = .62f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** New playlist first, then every playlist, with one mark showing what's in and what can be added. */
@Composable
internal fun PlaylistPickerList(
    playlists: List<Playlist>,
    isInPlaylist: (Playlist) -> Boolean,
    onCreate: () -> Unit,
    onPick: (Playlist) -> Unit
) {
    val foreground = pickerForeground()
    var query by remember { mutableStateOf("") }
    val shown = remember(playlists, query) {
        if (query.isBlank()) playlists else playlists.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxWidth()) {
        if (playlists.size > SEARCH_FROM_PLAYLISTS) {
            PlaylistSearchField(query, { query = it }, foreground)
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 420.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "new_playlist", contentType = "new") { NewPlaylistRow(onCreate) }
            items(shown, key = { it.id }, contentType = { "playlist" }) { playlist ->
                PlaylistPickRow(playlist, isInPlaylist(playlist), foreground) { onPick(playlist) }
            }
        }
    }
}

@Composable
private fun PlaylistSearchField(query: String, onQueryChange: (String) -> Unit, foreground: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(foreground.copy(alpha = .08f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = foreground.copy(alpha = .6f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(str(R.string.search), color = foreground.copy(alpha = .5f), fontSize = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = foreground, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.dynamicPrimary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NewPlaylistRow(onClick: () -> Unit) {
    val accent = MaterialTheme.dynamicPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(CoverSize).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = .18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, null, tint = accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(str(R.string.new_playlist), color = accent, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlaylistPickRow(playlist: Playlist, added: Boolean, foreground: Color, onClick: () -> Unit) {
    val accent = MaterialTheme.dynamicPrimary
    val markFill by animateColorAsState(if (added) accent else foreground.copy(alpha = .09f), label = "playlistMark")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkCard(
            url = playlist.coverUrl ?: playlist.tracks.firstOrNull()?.thumbnail,
            modifier = Modifier.size(CoverSize),
            cornerRadius = 14.dp,
            sizeToConstraints = true,
            crossfade = false,
            contentDescription = playlist.title
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, color = foreground, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(str(R.string.x_songs, playlist.tracks.size), color = foreground.copy(alpha = .6f), fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(32.dp).clip(CircleShape).background(markFill), contentAlignment = Alignment.Center) {
            Icon(
                if (added) Icons.Default.Check else Icons.Default.Add,
                str(if (added) R.string.already_in_playlist else R.string.add),
                tint = if (added) MaterialTheme.colorScheme.onPrimary else foreground.copy(alpha = .8f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
