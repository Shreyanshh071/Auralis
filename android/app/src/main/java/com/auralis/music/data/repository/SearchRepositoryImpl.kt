package com.auralis.music.data.repository

import com.auralis.music.data.local.dao.SearchHistoryDao
import com.auralis.music.data.local.entity.SearchHistoryEntity
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.remote.InvidiousApi
import com.auralis.music.data.remote.PipedApi
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Dedicated YouTube Music search repository powered by InnerTube WEB_REMIX API
 * and live autocomplete suggestions.
 */
class SearchRepositoryImpl(
    private val innerTubeClient: InnerTubeClient,
    private val suggestionsClient: SearchSuggestionsClient,
    private val searchHistoryDao: SearchHistoryDao
) : SearchRepository {

    override suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResults()

        coroutineScope {
            // Fetch official songs, albums, and artists in parallel with general search
            val songsDeferred = async {
                try {
                    innerTubeClient.search(trimmed, InnerTubeClient.FILTER_SONGS).songs
                } catch (e: Exception) {
                    emptyList<Track>()
                }
            }
            val albumsDeferred = async {
                try {
                    val res = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_ALBUMS)
                    if (res.albums.isNotEmpty()) res.albums else res.playlists
                } catch (e: Exception) {
                    emptyList<PlaylistResult>()
                }
            }
            val artistsDeferred = async {
                try {
                    innerTubeClient.search(trimmed, InnerTubeClient.FILTER_ARTISTS).artists
                } catch (e: Exception) {
                    emptyList<Artist>()
                }
            }
            val generalDeferred = async {
                try {
                    innerTubeClient.search(trimmed)
                } catch (e: Exception) {
                    SearchResults()
                }
            }

            val topSuggestionDeferred = async {
                if (trimmed.length >= 2) {
                    try {
                        val sugs = suggestionsClient.getSuggestions(trimmed)
                        val best = sugs.firstOrNull { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }
                        if (best != null) {
                            innerTubeClient.search(best, InnerTubeClient.FILTER_SONGS).songs
                        } else emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else emptyList()
            }

            val officialSongs: List<Track> = songsDeferred.await()
            val officialAlbums: List<PlaylistResult> = albumsDeferred.await()
            val officialArtists: List<Artist> = artistsDeferred.await()
            val generalResults: SearchResults = generalDeferred.await()
            val suggestionSongs: List<Track> = topSuggestionDeferred.await()

            val allSongs: List<Track> = (officialSongs + generalResults.songs + suggestionSongs).distinctBy { it.id }
            val allAlbums: List<PlaylistResult> = (officialAlbums + generalResults.albums + generalResults.playlists.filter { it.id.startsWith("MPRE") }).distinctBy { it.id }

            // 1. Partition matched songs and supplementary recommendations
            val (matchedSongs, recommendations) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(
                candidates = allSongs,
                query = trimmed,
                maxRecommendations = 3
            )

            // 2. Rank albums strictly by relevance and popularity
            val rankedAlbums = com.auralis.music.domain.search.SearchQueryMatcher.rankAlbums(
                candidates = allAlbums,
                query = trimmed
            )

            // 3. Automatically extract artists and resolve real artist photos from YouTube Music
            val songArtists: List<String> = matchedSongs
                .map { it.artist }
                .flatMap { it.split(",", "&", "feat.", "ft.", "/").map { a -> a.trim() } }
                .filter { it.isNotBlank() && it.length > 1 && !it.equals("Spotify Artist", ignoreCase = true) && !it.equals("Various Artists", ignoreCase = true) }
                .distinctBy { it.lowercase() }

            val allFoundArtists = (officialArtists + generalResults.artists).distinctBy { it.name.lowercase() }
            val existingArtistNames: Set<String> = allFoundArtists.map { it.name.lowercase() }.toSet()
            val missingArtists = mutableListOf<Artist>()

            for (artName in songArtists) {
                if (!existingArtistNames.contains(artName.lowercase())) {
                    val matchingOfficial = officialArtists.find { it.name.equals(artName, ignoreCase = true) }
                    val artistThumb = matchingOfficial?.thumbnail
                    val artistId = matchingOfficial?.id ?: "yt:$artName"
                    missingArtists.add(
                        Artist(
                            id = artistId,
                            name = matchingOfficial?.name ?: artName,
                            thumbnail = artistThumb,
                            query = "$artName top songs"
                        )
                    )
                }
            }

            val enrichedArtists: List<Artist> = (allFoundArtists + missingArtists).distinctBy { it.name.lowercase() }

            val officialThumbsByTitle = officialSongs
                .filter { !it.thumbnail.isNullOrBlank() && !it.thumbnail.contains("i.ytimg.com/vi/") }
                .associateBy { it.title.lowercase() }

            val officialThumbsById = officialSongs
                .filter { !it.thumbnail.isNullOrBlank() && !it.thumbnail.contains("i.ytimg.com/vi/") }
                .associateBy { it.id }

            fun upgradeTrackThumb(t: Track): Track {
                if (t.thumbnail.contains("i.ytimg.com/vi/") || t.thumbnail.isBlank()) {
                    val match = officialThumbsById[t.id] ?: officialThumbsByTitle[t.title.lowercase()]
                    if (match != null && !match.thumbnail.isNullOrBlank()) {
                        return t.copy(
                            thumbnail = match.thumbnail,
                            album = if (t.album.isNullOrBlank()) match.album else t.album,
                            views = if (t.views.isNullOrBlank()) match.views else t.views
                        )
                    }
                }
                return t
            }

            val finalMatchedSongs = matchedSongs.map { upgradeTrackThumb(it) }
            val finalRecommendations = recommendations.map { upgradeTrackThumb(it) }

            val exactArtistMatch = officialArtists.find { it.name.equals(trimmed, ignoreCase = true) }
                ?: enrichedArtists.find { it.name.equals(trimmed, ignoreCase = true) }

            val exactAlbumMatch = rankedAlbums.find { it.title.equals(trimmed, ignoreCase = true) }
                ?: officialAlbums.find { it.title.equals(trimmed, ignoreCase = true) }

            val topMatchedSong = finalMatchedSongs.firstOrNull()
            val topSongViews = SearchQueryMatcher.parsePlayCount(topMatchedSong?.views)

            val normQuery = SearchQueryMatcher.normalize(trimmed)
            val topSongNormTitle = topMatchedSong?.let { SearchQueryMatcher.normalize(it.title) } ?: ""
            val topSongCleanTitle = topMatchedSong?.let { SearchQueryMatcher.normalize(it.title.replace(Regex("\\(.*\\)|\\[.*\\]"), "")) } ?: ""
            val topSongIsExactTitle = topMatchedSong != null && (topSongNormTitle == normQuery || topSongCleanTitle == normQuery)

            // Resolve Top Result with highest fidelity to YouTube Music's global classification:
            // 1. If YouTube Music returned an official Album Top Result (e.g. "Graduation" by Kanye West, "OK Computer" by Radiohead, "Starboy" by The Weeknd)
            // 2. If YouTube Music returned an official Artist Top Result (e.g. "Kanye West", "The Weeknd")
            // 3. If YouTube Music returned a Song Top Result or matched songs exist
            val resolvedTopResult: SearchTopResult? = when {
                generalResults.topResult is SearchTopResult.AlbumResult -> {
                    generalResults.topResult
                }
                generalResults.topResult is SearchTopResult.ArtistResult -> {
                    generalResults.topResult
                }
                exactArtistMatch != null && (topMatchedSong == null || !topSongIsExactTitle) -> {
                    SearchTopResult.ArtistResult(exactArtistMatch)
                }
                exactAlbumMatch != null && (topMatchedSong == null || !topSongIsExactTitle) -> {
                    SearchTopResult.AlbumResult(exactAlbumMatch)
                }
                topMatchedSong != null -> {
                    SearchTopResult.SongResult(topMatchedSong)
                }
                exactAlbumMatch != null -> {
                    SearchTopResult.AlbumResult(exactAlbumMatch)
                }
                exactArtistMatch != null -> {
                    SearchTopResult.ArtistResult(exactArtistMatch)
                }
                generalResults.topResult != null -> {
                    when (val tr = generalResults.topResult) {
                        is SearchTopResult.SongResult -> {
                            val topTrack = tr.track
                            val studioMatch = officialSongs.find {
                                com.auralis.music.domain.recommendations.TrackDeduplicator.isDuplicateTrack(it, topTrack)
                            } ?: finalMatchedSongs.find {
                                com.auralis.music.domain.recommendations.TrackDeduplicator.isDuplicateTrack(it, topTrack)
                            }
                            val bestTrack = studioMatch ?: topTrack
                            SearchTopResult.SongResult(upgradeTrackThumb(bestTrack))
                        }
                        else -> tr
                    }
                }
                else -> null
            }

            // Resolve Primary Artist (e.g. Radiohead for "OK Computer", Kanye West for "Graduation", Elley Duhé for "MIDDLE OF THE NIGHT")
            var primaryArtist: Artist? = when {
                resolvedTopResult is SearchTopResult.ArtistResult -> resolvedTopResult.artist
                resolvedTopResult is SearchTopResult.AlbumResult -> {
                    val albumAuthor = resolvedTopResult.album.author ?: ""
                    enrichedArtists.find { it.name.equals(albumAuthor, ignoreCase = true) || albumAuthor.contains(it.name, ignoreCase = true) }
                        ?: officialArtists.find { it.name.equals(albumAuthor, ignoreCase = true) }
                        ?: if (albumAuthor.isNotBlank()) {
                            Artist(
                                id = "yt:$albumAuthor",
                                name = albumAuthor,
                                thumbnail = null,
                                query = "$albumAuthor top songs"
                            )
                        } else null
                }
                resolvedTopResult is SearchTopResult.SongResult -> {
                    val songArtist = resolvedTopResult.track.artist
                    if (songArtist.isNotBlank() && !songArtist.equals("Unknown Artist", ignoreCase = true) && !songArtist.equals("YouTube Artist", ignoreCase = true)) {
                        enrichedArtists.find { it.name.equals(songArtist, ignoreCase = true) || songArtist.contains(it.name, ignoreCase = true) }
                            ?: officialArtists.find { it.name.equals(songArtist, ignoreCase = true) }
                            ?: Artist(
                                id = "yt:$songArtist",
                                name = songArtist,
                                thumbnail = null,
                                query = "$songArtist top songs"
                            )
                    } else null
                }
                exactAlbumMatch != null -> {
                    val albumAuthor = exactAlbumMatch.author ?: ""
                    enrichedArtists.find { it.name.equals(albumAuthor, ignoreCase = true) || albumAuthor.contains(it.name, ignoreCase = true) }
                        ?: officialArtists.find { it.name.equals(albumAuthor, ignoreCase = true) }
                        ?: if (albumAuthor.isNotBlank()) {
                            Artist(
                                id = "yt:$albumAuthor",
                                name = albumAuthor,
                                thumbnail = null,
                                query = "$albumAuthor top songs"
                            )
                        } else null
                }
                else -> exactArtistMatch ?: enrichedArtists.firstOrNull()
            }

            // Ensure primaryArtist has their real verified YouTube photo
            if (primaryArtist != null && (primaryArtist.thumbnail.isNullOrBlank() || primaryArtist.thumbnail.contains("i.ytimg.com/vi/"))) {
                val realArtist = officialArtists.find { it.name.equals(primaryArtist.name, ignoreCase = true) }
                    ?: generalResults.artists.find { it.name.equals(primaryArtist.name, ignoreCase = true) }
                    ?: try {
                        val artistSearch = innerTubeClient.search(primaryArtist.name, InnerTubeClient.FILTER_ARTISTS).artists
                        artistSearch.firstOrNull {
                            it.name.equals(primaryArtist.name, ignoreCase = true) || it.name.contains(primaryArtist.name, ignoreCase = true)
                        } ?: artistSearch.firstOrNull()
                    } catch (_: Exception) { null }

                if (realArtist != null && !realArtist.thumbnail.isNullOrBlank()) {
                    primaryArtist = primaryArtist.copy(
                        id = if (realArtist.id.startsWith("UC")) realArtist.id else primaryArtist.id,
                        thumbnail = realArtist.thumbnail
                    )
                }
            }

            // Resolve Primary Album (e.g. "Graduation" for "Graduation", "OK Computer" for "OK Computer", "Starboy" for "Starboy")
            val primaryAlbum: PlaylistResult? = when {
                resolvedTopResult is SearchTopResult.AlbumResult -> resolvedTopResult.album
                resolvedTopResult is SearchTopResult.SongResult -> {
                    val track = resolvedTopResult.track
                    val albumTitle = track.album
                    val targetArtist = primaryArtist?.name ?: track.artist

                    if (!albumTitle.isNullOrBlank()) {
                        // Only match an album if it is by this verified artist
                        rankedAlbums.find { album ->
                            val auth = album.author ?: ""
                            val matchesArtist = auth.isNotBlank() && (
                                auth.contains(targetArtist, ignoreCase = true) ||
                                targetArtist.contains(auth, ignoreCase = true)
                            )
                            val matchesTitle = album.title.equals(albumTitle, ignoreCase = true)
                            matchesArtist && matchesTitle
                        }
                    } else {
                        // Song has no album tag; only accept an album if it is by the song's actual artist
                        rankedAlbums.find { album ->
                            val auth = album.author ?: ""
                            val matchesArtist = auth.isNotBlank() && (
                                auth.contains(targetArtist, ignoreCase = true) ||
                                targetArtist.contains(auth, ignoreCase = true)
                            )
                            val matchesSongTitle = album.title.contains(track.title, ignoreCase = true) || track.title.contains(album.title, ignoreCase = true)
                            matchesArtist && matchesSongTitle
                        }
                    }
                }
                exactAlbumMatch != null && primaryArtist != null && (
                    exactAlbumMatch.author?.contains(primaryArtist.name, ignoreCase = true) == true ||
                    primaryArtist.name.contains(exactAlbumMatch.author ?: "", ignoreCase = true)
                ) -> exactAlbumMatch
                primaryArtist != null -> {
                    rankedAlbums.find { album ->
                        val auth = album.author ?: ""
                        auth.isNotBlank() && (
                            auth.contains(primaryArtist.name, ignoreCase = true) ||
                            primaryArtist.name.contains(auth, ignoreCase = true)
                        )
                    }
                }
                else -> null
            }

            // Ensure prioritized ordering
            val finalArtists = if (primaryArtist != null) {
                listOf(primaryArtist) + enrichedArtists.filterNot { it.name.equals(primaryArtist.name, ignoreCase = true) }
            } else {
                enrichedArtists
            }

            val finalAlbums = if (primaryAlbum != null) {
                listOf(primaryAlbum) + rankedAlbums.filterNot { it.id == primaryAlbum.id || it.title.equals(primaryAlbum.title, ignoreCase = true) }
            } else {
                rankedAlbums
            }

            SearchResults(
                topResult = resolvedTopResult,
                recommendations = finalRecommendations,
                songs = finalMatchedSongs,
                albums = finalAlbums,
                artists = finalArtists,
                playlists = generalResults.playlists,
                primaryArtist = primaryArtist,
                primaryAlbum = primaryAlbum
            )
        }
    }

    override suspend fun searchSongs(query: String): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // Fetch filtered and general songs concurrently in parallel for fast response
        val filteredDef = async {
            try {
                innerTubeClient.search(trimmed, InnerTubeClient.FILTER_SONGS).songs
            } catch (_: Exception) { emptyList() }
        }
        val generalDef = async {
            try {
                innerTubeClient.search(trimmed).songs
            } catch (_: Exception) { emptyList() }
        }

        val filtered = filteredDef.await()
        val general = generalDef.await()
        val candidates = (filtered + general).distinctBy { it.id }

        val (matchedSongs, remaining) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(candidates, trimmed)
        if (matchedSongs.isNotEmpty()) matchedSongs else remaining.take(10)
    }

    override suspend fun searchAlbums(query: String): List<PlaylistResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val filteredRes = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_ALBUMS)
        val filtered = if (filteredRes.albums.isNotEmpty()) filteredRes.albums else filteredRes.playlists
        val general = innerTubeClient.search(trimmed).albums
        val candidates = (filtered + general).distinctBy { it.id }

        com.auralis.music.domain.search.SearchQueryMatcher.rankAlbums(candidates, trimmed)
    }

    override suspend fun searchArtists(query: String): List<Artist> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // 1. Try YouTube Music filtered artist search
        val filtered = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_ARTISTS).artists
        if (filtered.isNotEmpty()) return@withContext filtered

        // 2. Fall back to general search artists enriched with song artists
        val general = search(trimmed)
        general.artists
    }

    override suspend fun searchPlaylists(query: String): List<PlaylistResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // 1. Try YouTube Music filtered playlist search
        val filtered = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_PLAYLISTS).playlists
        if (filtered.isNotEmpty()) return@withContext filtered

        // 2. Fall back to general YouTube Music search playlists
        innerTubeClient.search(trimmed).playlists
    }

    override suspend fun getSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        suggestionsClient.getSuggestions(trimmed)
    }

    override suspend fun getArtistPage(artist: Artist): ArtistPage? = withContext(Dispatchers.IO) {
        innerTubeClient.getArtistPage(artist)
    }

    private val youtubePlaylistImporter = com.auralis.music.data.network.YouTubePlaylistImporter()

    private fun filterOfficialAlbumTracks(album: PlaylistResult, tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks

        val isExplicitLiveAlbum = album.title.contains("Live", ignoreCase = true)
        val isExplicitInstrumentalAlbum = album.title.contains("Instrumental", ignoreCase = true)
        val isExplicitAcousticAlbum = album.title.contains("Acoustic", ignoreCase = true)
        val isExplicitRemixAlbum = album.title.contains("Remix", ignoreCase = true)

        val cleanTracks = tracks.filter { track ->
            val title = track.title
            val isInstrumental = !isExplicitInstrumentalAlbum && (
                title.contains("(Instrumental", ignoreCase = true) ||
                title.contains("[Instrumental", ignoreCase = true) ||
                title.endsWith(" - Instrumental", ignoreCase = true) ||
                title.endsWith(" (Instrumental)", ignoreCase = true)
            )

            val isLiveCut = !isExplicitLiveAlbum && (
                title.contains("(Live From", ignoreCase = true) ||
                title.contains("(Live at", ignoreCase = true) ||
                title.contains("[Live at", ignoreCase = true) ||
                title.contains("(Live in", ignoreCase = true) ||
                title.contains("(Live /", ignoreCase = true) ||
                title.contains(" - Live", ignoreCase = true)
            )

            val isCommentaryOrDemo = title.contains("(Commentary", ignoreCase = true) ||
                title.contains("(Demo", ignoreCase = true) ||
                title.contains("[Demo", ignoreCase = true) ||
                title.contains("(Acapella", ignoreCase = true)

            !isInstrumental && !isLiveCut && !isCommentaryOrDemo
        }

        // Outro / Closing track detection for albums with expanded bonus tracks appended at end
        val outroIndex = cleanTracks.indexOfFirst {
            it.title.equals("Curtains Close", ignoreCase = true) ||
            (it.title.equals("Outro", ignoreCase = true) && cleanTracks.size > 20) ||
            (it.title.equals("Still Don't Give A Fuck", ignoreCase = true) && cleanTracks.size > 20) ||
            (it.title.equals("Criminal", ignoreCase = true) && cleanTracks.size > 18)
        }

        val isExpandedOrDeluxeAlbumTitle = album.title.contains("Expanded", ignoreCase = true) ||
            album.title.contains("Deluxe", ignoreCase = true) ||
            album.title.contains("Anniversary", ignoreCase = true) ||
            album.title.contains("Bonus", ignoreCase = true)

        val finalTracks = if (outroIndex in 10..25 && cleanTracks.size > outroIndex + 1 && !isExpandedOrDeluxeAlbumTitle) {
            cleanTracks.take(outroIndex + 1)
        } else {
            cleanTracks
        }

        return finalTracks
    }

    override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = withContext(Dispatchers.IO) {
        try {
            // 1. If album ID is already an official YouTube browse ID (MPRE, VL, PL, OLAK)
            if (album.id.startsWith("MPRE") || album.id.startsWith("VL") || album.id.startsWith("PL") || album.id.startsWith("OLAK")) {
                val imported = youtubePlaylistImporter.importPlaylistById(album.id)
                if (imported != null && imported.tracks.isNotEmpty()) {
                    val albumCover = imported.coverUrl ?: album.thumbnail
                    val tracksWithAlbumMeta = imported.tracks.map {
                        it.copy(
                            album = it.album?.ifBlank { album.title } ?: album.title,
                            artist = if (it.artist.isBlank() || it.artist == "Artist" || it.artist == "YouTube Music") {
                                album.author ?: it.artist
                            } else it.artist,
                            thumbnail = if (!albumCover.isNullOrBlank()) albumCover else it.thumbnail
                        )
                    }
                    return@withContext filterOfficialAlbumTracks(album, tracksWithAlbumMeta)
                }
            }

            // 2. Lookup the official album via YouTube Music InnerTube FILTER_ALBUMS
            val searchParam = "${album.author ?: ""} ${album.title}".trim()
            val albumsResult = innerTubeClient.search(searchParam, InnerTubeClient.FILTER_ALBUMS).albums
            val matchedAlbum = albumsResult.firstOrNull {
                it.title.equals(album.title, ignoreCase = true) ||
                (album.author != null && it.author?.contains(album.author, ignoreCase = true) == true) ||
                it.id.startsWith("MPRE")
            } ?: albumsResult.firstOrNull()

            if (matchedAlbum != null && (matchedAlbum.id.startsWith("MPRE") || matchedAlbum.id.startsWith("OLAK") || matchedAlbum.id.startsWith("VL"))) {
                val imported = youtubePlaylistImporter.importPlaylistById(matchedAlbum.id)
                if (imported != null && imported.tracks.isNotEmpty()) {
                    val albumCover = imported.coverUrl ?: matchedAlbum.thumbnail ?: album.thumbnail
                    val tracksWithAlbumMeta = imported.tracks.map {
                        it.copy(
                            album = it.album?.ifBlank { album.title } ?: album.title,
                            artist = if (it.artist.isBlank() || it.artist == "Artist" || it.artist == "YouTube Music") {
                                album.author ?: matchedAlbum.author ?: it.artist
                            } else it.artist,
                            thumbnail = if (!albumCover.isNullOrBlank()) albumCover else it.thumbnail
                        )
                    }
                    return@withContext filterOfficialAlbumTracks(album, tracksWithAlbumMeta)
                }
            }

            // 3. Fallback: songs where album metadata EXACTLY matches album.title
            val albumSongs = innerTubeClient.search(searchParam, InnerTubeClient.FILTER_SONGS).songs
            val strictlyMatchingSongs = albumSongs.filter {
                it.album?.equals(album.title, ignoreCase = true) == true
            }.map {
                val albumCover = album.thumbnail
                if (!albumCover.isNullOrBlank()) it.copy(thumbnail = albumCover) else it
            }
            if (strictlyMatchingSongs.isNotEmpty()) {
                return@withContext filterOfficialAlbumTracks(album, strictlyMatchingSongs)
            }

            return@withContext emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun getRecentSearchQueries(): Flow<List<String>> {
        return searchHistoryDao.getRecentQueriesFlow()
    }

    override suspend fun recordSearchQuery(query: String) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            searchHistoryDao.insertSearchQuery(
                SearchHistoryEntity(
                    query = trimmed,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun removeSearchQuery(query: String) = withContext(Dispatchers.IO) {
        searchHistoryDao.deleteSearchQuery(query)
    }

    override suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        searchHistoryDao.clearSearchHistory()
    }
}
