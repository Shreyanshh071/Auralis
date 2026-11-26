package com.auralis.music.ui.explore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.data.network.ArtistPhotoProvider
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.HomeChip
import com.auralis.music.domain.model.HomeSection
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.ArtistAffinity
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.i18n.UiLabels
import com.auralis.music.ui.i18n.str

/** What the empty search page shows below recent searches and recently played. */
internal class SearchExploreContent(
    val chips: List<HomeChip>,
    val selectedChip: HomeChip?,
    /** The selected chip's shelves; null while they load. */
    val chipSections: List<HomeSection>?,
    val quickPicks: List<Track>,
    /** Most-replayed artists from measured plays, most first. */
    val topArtists: List<ArtistAffinity>,
    /** Home's own shelves, shown when no chip is selected. */
    val homeSections: List<HomeSection>
)

internal class SearchExploreActions(
    val onChip: (HomeChip?) -> Unit,
    val onPlay: (Track, List<Track>) -> Unit,
    val onTrackMenu: (Track) -> Unit,
    val onStartRadio: (Track) -> Unit,
    val onOpenArtist: (Artist) -> Unit,
    val onOpenAlbum: (PlaylistResult) -> Unit
)

/**
 * The taste profile keys plays by a song's whole artist text ("Pritam, Arijit Singh"). For an artist
 * row each song counts toward its first-billed artist, keeping the most-played song as the sample.
 */
internal fun primaryArtists(affinities: List<ArtistAffinity>): List<ArtistAffinity> {
    val split = Regex("(?i)\\s*(?:,|&|/|\\s+(?:feat\\.?|ft\\.?|featuring|with|x)\\s+)\\s*")
    return affinities.groupBy { it.artistName.split(split).first().trim() }
        .filterKeys { it.isNotBlank() }
        .map { (name, group) ->
            val top = group.maxBy { it.totalPlays }
            ArtistAffinity(name, group.sumOf { it.totalPlays }, group.sumOf { it.recentPlaysCount },
                group.sumOf { it.affinityScore.toDouble() }.toFloat(), top.sampleTrack)
        }
        .sortedByDescending { it.affinityScore }
}

/**
 * Explore sections on the search page: mood chips, Quick picks, an artist spotlight, your
 * most-replayed artists, then music shelves. A chip swaps the picks and shelves for that mood.
 */
internal fun LazyListScope.searchExploreSections(
    content: SearchExploreContent,
    actions: SearchExploreActions,
    currentTrackId: String?,
    isPlaying: Boolean
) {
    if (content.chips.isNotEmpty()) item(key = "explore_chips", contentType = "explore_chips") {
        ExploreChipRow(content.chips, content.selectedChip, actions.onChip)
    }
    val chip = content.selectedChip
    if (chip == null && content.quickPicks.isNotEmpty()) {
        item(key = "explore_quick_picks_header") { SearchSectionHeader(UiLabels.of("Quick picks")) }
        item(key = "explore_quick_picks", contentType = "explore_track_row") {
            ExploreTrackRow(content.quickPicks.take(20), currentTrackId, isPlaying, actions, artSize = 156.dp)
        }
    }
    val artists = primaryArtists(content.topArtists)
    if (chip == null) artists.firstOrNull()?.let { top ->
        item(key = "explore_spotlight", contentType = "explore_spotlight") { ArtistSpotlightCard(top, actions) }
    }
    val forYou = artists.take(12)
    if (chip == null && forYou.isNotEmpty()) {
        item(key = "explore_artists_header") {
            Column {
                SearchSectionHeader(str(R.string.search_artists_for_you))
                Text(str(R.string.search_artists_for_you_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }
        }
        item(key = "explore_artists", contentType = "explore_artist_row") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                items(forYou, key = { "fy_${it.artistName}" }) { artist -> ArtistForYou(artist) {
                    actions.onOpenArtist(Artist(id = "", name = artist.artistName))
                } }
            }
        }
    }
    val sections = if (chip == null) content.homeSections else content.chipSections
    if (sections == null) {
        item(key = "explore_chip_loading") {
            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
            }
        }
        return
    }
    sections.filter { it.items.isNotEmpty() || it.albums.isNotEmpty() }.take(8).forEach { section ->
        item(key = "explore_section_header_${section.id}") { SearchSectionHeader(section.title) }
        item(key = "explore_section_${section.id}", contentType = "explore_section_row") {
            if (section.items.isNotEmpty()) ExploreTrackRow(section.items.take(20), currentTrackId, isPlaying, actions)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                items(section.albums.take(20), key = { "al_${section.id}_${it.id}" }) { album ->
                    ExploreAlbumCard(album) { actions.onOpenAlbum(album) }
                }
            }
        }
    }
}

