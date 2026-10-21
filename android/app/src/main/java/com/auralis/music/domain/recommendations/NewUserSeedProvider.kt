package com.auralis.music.domain.recommendations

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.DailyDiscoverItem
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.RecommendationSeedType
import com.auralis.music.domain.model.SimilarRecommendation
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Collections

/** First Home recommendations use only artists chosen by this listener. */
object NewUserSeedProvider {

    /** Artists picked during onboarding and their top songs; empty means no taste has been chosen yet. */
    private data class Starter(val artists: List<String>, val tracks: List<Track>)
    @Volatile private var starter = Starter(emptyList(), emptyList())

    fun setStarter(artists: List<String>, tracks: List<Track>) {
        starter = Starter(artists.toList(), tracks.toList())
    }

    val SEED_ARTISTS: List<String> get() = starter.artists
    val hasStarter: Boolean get() = starter.artists.isNotEmpty()
    private val INITIAL_SEED_TRACKS: List<Track> get() = starter.tracks

    /** Immediately available shelves from songs fetched during artist selection or restore. */
    fun starterRecommendations(): List<SimilarRecommendation> {
        val saved = starter
        return saved.artists.mapNotNull { name ->
            val songs = saved.tracks.filter { it.artist.equals(name, ignoreCase = true) ||
                it.artist.contains(name, ignoreCase = true) }.distinctBy { it.id }
            songs.takeIf { it.isNotEmpty() }?.let {
                SimilarRecommendation(seedTitle = name, seedThumbnail = it.first().thumbnail,
                    seedType = RecommendationSeedType.ARTIST, items = it, artistName = name)
            }
        }
    }

    fun getInitialSeedTracks(): List<Track> {
        val artistGroups = INITIAL_SEED_TRACKS.groupBy { it.artist }
        return interleaveTracks(artistGroups, maxTotal = 27)
    }

    /**
     * Interleaves tracks round-robin across artists to prevent artist clustering
     * and strictly eliminates duplicate songs.
     */
    fun interleaveTracks(artistGroups: Map<String, List<Track>>, maxTotal: Int = 28): List<Track> {
        val result = mutableListOf<Track>()
        val seenIds = mutableSetOf<String>()
        val artistPools = artistGroups.values.map { it.toMutableList() }.toMutableList()

        var round = 0
        val maxRounds = 10
        while (result.size < maxTotal && round < maxRounds && artistPools.any { it.isNotEmpty() }) {
            var addedAny = false
            for (pool in artistPools) {
                if (pool.isNotEmpty() && result.size < maxTotal) {
                    val track = pool.removeAt(0)
                    if (seenIds.add(track.id) && result.none { TrackDeduplicator.isDuplicateTrack(it, track) }) {
                        result.add(track)
                        addedAny = true
                    }
                }
            }
            if (!addedAny) break
            round++
        }
        return result
    }

