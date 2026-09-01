package com.auralis.music.domain.recommendations

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.DailyDiscoverItem
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.RecommendationSeedType
import com.auralis.music.domain.model.SimilarRecommendation
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Initial Taste & Seed Pool for Brand-New Users with Zero Listening History.
 *
 * Provides a rich, balanced initial Home experience curated around 9 iconic seed artists:
 * - Tame Impala (Psychedelic / Indie Rock)
 * - Kanye West (Hip-Hop / Production)
 * - Karan Aujla (Contemporary Punjabi / Pop)
 * - Radiohead (Alternative / Art Rock)
 * - KR$NA (Desi Hip-Hop / Lyricism)
 * - Arijit Singh (Indian Pop / Contemporary Melodies)
 * - KK (Classic Evergreen Melodies)
 * - Shreya Ghoshal (Indian Classical & Contemporary Vocals)
 * - Atif Aslam (Sufi / Vocal Rock)
 *
 * This acts as an initial-state mechanism only; once a user logs listening history,
 * the adaptive recommendation engine takes over naturally and personalization is uninhibited.
 */
object NewUserSeedProvider {

    val SEED_ARTISTS = listOf(
        "Tame Impala",
        "Kanye West",
        "Karan Aujla",
        "Radiohead",
        "KR\$NA",
        "Arijit Singh",
        "KK",
        "Shreya Ghoshal",
        "Atif Aslam"
    )