@Composable
private fun ExploreChipRow(chips: List<HomeChip>, selected: HomeChip?, onChip: (HomeChip?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)
    ) {
        item(key = "chip_all") { ExploreChip(str(R.string.search_explore_all), selected == null) { onChip(null) } }
        items(chips, key = { "chip_${it.title}" }) { chip ->
            ExploreChip(UiLabels.of(chip.title), selected == chip) { onChip(if (selected == chip) null else chip) }
        }
    }
}

@Composable
private fun ExploreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = label,
        color = if (selected) colors.onPrimaryContainer else colors.onSurface,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 15.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}

@Composable
private fun ExploreTrackRow(tracks: List<Track>, currentTrackId: String?, isPlaying: Boolean,
    actions: SearchExploreActions, artSize: androidx.compose.ui.unit.Dp = 124.dp) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.fillMaxWidth()) {
        items(tracks, key = { "et_${it.id}" }) { track ->
            RecentlyPlayedCard(track = track, isCurrent = track.id == currentTrackId, isPlaying = isPlaying,
                onClick = { actions.onPlay(track, tracks) }, onLongClick = { actions.onTrackMenu(track) }, artSize = artSize)
        }
    }
}

/** Resolves an artist's photo once, from the cache when it's there. */
@Composable
private fun artistPhoto(name: String): String? {
    val photo by produceState(ArtistPhotoProvider.getCachedPhoto(name), name) {
        if (value == null) value = runCatching { ArtistPhotoProvider.resolveArtistPhoto(name) }.getOrNull()
    }
    return photo
}

/** Your most-replayed artist: their photo, top song, and buttons for radio or their page. */
@Composable
private fun ArtistSpotlightCard(artist: ArtistAffinity, actions: SearchExploreActions) {
    val colors = MaterialTheme.colorScheme
    val photo = artistPhoto(artist.artistName)
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp).clip(shape)
            .background(colors.surfaceContainerHigh)
            .border(1.dp, colors.outlineVariant.copy(alpha = .35f), shape)
    ) {
        // A soft wash of the artist's photo behind the card.
        ArtworkCard(url = photo, modifier = Modifier.matchParentSize().blur(36.dp), cornerRadius = 0.dp,
            contentDescription = null)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(colors.surfaceContainerHigh.copy(alpha = .72f), colors.surfaceContainerHigh.copy(alpha = .92f)))))
        Column(Modifier.padding(18.dp)) {
            Text(str(R.string.search_artist_spotlight).uppercase(), color = colors.primary, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                modifier = Modifier.clip(CircleShape).background(colors.primary.copy(alpha = .12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkCard(url = photo, modifier = Modifier.size(84.dp), cornerRadius = 42.dp, contentDescription = artist.artistName)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist.artistName, color = colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    artist.sampleTrack?.let { song ->
                        Text(str(R.string.search_top_track, song.title), color = colors.onSurfaceVariant, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                artist.sampleTrack?.let { song ->
                    Row(
                        Modifier.weight(1f).clip(CircleShape).background(colors.primary)
                            .clickable { actions.onStartRadio(song) }.padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(str(R.string.search_artist_radio), color = colors.onPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
                Box(
                    Modifier.weight(1f).clip(CircleShape).background(colors.onSurface.copy(alpha = .08f))
                        .clickable { actions.onOpenArtist(Artist(id = "", name = artist.artistName)) }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(str(R.string.view_artist), color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun ArtistForYou(artist: ArtistAffinity, onClick: () -> Unit) {
    val photo = artistPhoto(artist.artistName)
    Column(
        Modifier.width(92.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArtworkCard(url = photo, modifier = Modifier.size(84.dp)
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .55f), CircleShape),
            cornerRadius = 42.dp, contentDescription = artist.artistName)
        Spacer(Modifier.height(8.dp))
        Text(artist.artistName, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreAlbumCard(album: PlaylistResult, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick).padding(4.dp)) {
        ArtworkCard(url = album.thumbnail, modifier = Modifier.size(132.dp), cornerRadius = 14.dp, contentDescription = album.title)
        Spacer(Modifier.height(8.dp))
        Text(album.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        album.author?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