    /**
     * Fetches a rich, diverse Quick Picks playlist for new users.
     * Queries top tracks for all 9 seed artists in parallel and round-robin interleaves them.
     */
    suspend fun fetchSeedQuickPicks(searchRepository: SearchRepository): List<Track> = withContext(Dispatchers.IO) {
        val artistTracksMap = mutableMapOf<String, MutableList<Track>>()

        // Seed with baseline curated tracks first
        for (track in INITIAL_SEED_TRACKS) {
            artistTracksMap.getOrPut(track.artist) { mutableListOf() }.add(track)
        }

        try {
            coroutineScope {
                SEED_ARTISTS.map { artist ->
                    async {
                        try {
                            val results = searchRepository.search("$artist songs")
                            val songs = results.songs.filter { it.artist.contains(artist, ignoreCase = true) }.take(4)
                            if (songs.isNotEmpty()) {
                                synchronized(artistTracksMap) {
                                    artistTracksMap.getOrPut(artist) { mutableListOf() }.addAll(songs)
                                }
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {}

        val interleaved = interleaveTracks(artistTracksMap, maxTotal = 28)
        return@withContext if (interleaved.isNotEmpty()) interleaved else getInitialSeedTracks()
    }

    /**
     * Generates "Similar to [Seed Artist]" shelves for a diverse subset of seed artists.
     */
    suspend fun fetchSeedSimilarRecommendations(searchRepository: SearchRepository): List<SimilarRecommendation> = withContext(Dispatchers.IO) {
        val similarList = Collections.synchronizedList(mutableListOf<SimilarRecommendation>())

        // Select a diverse rotation of seed artists across multiple genres
        val artistsToSeed = SEED_ARTISTS.take(8)

        try {
            coroutineScope {
                artistsToSeed.map { artistName ->
                    async {
                        try {
                            val searchResult = searchRepository.search(artistName)
                            val matchedArtist = searchResult.artists.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                                ?: searchResult.artists.firstOrNull()
                            val page = matchedArtist?.let { searchRepository.getArtistPage(it) }
                            val songs = (page?.topSongs.orEmpty() + searchResult.songs.filter {
                                it.artist.contains(artistName, ignoreCase = true)
                            }).take(10)
                            val baselineSongs = INITIAL_SEED_TRACKS.filter { it.artist.equals(artistName, ignoreCase = true) }
                            val effectiveSongs = (songs + baselineSongs).distinctBy { it.id }.take(10)

                            val thumb = matchedArtist?.thumbnail ?: effectiveSongs.firstOrNull()?.thumbnail

                            if (effectiveSongs.isNotEmpty()) {
                                similarList.add(
                                    SimilarRecommendation(
                                        seedTitle = artistName,
                                        seedThumbnail = thumb,
                                        seedType = RecommendationSeedType.ARTIST,
                                        items = effectiveSongs,
                                        artistId = matchedArtist?.id,
                                        artistName = artistName
                                    )
                                )
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {}

        if (similarList.isEmpty()) {
            // Fallback from baseline tracks
            for (artistName in artistsToSeed.take(3)) {
                val songs = INITIAL_SEED_TRACKS.filter { it.artist.equals(artistName, ignoreCase = true) }
                if (songs.isNotEmpty()) {
                    similarList.add(
                        SimilarRecommendation(
                            seedTitle = artistName,
                            seedThumbnail = songs.firstOrNull()?.thumbnail,
                            seedType = RecommendationSeedType.ARTIST,
                            items = songs,
                            artistName = artistName
                        )
                    )
                }
            }
        }

        return@withContext similarList.toList().sortedBy { artistsToSeed.indexOf(it.seedTitle) }
    }

    /**
     * Generates Daily Discover recommendations seeded from iconic tracks of the seed pool.
     */
    suspend fun fetchSeedDailyDiscover(
        searchRepository: SearchRepository,
        innerTubeClient: InnerTubeClient
    ): List<DailyDiscoverItem> = withContext(Dispatchers.IO) {
        val discoveries = Collections.synchronizedList(mutableListOf<DailyDiscoverItem>())
        val seedTracks = INITIAL_SEED_TRACKS.shuffled().take(5)

        try {
            coroutineScope {
                seedTracks.map { seed ->
                    async {
                        try {
                            val (browseId, params) = innerTubeClient.getNextAndRelatedEndpoint(seed.id)
                            if (browseId != null || params != null) {
                                val related = innerTubeClient.getRelated(browseId, params)
                                val candidate = related.firstOrNull { it.id != seed.id }
                                if (candidate != null) {
                                    discoveries.add(
                                        DailyDiscoverItem(
                                            seed = seed,
                                            recommendation = candidate,
                                            browseId = browseId,
                                            params = params
                                        )
                                    )
                                }
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {}

        if (discoveries.isEmpty()) {
            val fallback = INITIAL_SEED_TRACKS.shuffled().take(6)
            fallback.forEach { t ->
                discoveries.add(DailyDiscoverItem(seed = t, recommendation = t))
            }
        }

        return@withContext discoveries.distinctBy { it.recommendation.id }.shuffled()
    }

    /**
     * Fetches curated community playlists / artist mixes for seed artists.
     */
    suspend fun fetchSeedCommunityPlaylists(searchRepository: SearchRepository): List<PlaylistResult> = withContext(Dispatchers.IO) {
        try {
            val playlists = searchRepository.searchPlaylists(SEED_ARTISTS.take(3).joinToString(" ").let { if (it.isBlank()) "popular music mixes" else "$it Mix" })
            if (playlists.isNotEmpty()) {
                return@withContext playlists.take(8)
            }
            val fallback = searchRepository.searchPlaylists("popular music mixes")
            return@withContext fallback.take(8)
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            return@withContext emptyList()
        }
    }
}
