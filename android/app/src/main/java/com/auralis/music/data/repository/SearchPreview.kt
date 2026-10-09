package com.auralis.music.data.repository

import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.domain.model.*
import com.auralis.music.domain.recommendations.SimilarSeedPlanner
import com.auralis.music.domain.search.SearchQueryMatcher

/** Rank only metadata already received. Extra lookups must never delay the first visible result. */
internal fun searchPreview(query: String, sources: List<SearchResults>): SearchResults {
    val candidates = sources.flatMap { it.songs + listOfNotNull((it.topResult as? SearchTopResult.SongResult)?.track) }
        .distinctBy { it.id }
    val (songs, recommendations) = SearchQueryMatcher.partitionResults(candidates, query)
    val albums = SearchQueryMatcher.rankAlbums(sources.flatMap { it.albums + it.playlists.filter { p -> p.id.startsWith("MPRE") } }
        .distinctBy { it.id }, query)
    val artists = sources.flatMap { it.artists }.distinctBy { it.name.lowercase() }
    val exactArtist = artists.firstOrNull { SearchQueryMatcher.normalize(it.name) == SearchQueryMatcher.normalize(query) }
    val exactAlbum = albums.firstOrNull { SearchQueryMatcher.normalize(it.title) == SearchQueryMatcher.normalize(query) }
    val topSong = songs.firstOrNull()
    val exactSong = topSong?.let { SearchQueryMatcher.evaluateMatch(it, query)?.tier == SearchQueryMatcher.MatchTier.EXACT_TITLE } == true
    val top = when {
        exactSong -> SearchTopResult.SongResult(topSong!!)
        exactArtist != null -> SearchTopResult.ArtistResult(exactArtist)
        exactAlbum != null -> SearchTopResult.AlbumResult(exactAlbum)
        topSong != null -> SearchTopResult.SongResult(topSong)
        else -> sources.firstNotNullOfOrNull { it.topResult }
    }
    // An artist-only response for a song query cannot flash an unrelated profile before songs arrive.
    if (top == null && candidates.isEmpty() && exactArtist == null && exactAlbum == null) return SearchResults()
    val credit = when (top) {
        is SearchTopResult.SongResult -> SimilarSeedPlanner.splitArtistCredit(top.track.artist).firstOrNull()
        is SearchTopResult.AlbumResult -> top.album.author
        is SearchTopResult.ArtistResult -> top.artist.name
        else -> null
    }
    val artist = (top as? SearchTopResult.ArtistResult)?.artist ?: credit?.takeIf { it.isNotBlank() }?.let { name ->
        artists.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Artist("yt:$name", name)
    }
    val track = (top as? SearchTopResult.SongResult)?.track
    val album = (top as? SearchTopResult.AlbumResult)?.album ?: track?.takeIf {
        !it.albumId.isNullOrBlank() && !AlbumMetadataResolver.needsResolving(it.album, it.title)
    }?.let { PlaylistResult(it.albumId!!, it.album!!, thumbnail = it.thumbnail.ifBlank { null }, author = it.artist) }
    return SearchResults(topResult = top, songs = songs, recommendations = recommendations, albums = albums,
        artists = artists, primaryArtist = artist, primaryAlbum = album,
        playlists = sources.flatMap { it.playlists }.distinctBy { it.id }, isComplete = false)
}