    /**
     * Curated, verified baseline tracks across all 9 seed artists (3 per artist = 27 tracks).
     * Provides instantaneous 0ms display on fresh launch before network search results resolve.
     */
    private val INITIAL_SEED_TRACKS: List<Track> = listOf(
        // Tame Impala
        Track(
            id = "sBzrzS1Ag_g",
            title = "The Less I Know the Better",
            artist = "Tame Impala",
            duration = 216,
            thumbnail = "https://i.ytimg.com/vi/sBzrzS1Ag_g/hqdefault.jpg"
        ),
        Track(
            id = "2g5xkLqIElU",
            title = "Borderline",
            artist = "Tame Impala",
            duration = 237,
            thumbnail = "https://i.ytimg.com/vi/2g5xkLqIElU/hqdefault.jpg"
        ),
        Track(
            id = "pFptt7Cargc",
            title = "Let It Happen",
            artist = "Tame Impala",
            duration = 467,
            thumbnail = "https://i.ytimg.com/vi/pFptt7Cargc/hqdefault.jpg"
        ),

        // Kanye West
        Track(
            id = "ila-hAUXR5U",
            title = "Flashing Lights",
            artist = "Kanye West",
            duration = 237,
            thumbnail = "https://i.ytimg.com/vi/ila-hAUXR5U/hqdefault.jpg"
        ),
        Track(
            id = "Co0tTeuUVhU",
            title = "Heartless",
            artist = "Kanye West",
            duration = 211,
            thumbnail = "https://i.ytimg.com/vi/Co0tTeuUVhU/hqdefault.jpg"
        ),
        Track(
            id = "PsO6Zn4V07g",
            title = "Stronger",
            artist = "Kanye West",
            duration = 311,
            thumbnail = "https://i.ytimg.com/vi/PsO6Zn4V07g/hqdefault.jpg"
        ),

        // Karan Aujla
        Track(
            id = "LK7-_dgAVQE",
            title = "Tauba Tauba",
            artist = "Karan Aujla",
            duration = 208,
            thumbnail = "https://i.ytimg.com/vi/LK7-_dgAVQE/hqdefault.jpg"
        ),
        Track(
            id = "cWMxFX7QCbw",
            title = "Softly",
            artist = "Karan Aujla",
            duration = 155,
            thumbnail = "https://i.ytimg.com/vi/cWMxFX7QCbw/hqdefault.jpg"
        ),
        Track(
            id = "vX2cDW8up2g",
            title = "Winning Speech",
            artist = "Karan Aujla",
            duration = 224,
            thumbnail = "https://i.ytimg.com/vi/vX2cDW8up2g/hqdefault.jpg"
        ),

        // Radiohead
        Track(
            id = "XFkzRNyygfk",
            title = "Creep",
            artist = "Radiohead",
            duration = 238,
            thumbnail = "https://i.ytimg.com/vi/XFkzRNyygfk/hqdefault.jpg"
        ),
        Track(
            id = "1uYWYWPc9HU",
            title = "Karma Police",
            artist = "Radiohead",
            duration = 264,
            thumbnail = "https://i.ytimg.com/vi/1uYWYWPc9HU/hqdefault.jpg"
        ),
        Track(
            id = "u5CVsCnxyXg",
            title = "No Surprises",
            artist = "Radiohead",
            duration = 228,
            thumbnail = "https://i.ytimg.com/vi/u5CVsCnxyXg/hqdefault.jpg"
        ),

        // KR$NA
        Track(
            id = "QjQ_rG_c43A",
            title = "No Cap",
            artist = "KR\$NA",
            duration = 212,
            thumbnail = "https://i.ytimg.com/vi/QjQ_rG_c43A/hqdefault.jpg"
        ),
        Track(
            id = "yS3vYw4oXG8",
            title = "Prarthana",
            artist = "KR\$NA",
            duration = 210,
            thumbnail = "https://i.ytimg.com/vi/yS3vYw4oXG8/hqdefault.jpg"
        ),
        Track(
            id = "z6bEwQjU_Qc",
            title = "I Guess",
            artist = "KR\$NA",
            duration = 185,
            thumbnail = "https://i.ytimg.com/vi/z6bEwQjU_Qc/hqdefault.jpg"
        ),

        // Arijit Singh
        Track(
            id = "BddP6PYo2gs",
            title = "Kesariya",
            artist = "Arijit Singh",
            duration = 268,
            thumbnail = "https://i.ytimg.com/vi/BddP6PYo2gs/hqdefault.jpg"
        ),
        Track(
            id = "IJq0yyWug1k",
            title = "Tum Hi Ho",
            artist = "Arijit Singh",
            duration = 262,
            thumbnail = "https://i.ytimg.com/vi/IJq0yyWug1k/hqdefault.jpg"
        ),
        Track(
            id = "ElZfdU54Cp8",
            title = "Apna Bana Le",
            artist = "Arijit Singh",
            duration = 261,
            thumbnail = "https://i.ytimg.com/vi/ElZfdU54Cp8/hqdefault.jpg"
        ),

        // KK
        Track(
            id = "5i_Wc3uE6G0",
            title = "Zara Sa",
            artist = "KK",
            duration = 303,
            thumbnail = "https://i.ytimg.com/vi/5i_Wc3uE6G0/hqdefault.jpg"
        ),
        Track(
            id = "2wVf4nUu8s8",
            title = "Kya Mujhe Pyar Hai",
            artist = "KK",
            duration = 277,
            thumbnail = "https://i.ytimg.com/vi/2wVf4nUu8s8/hqdefault.jpg"
        ),
        Track(
            id = "M4-Ecx6h0tU",
            title = "Labon Ko",
            artist = "KK",
            duration = 342,
            thumbnail = "https://i.ytimg.com/vi/M4-Ecx6h0tU/hqdefault.jpg"
        ),

        // Shreya Ghoshal
        Track(
            id = "z3UHfi9mpsg",
            title = "Sunn Raha Hai",
            artist = "Shreya Ghoshal",
            duration = 314,
            thumbnail = "https://i.ytimg.com/vi/z3UHfi9mpsg/hqdefault.jpg"
        ),
        Track(
            id = "d8ITb6mZbi4",
            title = "Manwa Laage",
            artist = "Shreya Ghoshal",
            duration = 271,
            thumbnail = "https://i.ytimg.com/vi/d8ITb6mZbi4/hqdefault.jpg"
        ),
        Track(
            id = "h6lHUn20J5g",
            title = "Deewani Mastani",
            artist = "Shreya Ghoshal",
            duration = 340,
            thumbnail = "https://i.ytimg.com/vi/h6lHUn20J5g/hqdefault.jpg"
        ),

        // Atif Aslam
        Track(
            id = "a18py61EcP4",
            title = "Tajdar-e-Haram",
            artist = "Atif Aslam",
            duration = 628,
            thumbnail = "https://i.ytimg.com/vi/a18py61EcP4/hqdefault.jpg"
        ),
        Track(
            id = "vpO8sZdxOGI",
            title = "Jeene Laga Hoon",
            artist = "Atif Aslam",
            duration = 237,
            thumbnail = "https://i.ytimg.com/vi/vpO8sZdxOGI/hqdefault.jpg"
        ),
        Track(
            id = "BadBAMnPXSc",
            title = "Pehli Nazar Mein",
            artist = "Atif Aslam",
            duration = 314,
            thumbnail = "https://i.ytimg.com/vi/BadBAMnPXSc/hqdefault.jpg"
        )
    )

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
                        } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        } catch (_: Exception) {}

        val interleaved = interleaveTracks(artistTracksMap, maxTotal = 28)
        return@withContext if (interleaved.isNotEmpty()) interleaved else getInitialSeedTracks()
    }

    /**
     * Generates "Similar to [Seed Artist]" shelves for a diverse subset of seed artists.
     */
    suspend fun fetchSeedSimilarRecommendations(searchRepository: SearchRepository): List<SimilarRecommendation> = withContext(Dispatchers.IO) {
        val similarList = Collections.synchronizedList(mutableListOf<SimilarRecommendation>())

        // Select a diverse rotation of seed artists across multiple genres
        val artistsToSeed = listOf("Tame Impala", "Arijit Singh", "Karan Aujla", "Kanye West", "KR\$NA", "Radiohead")

        try {
            coroutineScope {
                artistsToSeed.map { artistName ->
                    async {
                        try {
                            val searchResult = searchRepository.search(artistName)
                            val matchedArtist = searchResult.artists.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                                ?: searchResult.artists.firstOrNull()
                            val songs = searchResult.songs.take(10)
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
                        } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        } catch (_: Exception) {}

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

        return@withContext similarList.toList()
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
                        } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        } catch (_: Exception) {}

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
            val playlists = searchRepository.searchPlaylists("Tame Impala Karan Aujla Arijit Singh Mix")
            if (playlists.isNotEmpty()) {
                return@withContext playlists.take(8)
            }
            val fallback = searchRepository.searchPlaylists("Best Hits Music Mix 2026")
            return@withContext fallback.take(8)
        } catch (_: Exception) {
            return@withContext emptyList()
        }
    }
}
