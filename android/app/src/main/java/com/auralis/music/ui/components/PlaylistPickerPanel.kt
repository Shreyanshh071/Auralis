package com.auralis.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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


/** Readable on real glass and on the normal theme's card, in light and dark. */
@Composable
private fun pickerForeground(): Color =
    LocalLiquidGlass.current?.let { glassContentColor(it.isDark) } ?: MaterialTheme.colorScheme.onSurface


/**
 * The sheet's title row, then what is being saved as its own card: artwork, name and artist.
 * [onBack] adds a back button.
 */
@Composable
internal fun PlaylistPickerHeader(title: String, subtitle: String?, artworkUrl: String?, onBack: (() -> Unit)? = null) {
    val foreground = pickerForeground()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(foreground.copy(alpha = .08f)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back), tint = foreground, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(title, color = foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (!artworkUrl.isNullOrBlank() || !subtitle.isNullOrBlank()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(foreground.copy(alpha = .07f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    ArtworkCard(url = artworkUrl, modifier = Modifier.size(44.dp), cornerRadius = 11.dp, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.saving), color = foreground.copy(alpha = .55f), fontSize = 11.sp, letterSpacing = 1.sp)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, color = foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * A grid of covers: "New playlist" first, then every playlist as a large cover with its name.
 * Playlists that already hold the song are darkened with a check and read "Saved".
 */
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
            Spacer(Modifier.height(14.dp))
        }
        val rows = (if (query.isBlank()) 1 else 0) + shown.size
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 470.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (query.isBlank()) item(key = "new_playlist", contentType = "new") {
                NewPlaylistRow(foreground, groupShape(0, rows), onCreate)
            }
            itemsIndexed(shown, key = { _, it -> it.id }, contentType = { _, _ -> "playlist" }) { index, playlist ->
                val position = index + if (query.isBlank()) 1 else 0
                PlaylistRow(playlist, isInPlaylist(playlist), foreground, groupShape(position, rows)) { onPick(playlist) }
            }
        }
    }
}

/** Joined cards: big outer corners on the first and last, tight joins between. */
private fun groupShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) 22.dp else 6.dp
    val bottom = if (index == count - 1) 22.dp else 6.dp
    return RoundedCornerShape(top, top, bottom, bottom)
}

@Composable
private fun PlaylistSearchField(query: String, onQueryChange: (String) -> Unit, foreground: Color) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(foreground.copy(alpha = .08f)).padding(horizontal = 16.dp),
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
private fun pressScale(interactions: MutableInteractionSource): Float {
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f,
        spring(dampingRatio = if (pressed) .8f else .5f, stiffness = 500f), label = "tile press")
    return scale
}

@Composable
private fun NewPlaylistRow(foreground: Color, shape: RoundedCornerShape, onClick: () -> Unit) {
    val accent = MaterialTheme.dynamicPrimary
    val interactions = remember { MutableInteractionSource() }
    val scale = pressScale(interactions)
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .background(accent.copy(alpha = .12f)).clickable(interactions, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(CoverSize).clip(CoverShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(str(R.string.new_playlist), color = foreground, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Text(str(R.string.add_this_song), color = foreground.copy(alpha = .55f), fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, added: Boolean, foreground: Color, shape: RoundedCornerShape, onClick: () -> Unit) {
    val accent = MaterialTheme.dynamicPrimary
    val interactions = remember { MutableInteractionSource() }
    val scale = pressScale(interactions)
    val pillFill by animateColorAsState(if (added) accent else foreground.copy(alpha = .10f), label = "saved pill")
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .background(foreground.copy(alpha = .07f)).clickable(interactions, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(CoverSize).clip(CoverShape)) {
            com.auralis.music.ui.library.PlaylistCoverArt(playlist, emptyList(), 12.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, color = foreground, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(str(R.string.x_songs, playlist.tracks.size), color = foreground.copy(alpha = .55f), fontSize = 12.5.sp)
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.height(34.dp).clip(CircleShape).background(pillFill).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (added) Icons.Default.Check else Icons.Default.Add,
                str(if (added) R.string.already_in_playlist else R.string.add),
                tint = if (added) MaterialTheme.colorScheme.onPrimary else foreground,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(str(if (added) R.string.saved else R.string.add), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (added) MaterialTheme.colorScheme.onPrimary else foreground)
        }
    }
}

private val CoverSize = 52.dp
private val CoverShape = RoundedCornerShape(12.dp)
