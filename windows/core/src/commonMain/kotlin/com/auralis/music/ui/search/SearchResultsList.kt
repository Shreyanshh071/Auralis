package com.auralis.music.ui.search

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.TextButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import com.auralis.music.ui.components.clickable
import com.auralis.music.ui.components.secondaryClick
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track

// The phone's search results UI (moved here from ExploreScreen unchanged), shared with the Windows
// app. The few things that differ per platform come from [LocalSearchUi].

/** Text the search results need from the app's translations. */
class SearchUiStrings(
    val albumBy: (String) -> String,
    val songBy: (String) -> String,
    val variousArtists: () -> String,
    val single: () -> String,
    val artist: () -> String,
    val options: () -> String
)

/** Platform pieces of the search results: image loading, row gestures, now-playing marker, text. */
class SearchUiPlatform(
    val artwork: @Composable (url: String?, modifier: Modifier, cornerRadius: Dp, contentDescription: String?) -> Unit,
    val nowPlayingIndicator: @Composable (isPlaying: Boolean, modifier: Modifier, color: Color) -> Unit,
    val strings: SearchUiStrings,
    /** Wraps each song row; the phone adds swipe-to-queue (play next / add to queue). */
    val trackRowContainer: @Composable (onPlayNext: () -> Unit, onAddToQueue: () -> Unit, content: @Composable () -> Unit) -> Unit =
        { _, _, content -> content() }
)

val LocalSearchUi = staticCompositionLocalOf<SearchUiPlatform> { error("Provide LocalSearchUi") }

@Composable
private fun ArtworkCard(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    contentDescription: String? = null,
    @Suppress("UNUSED_PARAMETER") sizeToConstraints: Boolean = false
) = LocalSearchUi.current.artwork(url, modifier, cornerRadius, contentDescription)

// ============================================================================
// 🎯 SEARCH RESULTS VIEW
// ============================================================================

