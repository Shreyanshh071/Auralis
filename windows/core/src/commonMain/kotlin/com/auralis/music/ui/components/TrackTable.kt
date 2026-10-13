package com.auralis.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.Track

/** Where a song row's artist and album links go; null where the screen has no browsing (rows stay plain). */
class TrackLinks(val openArtist: (String) -> Unit, val openAlbum: (Track) -> Unit)

val LocalTrackLinks = staticCompositionLocalOf<TrackLinks?> { null }

/** At most two album lookups at once: iTunes rate-limits bursts from a long playlist. */
private val albumLookups = kotlinx.coroutines.sync.Semaphore(2)

/** Width of the length column in song tables, so the header's clock sits over the times. */
val TrackTableTimeWidth = 56.dp

private fun titleKey(text: String) = text.lowercase().replace(Regex("""[^\p{L}\p{Nd}]"""), "")

/**
 * The album to show for [track], or null for "—": no album, or a single whose album is just the
 * song's own name ("did i tell u that i miss u" on "did i tell u that i miss u").
 */
fun trackAlbumLabel(track: Track): String? {
    val album = track.album?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val a = titleKey(album)
    val t = titleKey(track.title)
    if (a.isEmpty() || t.isEmpty()) return album
    return if (a == t || a.startsWith(t) || t.startsWith(a)) null else album
}

/** Splits "Gorillaz, De La Soul" / "Gorillaz & De La Soul" into separately linked names. */
fun splitArtists(artist: String): List<String> =
    artist.split(Regex("""\s*(?:,|&| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }

/** The column titles over a song table: #, Title, Artist, Album, and a clock over the lengths. */
@Composable
fun TrackTableHeader(
    leading: Dp,
    trailing: Dp,
    showNumber: Boolean,
    modifier: Modifier = Modifier
) {
    val style = MaterialTheme.typography.labelLarge
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showNumber) Box(Modifier.width(32.dp)) { Text("#", style = style, color = colour) }
            Spacer(Modifier.width(leading))
            Text("Title", style = style, color = colour, modifier = Modifier.weight(1.5f))
            Text("Artist", style = style, color = colour, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Text("Album", style = style, color = colour, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Box(Modifier.width(TrackTableTimeWidth), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Outlined.Schedule, "Length", tint = colour, modifier = Modifier.padding(end = 8.dp).size(16.dp))
            }
            Spacer(Modifier.width(trailing))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    }
}

/** The artist and album cells of a wide song row; names underline on hover and open their pages. */
@Composable
fun RowScope.TrackLinkCells(track: Track, fallbackArtist: String? = null) {
    val links = LocalTrackLinks.current
    val style = MaterialTheme.typography.bodyMedium
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.weight(1f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val names = splitArtists(track.artist.ifBlank { fallbackArtist.orEmpty() })
        names.forEachIndexed { index, name ->
            LinkText(name, style, colour, links?.let { { it.openArtist(name) } }, Modifier.weight(1f, fill = false))
            if (index < names.lastIndex) Text(", ", style = style, color = colour, maxLines = 1)
        }
    }
    // A single or compilation tag isn't the song's album ("Love Me Not / Love Is Blind" for a
    // Bird's Eye song): look up the parent album, as search does, and show and link that.
    val needsLookup = remember(track.id, track.album) {
        com.auralis.music.data.network.AlbumMetadataResolver.needsResolving(track.album, track.title)
    }
    var resolved by remember(track.id) {
        mutableStateOf(if (needsLookup) com.auralis.music.data.network.AlbumMetadataResolver.getCached(track.title, track.artist, track.album) else null)
    }
    LaunchedEffect(track.id) {
        if (needsLookup && resolved == null) resolved = albumLookups.withPermit {
            runCatching { com.auralis.music.data.network.AlbumMetadataResolver.resolveAlbum(track.title, track.artist, track.album) }.getOrNull()
        }
    }
    val parent = resolved?.takeIf { !it.isSingle && it.albumTitle.isNotBlank() }
    val albumTrack = if (parent != null) track.copy(album = parent.albumTitle, albumId = parent.albumId ?: track.albumId) else track
    val album = parent?.albumTitle ?: trackAlbumLabel(track)
    Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
        if (album == null) Text("—", style = style, color = colour)
        else LinkText(album, style, colour, links?.let { { it.openAlbum(albumTrack) } })
    }
}

@Composable
private fun LinkText(text: String, style: TextStyle, colour: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Text(
        text, style = style.copy(textDecoration = if (hovered && onClick != null) TextDecoration.Underline else null),
        color = com.auralis.music.ui.components.smoothHoverColor(if (hovered && onClick != null) MaterialTheme.colorScheme.onSurface else colour),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.hoverable(hover).then(if (onClick != null) Modifier.clickable(hover, indication = null, onClick = onClick) else Modifier)
    )
}

/** [TrackLinkCells] for callers that hold the row scope as a value. */
@Composable
fun TrackLinkCellsRow(scope: RowScope, track: Track, fallbackArtist: String? = null) = with(scope) { TrackLinkCells(track, fallbackArtist) }

/** Song length in the table's fixed-width column. */
@Composable
fun TrackTimeCell(seconds: Long) {
    val text = if (seconds > 0) "%d:%02d".format(seconds / 60, seconds % 60) else ""
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(TrackTableTimeWidth).padding(end = 8.dp))
}
