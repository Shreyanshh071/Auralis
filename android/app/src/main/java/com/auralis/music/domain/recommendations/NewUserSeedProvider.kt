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
            id = "PvM79DJ2PmM",
            title = "The Less I Know The Better",
            artist = "Tame Impala",
            album = "Currents",
            duration = 217,
            thumbnail = "https://i.ytimg.com/vi/PvM79DJ2PmM/mqdefault.jpg"
        ),
        Track(
            id = "rymYToIEL9o",
            title = "Borderline",
            artist = "Tame Impala",
            album = "The Slow Rush",
            duration = 238,
            thumbnail = "https://i.ytimg.com/vi/rymYToIEL9o/mqdefault.jpg"
        ),
        Track(
            id = "NMRhx71bGo4",
            title = "Let It Happen",
            artist = "Tame Impala",
            album = "Currents",
            duration = 468,
            thumbnail = "https://i.ytimg.com/vi/NMRhx71bGo4/mqdefault.jpg"
        ),

        // Kanye West
        Track(
            id = "cxKs2b5lRsA",
            title = "Flashing Lights",
            artist = "Kanye West",
            album = "Graduation",
            duration = 238,
            thumbnail = "https://i.ytimg.com/vi/cxKs2b5lRsA/mqdefault.jpg"
        ),
        Track(
            id = "s40BTpfAELs",
            title = "Heartless",
            artist = "Kanye West",
            album = "808s & Heartbreak",
            duration = 211,
            thumbnail = "https://i.ytimg.com/vi/s40BTpfAELs/mqdefault.jpg"
        ),
        Track(
            id = "12hLNbXKCs4",
            title = "Stronger",
            artist = "Kanye West",
            album = "Graduation",
            duration = 313,
            thumbnail = "https://i.ytimg.com/vi/12hLNbXKCs4/mqdefault.jpg"
        ),

        // Karan Aujla
        Track(
            id = "N6_EvGT0ZfM",
            title = "Tauba Tauba",
            artist = "Karan Aujla",
            album = "Tauba Tauba",
            duration = 208,
            thumbnail = "https://i.ytimg.com/vi/N6_EvGT0ZfM/mqdefault.jpg"
        ),
        Track(
            id = "U4qD41gPQMU",
            title = "Softly",
            artist = "Karan Aujla",
            album = "Making Memories",
            duration = 156,
            thumbnail = "https://i.ytimg.com/vi/U4qD41gPQMU/mqdefault.jpg"
        ),
        Track(
            id = "0DS5jYQeiw0",
            title = "Winning Speech",
            artist = "Karan Aujla",
            album = "Winning Speech",
            duration = 228,
            thumbnail = "https://i.ytimg.com/vi/0DS5jYQeiw0/mqdefault.jpg"
        ),

        // Radiohead
        Track(
            id = "9RfVp-GhKfs",
            title = "Creep",
            artist = "Radiohead",
            album = "Pablo Honey",
            duration = 239,
            thumbnail = "https://i.ytimg.com/vi/9RfVp-GhKfs/mqdefault.jpg"
        ),
        Track(
            id = "nbCOAPR33ME",
            title = "Karma Police",
            artist = "Radiohead",
            album = "OK Computer",
            duration = 262,
            thumbnail = "https://i.ytimg.com/vi/nbCOAPR33ME/mqdefault.jpg"
        ),
        Track(
            id = "7374CZQoS2Y",
            title = "No Surprises",
            artist = "Radiohead",
            album = "OK Computer",
            duration = 229,
            thumbnail = "https://i.ytimg.com/vi/7374CZQoS2Y/mqdefault.jpg"
        ),

        // KR$NA
        Track(
            id = "6Zv9mSiZGBU",
            title = "No Cap",
            artist = "KR\$NA",
            album = "No Cap",
            duration = 206,
            thumbnail = "https://i.ytimg.com/vi/6Zv9mSiZGBU/mqdefault.jpg"
        ),
        Track(
            id = "brXz6f3EPFM",
            title = "Prarthana",
            artist = "KR\$NA",
            album = "FAR FROM OVER",
            duration = 200,
            thumbnail = "https://i.ytimg.com/vi/brXz6f3EPFM/mqdefault.jpg"
        ),
        Track(
            id = "mLaQwQHpP6A",
            title = "I Guess",
            artist = "KR\$NA",
            album = "I Guess",
            duration = 187,
            thumbnail = "https://i.ytimg.com/vi/mLaQwQHpP6A/mqdefault.jpg"
        ),

        // Arijit Singh
        Track(
            id = "NJAv_7lHUIU",
            title = "Kesariya",
            artist = "Arijit Singh",
            album = "Brahmastra",
            duration = 269,
            thumbnail = "https://i.ytimg.com/vi/NJAv_7lHUIU/mqdefault.jpg"
        ),
        Track(
            id = "fsiPzT50ZiM",
            title = "Tum Hi Ho",
            artist = "Arijit Singh",
            album = "Aashiqui 2",
            duration = 262,
            thumbnail = "https://i.ytimg.com/vi/fsiPzT50ZiM/mqdefault.jpg"
        ),
        Track(
            id = "YALvuUpY_b0",
            title = "Apna Bana Le",
            artist = "Arijit Singh",
            album = "Bhediya",
            duration = 262,
            thumbnail = "https://i.ytimg.com/vi/YALvuUpY_b0/mqdefault.jpg"
        ),

        // KK
        Track(
            id = "zv-tbc4F818",
            title = "Zara Sa",
            artist = "KK",
            album = "Jannat",
            duration = 304,
            thumbnail = "https://i.ytimg.com/vi/zv-tbc4F818/mqdefault.jpg"
        ),
        Track(
            id = "XPu9ZE4Onzc",
            title = "Kya Mujhe Pyar Hai",
            artist = "KK",
            album = "Woh Lamhe",
            duration = 267,
            thumbnail = "https://i.ytimg.com/vi/XPu9ZE4Onzc/mqdefault.jpg"
        ),
        Track(
            id = "12pMB_mCBOo",
            title = "Labon Ko",
            artist = "KK",
            album = "Bhool Bhulaiyaa",
            duration = 342,
            thumbnail = "https://i.ytimg.com/vi/12pMB_mCBOo/mqdefault.jpg"
        ),

        // Shreya Ghoshal
        Track(
            id = "1If9aw74Tj4",
            title = "Sunn Raha Hai",
            artist = "Shreya Ghoshal",
            album = "Aashiqui 2",
            duration = 315,
            thumbnail = "https://i.ytimg.com/vi/1If9aw74Tj4/mqdefault.jpg"
        ),
        Track(
            id = "MEjnFgMh3qE",
            title = "Manwa Laage",
            artist = "Shreya Ghoshal",
            album = "Happy New Year",
            duration = 273,
            thumbnail = "https://i.ytimg.com/vi/MEjnFgMh3qE/mqdefault.jpg"
        ),
        Track(
            id = "eSu6HHRn1UE",
            title = "Deewani Mastani",
            artist = "Shreya Ghoshal",
            album = "Bajirao Mastani",
            duration = 340,
            thumbnail = "https://i.ytimg.com/vi/eSu6HHRn1UE/mqdefault.jpg"
        ),

        // Atif Aslam
        Track(
            id = "qmBW9-fUvag",
            title = "Tajdar-e-Haram",
            artist = "Atif Aslam",
            album = "Coke Studio Season 8",
            duration = 617,
            thumbnail = "https://i.ytimg.com/vi/qmBW9-fUvag/mqdefault.jpg"
        ),
        Track(
            id = "3M3o3Ak1qBY",
            title = "Jeene Laga Hoon",
            artist = "Atif Aslam",
            album = "Ramaiya Vastavaiya",
            duration = 236,
            thumbnail = "https://i.ytimg.com/vi/3M3o3Ak1qBY/mqdefault.jpg"
        ),
        Track(
            id = "swcCuuQKGJ4",
            title = "Pehli Nazar Mein",
            artist = "Atif Aslam",
            album = "Race",
            duration = 313,
            thumbnail = "https://i.ytimg.com/vi/swcCuuQKGJ4/mqdefault.jpg"
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
