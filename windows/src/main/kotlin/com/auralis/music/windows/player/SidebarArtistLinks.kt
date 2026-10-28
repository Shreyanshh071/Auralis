package com.auralis.music.windows.player

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.recommendations.SimilarSeedPlanner
import com.auralis.music.ui.components.clickable

/** Prefer actual performer credits, preserving punctuation inside a band's name. */
internal fun sidebarArtists(credit: String, people: List<SongCredit>): List<Artist> {
    val performers = people.filter { "Main Artist" in it.roles }
    val matches = performers.mapNotNull { person ->
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(person.name)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .find(credit)?.let { it.range to person }
    }.sortedBy { it.first.first }
    val remaining = StringBuilder(credit).also { text ->
        matches.asReversed().forEach { (range, _) ->
            for (index in range) text.setCharAt(index, ' ')
        }
    }.toString().replace(Regex("\\b(?:feat|ft|featuring|with|and|x)\\b", RegexOption.IGNORE_CASE), "")
    val names = if (matches.isNotEmpty() && remaining.none(Char::isLetterOrDigit)) matches.map { it.second.name }
        else SimilarSeedPlanner.splitArtistCredit(credit)
    return names.distinctBy { it.lowercase() }.map { name ->
        Artist(performers.firstOrNull { it.name.equals(name, true) }?.channelId.orEmpty(), name)
    }
}

internal fun SongCredit.profileArtist(): Artist? =
    if (!channelId.isNullOrBlank() || "Main Artist" in roles) Artist(channelId.orEmpty(), name) else null

@Composable
internal fun SidebarArtistLink(artist: Artist, onOpen: (Artist) -> Unit,
    modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.White.copy(alpha = .75f)) {
    val source = remember(artist) { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Text(artist.name, modifier.clickable(interactionSource = source, indication = null, role = Role.Button) { onOpen(artist) },
        style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textDecoration = if (hovered) TextDecoration.Underline else null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SidebarArtistNames(artists: List<Artist>, onOpen: (Artist) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, maxLines = 2) {
        artists.forEachIndexed { index, artist ->
            SidebarArtistLink(artist, onOpen)
            if (index < artists.lastIndex) Text(", ", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .75f))
        }
    }
}