@Composable
fun SearchResultsList(
    results: com.auralis.music.domain.model.SearchResults,
    contentPadding: PaddingValues,
    query: String = "",
    searchFailed: Boolean = false,
    onRetry: () -> Unit = {},
    currentTrackId: String?,
    isPlaying: Boolean,
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlayNext: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    onMenuClick: (Track) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlaylistClick: (PlaylistResult) -> Unit
) {
    val allArtists = (listOfNotNull(results.primaryArtist) + results.artists).distinctBy { it.id }
    // A single's release card is not an album: it is never listed (or opened) as one.
    val allAlbums = (listOfNotNull(results.primaryAlbum?.takeIf { !results.primarySingle }) + results.albums + results.playlists).distinctBy { it.id }
    val primaryArtist = results.primaryArtist ?: results.artists.firstOrNull()
    val primaryAlbum = results.primaryAlbum?.takeIf { album ->
        primaryArtist == null || com.auralis.music.domain.search.SearchQueryMatcher.isAuthorMatch(album.author, primaryArtist.name)
    }
    val isSingleRelease = primaryAlbum != null && results.primarySingle
    val isArtistSearch = results.topResult is SearchTopResult.ArtistResult ||
        (results.primaryArtist != null && query.isNotBlank() &&
            com.auralis.music.domain.search.SearchQueryMatcher.isAuthorMatch(results.primaryArtist!!.name, query))

    // Songs already shown as Top result / Also matching are not listed again below.
    val songsBelowFeatured = results.songsBelowFeatured()

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // A real failure stays visible; still-loading details don't get a row of their own.
        if (searchFailed && results.isNotEmpty()) {
            item(key = "search_completion_status") {
                TextButton(onClick = onRetry) { Text("Some results could not load. Retry") }
            }
        }
        // ====================================================================
        // STEP 1 — TOP RESULT
        // ====================================================================
        if (results.topResult != null) {
            item(key = "header_top_result") {
                Text(
                    text = "Top result",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            val topResultKey = when (val tr = results.topResult) {
                is SearchTopResult.SongResult -> "card_top_song_${tr.track.id}"
                is SearchTopResult.ArtistResult -> "card_top_artist_${tr.artist.id}"
                is SearchTopResult.AlbumResult -> "card_top_album_${tr.album.id}"
                else -> "card_top_none"
            }
            item(key = topResultKey) {
                SearchHeroCard(
                    result = results.topResult!!,
                    albumPlays = (results.topResult as? SearchTopResult.AlbumResult)?.let {
                        results.albumPlayCounts[it.album.id]
                    } ?: results.albumPlays,
                    currentTrackId = currentTrackId,
                    onTrackClick = onTrackClick,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = onPlaylistClick
                )
            }
        }


        // ====================================================================
        // STEP 1b — ALSO MATCHING: the same-named song or album that lost the top
        // spot on plays ("graduation": Kanye West's album under benny blanco's
        // song, or the other way round). A searched album never disappears.
        // ====================================================================
        results.runnerUp?.let { runnerUp ->
            item(key = "header_also_matching") {
                Text(
                    text = "Also matching",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
            item(key = "card_also_matching") {
                SearchHeroCard(
                    result = runnerUp,
                    albumPlays = (runnerUp as? SearchTopResult.AlbumResult)?.let {
                        results.albumPlayCounts[it.album.id]
                    } ?: results.albumPlays,
                    currentTrackId = currentTrackId,
                    onTrackClick = onTrackClick,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = onPlaylistClick
                )
            }
        }

        // ====================================================================
        // STEP 2 — ARTIST & ALBUM (EXACT SIDE-BY-SIDE CARDS)
        // Only shown when searching for songs/general queries. When searching
        // specifically for an artist, the artist is already in Top Result,
        // so we skip this section to directly show their top famous songs.
        // ====================================================================
        if (!isArtistSearch && (primaryArtist != null || primaryAlbum != null)) {
            item(key = "header_artist_album") {
                val headerTitle = when {
                    primaryArtist != null && primaryAlbum != null -> if (isSingleRelease) "Artist & Single" else "Artist & Album"
                    primaryAlbum != null -> if (isSingleRelease) "Single" else "Album"
                    else -> "Artist"
                }
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }

            item(key = "row_artist_album") {
                if (primaryArtist != null && primaryAlbum != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Artist Card (Left)
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onArtistClick(primaryArtist) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!primaryArtist.thumbnail.isNullOrBlank() && !primaryArtist.thumbnail!!.contains("i.ytimg.com/vi/")) {
                                    ArtworkCard(
                                        sizeToConstraints = true,
                                        url = primaryArtist.thumbnail,
                                        modifier = Modifier.size(46.dp).clip(CircleShape),
                                        cornerRadius = 23.dp,
                                        contentDescription = primaryArtist.name
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ARTIST",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = primaryArtist.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Top songs & info",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Album Card (Right)
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .then(if (isSingleRelease) Modifier else Modifier.clickable { onPlaylistClick(primaryAlbum) }),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkCard(
                                    sizeToConstraints = true,
                                    url = primaryAlbum.thumbnail,
                                    modifier = Modifier.size(46.dp),
                                    cornerRadius = 8.dp,
                                    contentDescription = primaryAlbum.title
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isSingleRelease) "SINGLE" else "ALBUM",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = primaryAlbum.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = primaryAlbum.author ?: "Album",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                } else if (primaryArtist != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onArtistClick(primaryArtist) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!primaryArtist.thumbnail.isNullOrBlank() && !primaryArtist.thumbnail!!.contains("i.ytimg.com/vi/")) {
                                ArtworkCard(
                                    sizeToConstraints = true,
                                    url = primaryArtist.thumbnail,
                                    modifier = Modifier.size(50.dp).clip(CircleShape),
                                    cornerRadius = 25.dp,
                                    contentDescription = primaryArtist.name
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ARTIST",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = primaryArtist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Top songs & albums",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else if (primaryAlbum != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .then(if (isSingleRelease) Modifier else Modifier.clickable { onPlaylistClick(primaryAlbum) }),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkCard(
                                sizeToConstraints = true,
                                url = primaryAlbum.thumbnail,
                                modifier = Modifier.size(50.dp),
                                cornerRadius = 8.dp,
                                contentDescription = primaryAlbum.title
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isSingleRelease) "SINGLE" else "ALBUM",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = primaryAlbum.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = primaryAlbum.author ?: "Album",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // STEP 3 — ACTUAL SEARCH RESULTS (REAL QUERY-MATCHED SONGS)
        // ====================================================================
        item(key = "header_matching_songs") {
            Text(
                text = if (isArtistSearch) "Top Songs" else "Songs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            )
        }

        if (songsBelowFeatured.isNotEmpty()) {
            items(
                items = songsBelowFeatured,
                key = { "match_${it.id}" },
                contentType = { "song" }
            ) { track ->
                val isCurrent = track.id == currentTrackId
                TrackRowItem(
                    track = track,
                    releaseTypes = results.releaseTypes,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    playlist = listOf(track),
                    onTrackClick = onTrackClick,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onMenuClick = onMenuClick
                )
            }
        } else {
            item(key = "empty_matching_songs") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No matching songs found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (query.isNotBlank()) "No songs match \"$query\". Check spelling or try searching another artist or title."
                        else "No songs found for this search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        // ====================================================================
        // STEP 4 — RECOMMENDATIONS (EXACTLY 3 MAXIMUM, SUPPLEMENTARY)
        // ====================================================================
        val recommendations = results.recommendations.take(3)
        if (recommendations.isNotEmpty()) {
            item(key = "header_recommendations") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recommendations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${recommendations.size} suggested",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(
                items = recommendations,
                key = { "rec_${it.id}" },
                contentType = { "song" }
            ) { track ->
                val isCurrent = track.id == currentTrackId
                TrackRowItem(
                    track = track,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    playlist = listOf(track),
                    onTrackClick = onTrackClick,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onMenuClick = onMenuClick
                )
            }
        }
    }
    // While the ranked results are still on their way (partial results on screen), a thin line at
    // the top says so without moving anything.
    if (!results.isComplete && !searchFailed) {
        androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp)
        )
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumRowItem(album: PlaylistResult, plays: Long, onClick: () -> Unit) {
    val strings = LocalSearchUi.current.strings
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkCard(
            sizeToConstraints = true,
            url = album.thumbnail,
            modifier = Modifier.size(50.dp),
            cornerRadius = 8.dp,
            contentDescription = album.title
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(strings.albumBy(album.author ?: strings.variousArtists()))
                    if (plays > 0L) append(" • ${com.auralis.music.domain.search.SearchQueryMatcher.formatPlayCount(plays)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRowItem(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    playlist: List<Track>,
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlayNext: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    releaseTypes: Map<String, String> = emptyMap(),
    onMenuClick: (Track) -> Unit
) {
    val strings = LocalSearchUi.current.strings
    LocalSearchUi.current.trackRowContainer(
        { onPlayNext?.invoke(track) },
        { onAddToQueue?.invoke(track) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                // Right-click opens the song's options, as its ⋮ does.
                .secondaryClick { onMenuClick(track) }
                .clickable { onTrackClick(track, playlist) }
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkCard(
                sizeToConstraints = true,
                url = track.thumbnail,
                modifier = Modifier.size(50.dp),
                cornerRadius = 8.dp,
                contentDescription = track.title
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val singleLabel = strings.single()
                val releaseType = track.albumId?.let { releaseTypes[it] }
                val subtitleText = remember(track.artist, track.album, track.views, track.title, singleLabel, releaseType) {
                    buildString {
                        if (track.artist.isNotBlank()) {
                            append(track.artist)
                        }
                        // "Single" where YouTube Music labels the release a single, the album's name where
                        // it labels it an album or EP. Unlabelled, an album that merely repeats the song
                        // title is left out: it could be either.
                        when {
                            releaseType == "single" -> append(" • $singleLabel")
                            track.album.isNullOrBlank() -> Unit
                            releaseType != null ||
                                !com.auralis.music.data.network.AlbumMetadataResolver.isRedundantOrSingle(track.album, track.title) ->
                                append(" • ${track.album}")
                        }
                        if (!track.views.isNullOrBlank()) {
                            append(" • ${track.views}")
                        }
                    }
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent) {
                LocalSearchUi.current.nowPlayingIndicator(isPlaying, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(onClick = { onMenuClick(track) }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = strings.options(),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** The large card used for the Top result and for the "Also matching" runner-up. */
@Composable
private fun SearchHeroCard(
    result: SearchTopResult,
    albumPlays: Long,
    currentTrackId: String?,
    onTrackClick: (Track, List<Track>) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlaylistClick: (PlaylistResult) -> Unit
) {
    val strings = LocalSearchUi.current.strings
    when (val tr = result) {
        is SearchTopResult.SongResult -> {
            val track = tr.track
            val isCurrent = track.id == currentTrackId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onTrackClick(track, listOf(track)) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(
                        sizeToConstraints = true,
                        url = track.thumbnail,
                        modifier = Modifier.size(72.dp),
                        cornerRadius = 12.dp,
                        contentDescription = track.title
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val subDetail = buildString {
                            append(strings.songBy(track.artist))
                            if (!track.views.isNullOrBlank()) {
                                append(" • ${track.views}")
                            }
                        }
                        Text(
                            text = subDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        is SearchTopResult.ArtistResult -> {
            val artist = tr.artist
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onArtistClick(artist) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(
                        sizeToConstraints = true,
                        url = artist.thumbnail ?: "",
                        modifier = Modifier.size(72.dp).clip(CircleShape),
                        cornerRadius = 36.dp,
                        contentDescription = artist.name
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.artist(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        is SearchTopResult.AlbumResult -> {
            val album = tr.album
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPlaylistClick(album) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(
                        sizeToConstraints = true,
                        url = album.thumbnail,
                        modifier = Modifier.size(72.dp),
                        cornerRadius = 12.dp,
                        contentDescription = album.title
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = buildString {
                            append(strings.albumBy(album.author ?: strings.variousArtists()))
                            if (albumPlays > 0L) append(" • ${com.auralis.music.domain.search.SearchQueryMatcher.formatPlayCount(albumPlays)}")
                        },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        else -> Unit
    }
}
