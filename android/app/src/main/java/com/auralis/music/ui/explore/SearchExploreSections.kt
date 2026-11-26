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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import com.auralis.music.ui.glass.LiquidGlassContext
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.glass.glassBarTint
import com.auralis.music.ui.glass.glassContentColor
import com.auralis.music.ui.glass.liquidGlass
import com.auralis.music.ui.glass.pageBackgroundGlass
import com.auralis.music.ui.theme.dynamicPrimary
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
    // The explore area gets its own heading, so the chips belong to what follows them.
    if (content.chips.isNotEmpty()) {
        item(key = "explore_header") { SearchSectionHeader(str(R.string.opt_explore)) }
        item(key = "explore_chips", contentType = "explore_chips") {
            ExploreChipRow(content.chips, content.selectedChip, actions.onChip)
        }
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

/** An icon for a YouTube Music mood chip, by its (English) title. */
private fun moodIcon(title: String): ImageVector {
    val t = title.lowercase()
    return when {
        "podcast" in t -> Icons.Rounded.Podcasts
        "romance" in t || "love" in t -> Icons.Rounded.Favorite
        "feel good" in t || "happy" in t -> Icons.Rounded.SentimentSatisfied
        "party" in t -> Icons.Rounded.Celebration
        "work" in t && "out" in t || "gym" in t -> Icons.Rounded.FitnessCenter
        "relax" in t || "chill" in t -> Icons.Rounded.Spa
        "energ" in t -> Icons.Rounded.Bolt
        "focus" in t -> Icons.Rounded.CenterFocusStrong
        "sleep" in t -> Icons.Rounded.Bedtime
        "commute" in t || "drive" in t -> Icons.Rounded.DirectionsCar
        "sad" in t -> Icons.Rounded.WaterDrop
        else -> Icons.Rounded.MusicNote
    }
}

/** The chips sample the Search page's background without painting a separate strip. */
@Composable
private fun ExploreChipRow(chips: List<HomeChip>, selected: HomeChip?, onChip: (HomeChip?) -> Unit) {
    val glass = pageBackgroundGlass()
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
    ) {
        item(key = "chip_all") {
            ExploreChip(str(R.string.search_explore_all), Icons.Rounded.AutoAwesome, selected == null, glass) { onChip(null) }
        }
        items(chips, key = { "chip_${it.title}" }) { chip ->
            ExploreChip(UiLabels.of(chip.title), moodIcon(chip.title), selected == chip, glass) {
                onChip(if (selected == chip) null else chip)
            }
        }
    }
}

@Composable
private fun ExploreChip(label: String, icon: ImageVector, selected: Boolean, glass: LiquidGlassContext?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = MaterialTheme.dynamicPrimary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .94f else 1f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium), label = "chipPress")
    val content = when {
        glass != null -> glassContentColor(glass.isDark)
        selected -> if (accent.luminance() > .45f) Color.Black else Color.White
        else -> colors.onSurface
    }
    val surface = if (glass != null) {
        Modifier.liquidGlass(glass, CircleShape,
            tint = if (selected) accent.copy(alpha = if (glass.isDark) .42f else .32f) else glassBarTint(glass.isDark))
    } else {
        Modifier.clip(CircleShape)
            .background(if (selected) accent else colors.surfaceContainerHigh)
            .then(if (selected) Modifier else Modifier.border(1.dp, colors.outlineVariant.copy(alpha = .45f), CircleShape))
    }
    Row(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .then(surface)
            .clickable(interactionSource = interaction, indication = if (glass != null) null else LocalIndication.current,
                role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(icon, null, tint = content.copy(alpha = if (selected) 1f else .8f), modifier = Modifier.size(18.dp))
        Text(label, color = content, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
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

/**
 * Your most-replayed artist as a hero: their photo fills the card and fades under the text, with
 * the top song and a primary radio button plus a round arrow to their page. With liquid glass the
 * buttons are lenses over the photo (recorded inside the card, never the page it sits in).
 */
@Composable
private fun ArtistSpotlightCard(artist: ArtistAffinity, actions: SearchExploreActions) {
    val colors = MaterialTheme.colorScheme
    val appGlass = LocalLiquidGlass.current
    val accent = MaterialTheme.dynamicPrimary
    val base = if (appGlass != null) (if (appGlass.isDark) Color(0xFF101014) else Color(0xFFF4F4F7)) else colors.surfaceContainerHigh
    val ink = if (appGlass != null) glassContentColor(appGlass.isDark) else colors.onSurface
    val photo = artistPhoto(artist.artistName)
    val shape = RoundedCornerShape(30.dp)
    val backdrop = rememberLayerBackdrop()
    val glass = appGlass?.let { app -> remember(backdrop, app) { app.over(backdrop) } }
    Box(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp).height(236.dp).clip(shape)
            .border(1.dp, ink.copy(alpha = .08f), shape)
    ) {
        // The photo on the right, washed into the card colour on the left for the text.
        Box(Modifier.matchParentSize().then(if (glass != null) Modifier.layerBackdrop(backdrop) else Modifier).background(base)) {
            ArtworkCard(url = photo, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(1f),
                cornerRadius = 0.dp, contentDescription = null, highRes = true)
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                0f to base, .38f to base.copy(alpha = .94f), .72f to base.copy(alpha = .35f), 1f to base.copy(alpha = .05f))))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color.Transparent, .6f to Color.Transparent, 1f to base.copy(alpha = .7f))))
            Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(accent.copy(alpha = .22f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = 700f)))
        }
        Column(Modifier.matchParentSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(15.dp))
                Text(str(R.string.search_artist_spotlight), color = ink.copy(alpha = .72f), fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = .3.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(artist.artistName, color = ink, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.66f))
            artist.sampleTrack?.let { song ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(.7f)) {
                    ArtworkCard(url = song.thumbnail, modifier = Modifier.size(22.dp), cornerRadius = 5.dp,
                        contentDescription = null, fallbackTrack = song)
                    Spacer(Modifier.width(8.dp))
                    Text(str(R.string.search_top_track, song.title), color = ink.copy(alpha = .75f), fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                artist.sampleTrack?.let { song ->
                    SpotlightButton(glass, primary = true, onClick = { actions.onStartRadio(song) }) { tint ->
                        Icon(Icons.Rounded.Radio, null, tint = tint, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(str(R.string.search_artist_radio), color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
                SpotlightButton(glass, primary = false, round = true,
                    onClick = { actions.onOpenArtist(Artist(id = "", name = artist.artistName)) }) { tint ->
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, str(R.string.view_artist), tint = tint, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SpotlightButton(glass: LiquidGlassContext?, primary: Boolean, round: Boolean = false, onClick: () -> Unit,
    content: @Composable RowScope.(Color) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = MaterialTheme.dynamicPrimary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium), label = "spotlightPress")
    val tint = when {
        glass != null -> glassContentColor(glass.isDark)
        primary -> if (accent.luminance() > .45f) Color.Black else Color.White
        else -> colors.onSurface
    }
    val surface = if (glass != null) Modifier.liquidGlass(glass, CircleShape,
        tint = if (primary) accent.copy(alpha = if (glass.isDark) .5f else .38f) else glassBarTint(glass.isDark))
    else Modifier.clip(CircleShape).background(if (primary) accent else colors.onSurface.copy(alpha = .1f))
    Row(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.then(surface)
            .clickable(interactionSource = interaction, indication = if (glass != null) null else LocalIndication.current,
                role = Role.Button, onClick = onClick)
            .then(if (round) Modifier.size(48.dp) else Modifier.height(48.dp).padding(horizontal = 22.dp)),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) { content(tint) }
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
