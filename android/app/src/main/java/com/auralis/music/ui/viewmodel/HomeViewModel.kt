package com.auralis.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.domain.model.*
import com.auralis.music.domain.recommendations.NewUserSeedProvider
import com.auralis.music.domain.recommendations.TasteProfile
import com.auralis.music.domain.recommendations.TasteProfiler
import com.auralis.music.domain.recommendations.TrackDeduplicator
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

import android.content.Context
import com.auralis.music.data.datastore.HomeRecommendationsCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect

data class SpeedDialItem(
    val id: String,
    val name: String,
    val type: SpeedDialType,
    val image: String? = null,
    val track: Track? = null,
    val artistQuery: String? = null,
    val isPinned: Boolean = false
)

enum class SpeedDialType { TRACK, ARTIST, SURPRISE, MORE, PLACEHOLDER }

data class HomeRecommendationSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val iconName: String = "flame",
    val tracks: List<Track> = emptyList()
)

data class HomeUiState(
    val recentTracks: List<HistoryEntry> = emptyList(),
    val topPlayedTracks: List<PlayCountEntry> = emptyList(),
    val speedDialPages: List<List<SpeedDialItem>> = emptyList(),
    val forgottenFavorites: List<Track> = emptyList(),
    val keepListening: List<Track> = emptyList(),
    val quickPicks: List<Track> = emptyList(),
    val communityPlaylists: List<PlaylistResult> = emptyList(),
    val communitySections: List<CommunityPlaylistItem> = emptyList(),
    val homeChips: List<HomeChip> = emptyList(),
    val selectedChip: HomeChip? = null,
    val dynamicSections: List<HomeSection> = emptyList(),
    val legacySections: List<HomeRecommendationSection> = emptyList(),
    val dailyDiscover: List<DailyDiscoverItem> = emptyList(),
    val similarRecommendations: List<SimilarRecommendation> = emptyList(),
    val similarArtists: List<Artist> = emptyList(),
    val similarArtistTracks: List<Track> = emptyList(),
    val similarArtistName: String? = null,
    val similarArtistThumbnail: String? = null,
    val selectedMoodFilter: String? = null,
    val tasteProfile: TasteProfile = TasteProfile(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

class HomeViewModel(
    private val historyRepository: HistoryRepository,
    private val searchRepository: SearchRepository,
    private val innerTubeClient: InnerTubeClient = InnerTubeClient(),
    private val context: Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val artistAvatarCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    init {
        // 1. Immediately restore cached recommendation shelves to UI (0ms cold start latency)
        viewModelScope.launch(Dispatchers.IO) {
            context?.let { ctx ->
                val cachedRecs = HomeRecommendationsCache.getCachedSimilarRecommendations(ctx)
                val cachedDiscover = HomeRecommendationsCache.getCachedDailyDiscover(ctx)
                if (cachedRecs.isNotEmpty() || cachedDiscover.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            similarRecommendations = if (it.similarRecommendations.isEmpty()) cachedRecs else it.similarRecommendations,
                            dailyDiscover = if (it.dailyDiscover.isEmpty()) cachedDiscover else it.dailyDiscover
                        )
                    }
                }
            }
        }

        // 2. Load fresh home data
        loadHomeData()

        // 3. Continuously collect listening history & top played in real-time
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.getHistory().collect { historyList ->
                val topPlayed = historyRepository.getTopPlayedTracks().first()
                val isNewUser = !hasListeningHistory(historyList, topPlayed)

                val profile = if (isNewUser) {
                    TasteProfile(recommendedSeeds = NewUserSeedProvider.SEED_ARTISTS, primaryVibe = "Welcome to Auralis")
                } else {
                    TasteProfiler.computeTasteProfile(historyList, topPlayed)
                }

                val topTracks = topPlayed.map { it.track }
                val historyTracks = historyList.map { it.track }

                if (isNewUser) {
                    val seedTracks = NewUserSeedProvider.getInitialSeedTracks()
                    val speedDial = buildSpeedDialPages(emptyList(), emptyList(), seedTracks)
                    _uiState.update {
                        it.copy(
                            recentTracks = emptyList(),
                            topPlayedTracks = emptyList(),
                            tasteProfile = profile,
                            speedDialPages = speedDial
                        )
                    }
                } else {
                    val likedSeeds = historyRepository.getLikedSeeds(limit = 20)
                    val heavy = historyRepository.getRecentHeavyRotation()
                    val speedDial = buildSpeedDialPages(topTracks, historyTracks, likedSeeds + heavy)
                    _uiState.update {
                        it.copy(
                            recentTracks = historyList,
                            topPlayedTracks = topPlayed,
                            tasteProfile = profile,
                            speedDialPages = speedDial
                        )
                    }
                    fetchSimilarRecommendations()
                    fetchQuickPicks()
                    fetchDailyDiscover()
                }
            }
        }
    }

    private fun hasListeningHistory(history: List<HistoryEntry>, topPlayed: List<PlayCountEntry>): Boolean {
        return history.isNotEmpty() || topPlayed.isNotEmpty()
    }

    private fun isInvalidArtistName(artist: String?): Boolean {
        return TrackDeduplicator.isInvalidArtistName(artist)
    }

    /**
     * Complete Metrolist Recommendation Engine:
     * Phase 1: Local database queries (Speed Dial, Forgotten Favorites, Heavy Rotation)
     *          plus primary YouTube Music Home feed.
     * Phase 2: Asynchronous background coroutines for heavy discovery algorithms
     *          (Daily Discover, Similar recommendations, Community Playlists, Quick Picks).
     */
    fun loadHomeData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Phase 1: Fast Parallel Fetch
            coroutineScope {
                // 1. Local history & top played
                launch(Dispatchers.IO) {
                    try {
                        val history = historyRepository.getHistory().first()
                        val topPlayed = historyRepository.getTopPlayedTracks().first()
                        val isNewUser = !hasListeningHistory(history, topPlayed)

                        val profile = if (isNewUser) {
                            TasteProfile(recommendedSeeds = NewUserSeedProvider.SEED_ARTISTS, primaryVibe = "Welcome to Auralis")
                        } else {
                            TasteProfiler.computeTasteProfile(history, topPlayed)
                        }

                        val topTracks = topPlayed.map { it.track }
                        val historyTracks = history.map { it.track }
                        val likedSeeds = historyRepository.getLikedSeeds(limit = 20)
                        val heavy = historyRepository.getRecentHeavyRotation()

                        val speedDial = if (isNewUser) {
                            val seedTracks = NewUserSeedProvider.getInitialSeedTracks()
                            buildSpeedDialPages(emptyList(), emptyList(), seedTracks)
                        } else {
                            buildSpeedDialPages(topTracks, historyTracks, likedSeeds + heavy)
                        }

                        _uiState.update {
                            it.copy(
                                recentTracks = history,
                                topPlayedTracks = topPlayed,
                                speedDialPages = speedDial,
                                tasteProfile = profile,
                                isLoading = false
                            )
                        }

                        // Pre-warm audio streams for top 2 visible Speed Dial tracks in background
                        val firstPageTrackIds = speedDial.firstOrNull()
                            ?.filter { it.type == SpeedDialType.TRACK }
                            ?.map { it.id }
                            ?.take(2) ?: emptyList()
                        val candidatePool = if (isNewUser) NewUserSeedProvider.getInitialSeedTracks() else (topTracks + historyTracks + likedSeeds + heavy)
                        val tracksToPrewarm = firstPageTrackIds.mapNotNull { id -> candidatePool.firstOrNull { it.id == id } }
                        if (tracksToPrewarm.isNotEmpty()) {
                            launch(Dispatchers.IO) {
                                for (trk in tracksToPrewarm) {
                                    try {
                                        val isCached = AudioStreamResolver.getCachedStream(trk.id) != null ||
                                            AudioStreamResolver.getCachedStreamByFingerprint(AudioStreamResolver.getSongFingerprintKey(trk.title, trk.artist)) != null
                                        if (!isCached) {
                                            AudioStreamResolver.resolveAudioStream(trk.id, trk.title, trk.artist)
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }

                        if (!isNewUser) {
                            // Asynchronously resolve authentic artist avatar photos for Speed Dial
                            val artistsToResolve = (topTracks + historyTracks + likedSeeds + heavy)
                                .map { it.artist }
                                .filter { !isInvalidArtistName(it) && !artistAvatarCache.containsKey(it) }
                                .distinct()

                            if (artistsToResolve.isNotEmpty()) {
                                launch(Dispatchers.IO) {
                                    var hasUpdates = false
                                    for (art in artistsToResolve.take(12)) {
                                        try {
                                            val searchHits = searchRepository.search(art)
                                            val match = searchHits.artists.firstOrNull { it.name.equals(art, ignoreCase = true) }
                                                ?: searchHits.artists.firstOrNull()
                                            if (match != null && !match.thumbnail.isNullOrBlank()) {
                                                artistAvatarCache[art] = Pair(match.id, match.thumbnail)
                                                hasUpdates = true
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    if (hasUpdates) {
                                        val updatedPages = buildSpeedDialPages(topTracks, historyTracks, likedSeeds + heavy)
                                        _uiState.update { it.copy(speedDialPages = updatedPages) }
                                    }
                                }
                            }
                        }

                        // Asynchronously resolve missing thumbnails in recent tracks and history
                        launch(Dispatchers.IO) {
                            val blankTracks = (historyTracks + topTracks).filter { it.thumbnail.isBlank() }.distinctBy { it.id }
                            if (blankTracks.isNotEmpty()) {
                                for (trk in blankTracks) {
                                    val thumb = com.auralis.music.data.network.ArtworkResolver.resolveArtwork(trk)
                                    if (!thumb.isNullOrBlank()) {
                                        historyRepository.addToHistory(trk.copy(thumbnail = thumb))
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 2. Forgotten Favorites (local DB)
                launch(Dispatchers.IO) {
                    try {
                        val forgotten = historyRepository.getForgottenFavorites().shuffled().take(15)
                        if (forgotten.isNotEmpty()) {
                            _uiState.update { it.copy(forgottenFavorites = forgotten) }
                        }
                    } catch (_: Exception) {}
                }

                // 3. Keep Listening / Heavy Rotation (last 2 weeks)
                launch(Dispatchers.IO) {
                    try {
                        val heavy = historyRepository.getRecentHeavyRotation().distinctBy { it.id }.take(15)
                        if (heavy.isNotEmpty()) {
                            _uiState.update { it.copy(keepListening = heavy) }
                        }
                    } catch (_: Exception) {}
                }

                // 4. Quick Picks (recent + trending + related)
                launch(Dispatchers.IO) {
                    try {
                        fetchQuickPicks()
                    } catch (_: Exception) {}
                }

                // 5. YouTube Music Home Feed (FEmusic_home)
                launch(Dispatchers.IO) {
                    try {
                        val (chips, sections) = innerTubeClient.getHome()
                        val cleanChips = chips.filter { !isUnwantedNoiseChip(it) }
                        val cleanSections = sections.filter { !isUnwantedNoiseSection(it) }
                        _uiState.update {
                            it.copy(
                                homeChips = cleanChips,
                                dynamicSections = cleanSections
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            // Mark Phase 1 complete immediately so UI is responsive
            _uiState.update { it.copy(isLoading = false) }

            // Phase 2: Heavy multi-request operations dispatched asynchronously
            viewModelScope.launch(Dispatchers.IO) {
                fetchDailyDiscover()
            }

            viewModelScope.launch(Dispatchers.IO) {
                fetchSimilarRecommendations()
            }

            viewModelScope.launch(Dispatchers.IO) {
                fetchCommunityPlaylists()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            loadHomeData()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * Daily Discover: Seeds liked/played songs -> queries YouTube related -> generates novel discoveries.
     */
    private suspend fun fetchDailyDiscover() = withContext(Dispatchers.IO) {
        try {
            val likedSeeds = historyRepository.getLikedSeeds(limit = 8).shuffled().take(5)
            val history = historyRepository.getHistory().first().map { it.track }
            val topPlayed = historyRepository.getTopPlayedTracks().first().map { it.track }
            val isNewUser = likedSeeds.isEmpty() && history.isEmpty() && topPlayed.isEmpty()

            if (isNewUser) {
                val seedDiscoveries = NewUserSeedProvider.fetchSeedDailyDiscover(searchRepository, innerTubeClient)
                if (seedDiscoveries.isNotEmpty()) {
                    _uiState.update { it.copy(dailyDiscover = seedDiscoveries) }
                    context?.let { HomeRecommendationsCache.saveDailyDiscover(it, seedDiscoveries) }
                }
                return@withContext
            }

            val seeds = if (likedSeeds.isNotEmpty()) {
                likedSeeds
            } else {
                history.shuffled().take(5)
            }

            val discoveries = Collections.synchronizedList(mutableListOf<DailyDiscoverItem>())

            if (seeds.isNotEmpty()) {
                coroutineScope {
                    seeds.forEach { seed ->
                        launch(Dispatchers.IO) {
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
                    }
                }
            }

            if (discoveries.isEmpty()) {
                // High-quality trending fallback discovery
                val trending = searchRepository.search("Global Viral & Discovery Mix").songs.take(6)
                trending.forEach { t ->
                    discoveries.add(
                        DailyDiscoverItem(
                            seed = t,
                            recommendation = t
                        )
                    )
                }
            }

            val finalItems = discoveries.distinctBy { it.recommendation.id }.shuffled()
            if (finalItems.isNotEmpty()) {
                _uiState.update { it.copy(dailyDiscover = finalItems) }
                context?.let { HomeRecommendationsCache.saveDailyDiscover(it, finalItems) }
            }
        } catch (_: Exception) {}
    }

    /**
     * Similar Recommendations: "Similar to [Artist]" & "Similar to [Song]"
     * Fetches concurrently and saves to local disk cache so shelves never vanish on restart.
     */
    private suspend fun fetchSimilarRecommendations() = withContext(Dispatchers.IO) {
        try {
            val history = historyRepository.getHistory().first().map { it.track }
            val topPlayed = historyRepository.getTopPlayedTracks().first().map { it.track }
            val isNewUser = history.isEmpty() && topPlayed.isEmpty()

            if (isNewUser) {
                val seedRecs = NewUserSeedProvider.fetchSeedSimilarRecommendations(searchRepository)
                if (seedRecs.isNotEmpty()) {
                    _uiState.update { it.copy(similarRecommendations = seedRecs) }
                    context?.let { HomeRecommendationsCache.saveSimilarRecommendations(it, seedRecs) }
                }
                return@withContext
            }

            val topArtists = history
                .map { it.artist }
                .filter { !isInvalidArtistName(it) }
                .distinct()
                .take(4)

            val topTracks = history.take(3)
            val similarList = Collections.synchronizedList(mutableListOf<SimilarRecommendation>())

            coroutineScope {
                // 1. Artist-based recommendations from user's authentic favorite artists (parallel)
                topArtists.forEach { artistName ->
                    launch(Dispatchers.IO) {
                        try {
                            val searchResult = searchRepository.search(artistName)
                            val matchedArtist = searchResult.artists.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                                ?: searchResult.artists.firstOrNull()
                            val artistTracks = searchResult.songs.take(10)
                            val sampleThumb = matchedArtist?.thumbnail
                                ?: artistTracks.firstOrNull()?.thumbnail

                            if (artistTracks.isNotEmpty()) {
                                similarList.add(
                                    SimilarRecommendation(
                                        seedTitle = artistName,
                                        seedThumbnail = sampleThumb,
                                        seedType = RecommendationSeedType.ARTIST,
                                        items = artistTracks,
                                        artistId = matchedArtist?.id,
                                        artistName = artistName
                                    )
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }

                // 2. Song-based related recommendations (parallel)
                topTracks.forEach { track ->
                    launch(Dispatchers.IO) {
                        try {
                            val (browseId, params) = innerTubeClient.getNextAndRelatedEndpoint(track.id)
                            val related = innerTubeClient.getRelated(browseId, params).take(10)
                            if (related.isNotEmpty()) {
                                similarList.add(
                                    SimilarRecommendation(
                                        seedTitle = track.title,
                                        seedThumbnail = track.thumbnail,
                                        seedType = RecommendationSeedType.SONG,
                                        items = related,
                                        artistName = track.artist
                                    )
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            // 3. Fallback popular recommendations if history is sparse (dynamic trending discovery)
            if (similarList.isEmpty()) {
                coroutineScope {
                    launch(Dispatchers.IO) {
                        try {
                            val exploreSections = innerTubeClient.getExplore()
                            val topTracks = exploreSections.flatMap { it.items }.distinctBy { it.id }.take(15)
                            val discoveredArtists = topTracks.map { it.artist }.filter { !isInvalidArtistName(it) }.distinct().take(3)

                            for (artist in discoveredArtists) {
                                val res = searchRepository.search(artist)
                                val matchedArt = res.artists.firstOrNull { it.name.equals(artist, ignoreCase = true) }
                                    ?: res.artists.firstOrNull()
                                if (res.songs.isNotEmpty()) {
                                    similarList.add(
                                        SimilarRecommendation(
                                            seedTitle = artist,
                                            seedThumbnail = matchedArt?.thumbnail ?: res.songs.firstOrNull()?.thumbnail,
                                            seedType = RecommendationSeedType.ARTIST,
                                            items = res.songs.take(8),
                                            artistId = matchedArt?.id,
                                            artistName = artist
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            val finalRecs = similarList.toList()
            if (finalRecs.isNotEmpty()) {
                _uiState.update { it.copy(similarRecommendations = finalRecs) }
                context?.let { HomeRecommendationsCache.saveSimilarRecommendations(it, finalRecs) }
            }
        } catch (_: Exception) {}
    }

    /**
     * Quick Picks:
     * - Discovers and aggregates songs across ALL artists the user frequently listens to
     *   (sampling from listening history, heavy rotation, top played, and liked songs).
     * - Samples top tracks and radio recommendations from up to 8 distinct artists the user loves.
     * - Interleaves (round-robin) the tracks so Quick Picks is never saturated with just 1 artist,
     *   giving a rich, diverse personalized feed.
     * - If first-time user: falls back to YouTube Music home quick picks & trending hits.
     */
    private suspend fun fetchQuickPicks() = withContext(Dispatchers.IO) {
        try {
            val history = historyRepository.getHistory().first().map { it.track }
            val heavyRotation = historyRepository.getRecentHeavyRotation()
            val topPlayed = historyRepository.getTopPlayedTracks().first().map { it.track }
            val likedSeeds = historyRepository.getLikedSeeds(limit = 20)

            val allUserTracks = (history + heavyRotation + topPlayed + likedSeeds).distinctBy { it.id }

            if (allUserTracks.isEmpty()) {
                val seedPicks = NewUserSeedProvider.fetchSeedQuickPicks(searchRepository)
                if (seedPicks.isNotEmpty()) {
                    _uiState.update { it.copy(quickPicks = seedPicks) }
                }
                return@withContext
            }

            // Group user tracks by artist to discover ALL distinct artists the user listens to
            val artistTracksMap = mutableMapOf<String, MutableList<Track>>()
            for (track in allUserTracks) {
                val artist = track.artist.trim()
                if (artist.isNotBlank() && !isInvalidArtistName(artist)) {
                    artistTracksMap.getOrPut(artist) { mutableListOf() }.add(track)
                }
            }

            val artistPools = Collections.synchronizedList(mutableListOf<MutableList<Track>>())

            if (artistTracksMap.isNotEmpty()) {
                // Sort artists by how many tracks user has listened to (descending user affinity)
                val sortedArtists = artistTracksMap.entries
                    .sortedByDescending { it.value.size }
                    .map { it.key }
                    .take(8) // Top 8 distinct artists

                coroutineScope {
                    sortedArtists.forEach { artistName ->
                        launch(Dispatchers.IO) {
                            val pool = mutableListOf<Track>()
                            val userKnownTracks = artistTracksMap[artistName] ?: emptyList()

                            // 1. Add 1-2 tracks the user loves by this artist
                            pool.addAll(userKnownTracks.shuffled().take(2))

                            // 2. Fetch radio tracks or top recommendations for this artist
                            try {
                                val seedTrack = userKnownTracks.firstOrNull()
                                if (seedTrack != null) {
                                    val radio = innerTubeClient.getRadioTracks(seedTrack.id, seedTrack.artist, seedTrack.title).take(4)
                                    pool.addAll(radio)
                                } else {
                                    val searchHits = searchRepository.search("$artistName songs").songs.take(4)
                                    pool.addAll(searchHits)
                                }
                            } catch (_: Exception) {}

                            if (pool.isNotEmpty()) {
                                artistPools.add(pool.distinctBy { it.id }.toMutableList())
                            }
                        }
                    }
                }
            }

            // Fallback for new users or if not enough artist pools
            val fallbackList = mutableListOf<Track>()
            if (artistPools.size < 3) {
                try {
                    val (_, sections) = innerTubeClient.getHome()
                    for (section in sections) {
                        if (section.title.contains("Quick pick", ignoreCase = true) ||
                            section.title.contains("Mixed for you", ignoreCase = true) ||
                            section.title.contains("Listen again", ignoreCase = true) ||
                            section.title.contains("Trending", ignoreCase = true) ||
                            section.title.contains("Hits", ignoreCase = true)) {
                            fallbackList.addAll(section.items)
                        }
                    }
                    if (fallbackList.isEmpty()) {
                        val trending = searchRepository.search("Top trending music hits").songs
                        fallbackList.addAll(trending)
                    }
                } catch (_: Exception) {}
            }

            // Round-Robin Interleave: Pick 1 track per artist per round to ensure diverse artist representation
            val finalQuickPicks = mutableListOf<Track>()
            val seenTrackIds = mutableSetOf<String>()
            val artistAppearanceCount = mutableMapOf<String, Int>()

            var round = 0
            val maxRounds = 4
            while (finalQuickPicks.size < 28 && round < maxRounds && artistPools.isNotEmpty()) {
                var anyAdded = false
                for (pool in artistPools) {
                    if (pool.isNotEmpty()) {
                        val track = pool.removeAt(0)
                        val count = artistAppearanceCount.getOrDefault(track.artist, 0)
                        if (count < 3 && seenTrackIds.add(track.id) && finalQuickPicks.none { TrackDeduplicator.isDuplicateTrack(it, track) }) {
                            finalQuickPicks.add(track)
                            artistAppearanceCount[track.artist] = count + 1
                            anyAdded = true
                        }
                    }
                }
                if (!anyAdded) break
                round++
            }

            // If we still need more tracks, fill from fallback list
            if (finalQuickPicks.size < 24 && fallbackList.isNotEmpty()) {
                for (t in fallbackList) {
                    val count = artistAppearanceCount.getOrDefault(t.artist, 0)
                    if (count < 2 && seenTrackIds.add(t.id) && finalQuickPicks.none { TrackDeduplicator.isDuplicateTrack(it, t) }) {
                        finalQuickPicks.add(t)
                        artistAppearanceCount[t.artist] = count + 1
                        if (finalQuickPicks.size >= 28) break
                    }
                }
            }

            if (finalQuickPicks.isNotEmpty()) {
                _uiState.update { it.copy(quickPicks = finalQuickPicks) }

                launch(Dispatchers.IO) {
                    var updated = false
                    val resolvedPicks = finalQuickPicks.map { trk ->
                        if (trk.thumbnail.isBlank()) {
                            val thumb = com.auralis.music.data.network.ArtworkResolver.resolveArtwork(trk)
                            if (!thumb.isNullOrBlank()) {
                                updated = true
                                trk.copy(thumbnail = thumb)
                            } else trk
                        } else trk
                    }
                    if (updated) {
                        _uiState.update { it.copy(quickPicks = resolvedPicks) }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Community Playlists & Curated Mixes
     */
    private suspend fun fetchCommunityPlaylists() = withContext(Dispatchers.IO) {
        try {
            val history = historyRepository.getHistory().first().map { it.track }
            val topArtist = history.map { it.artist }.firstOrNull { !isInvalidArtistName(it) }

            if (history.isEmpty() || topArtist.isNullOrBlank()) {
                val seedPlaylists = NewUserSeedProvider.fetchSeedCommunityPlaylists(searchRepository)
                if (seedPlaylists.isNotEmpty()) {
                    _uiState.update { it.copy(communityPlaylists = seedPlaylists) }
                    return@withContext
                }
            }

            val query = if (!topArtist.isNullOrBlank()) "$topArtist Mix" else "Top Hits Playlist 2026"
            val playlistsResult = searchRepository.searchPlaylists(query)
            
            val community = if (playlistsResult.isNotEmpty()) {
                playlistsResult.take(8)
            } else {
                searchRepository.searchPlaylists("Popular Music Mix").take(8)
            }
            if (community.isNotEmpty()) {
                _uiState.update { it.copy(communityPlaylists = community) }
            }
        } catch (_: Exception) {}
    }

    /**
     * Toggles/Selects a YouTube Music Chip.
     */
    fun toggleChip(chip: HomeChip?) {
        val nextChip = if (_uiState.value.selectedChip == chip) null else chip
        _uiState.update { it.copy(selectedChip = nextChip, isLoading = true) }

        viewModelScope.launch {
            try {
                val (_, sections) = if (nextChip != null) {
                    innerTubeClient.getHome(params = nextChip.params)
                } else {
                    innerTubeClient.getHome()
                }
                val cleanSections = sections.filter { !isUnwantedNoiseSection(it) }
                _uiState.update {
                    it.copy(dynamicSections = cleanSections, isLoading = false)
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun isUnwantedNoiseSection(section: HomeSection): Boolean {
        val lowerTitle = section.title.lowercase()
        val lowerSubtitle = (section.subtitle ?: "").lowercase()
        return lowerTitle.contains("rain therapy") || lowerTitle.contains("rain sound") ||
            lowerTitle.contains("sleep therapy") || lowerTitle.contains("white noise") ||
            lowerTitle.contains("nature sound") || lowerTitle.contains("binaural") ||
            lowerTitle.contains("sleep sound") || lowerTitle.contains("deep sleep") ||
            lowerSubtitle.contains("rain therapy") || lowerSubtitle.contains("sleep therapy") ||
            lowerSubtitle.contains("white noise")
    }

    private fun isUnwantedNoiseChip(chip: HomeChip): Boolean {
        val lower = chip.title.lowercase()
        return lower.contains("sleep") || lower.contains("therapy") || lower.contains("rain") ||
            lower.contains("white noise") || lower.contains("ambient sound")
    }

    fun selectMoodFilter(mood: String?) {
        _uiState.update { it.copy(selectedMoodFilter = mood) }
        viewModelScope.launch {
            if (mood == null) {
                loadHomeData()
            } else {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    val results = searchRepository.search("$mood music hits")
                    val moodSection = HomeRecommendationSection(
                        id = "mood-$mood",
                        title = "$mood Hits",
                        subtitle = "Curated for your $mood mood",
                        iconName = "sparkles",
                        tracks = results.songs
                    )
                    _uiState.update {
                        it.copy(
                            legacySections = listOf(moodSection),
                            isLoading = false
                        )
                    }
                } catch (_: Exception) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun getRandomSurpriseTrack(): Track? {
        val all = _uiState.value.quickPicks +
                _uiState.value.forgottenFavorites +
                _uiState.value.keepListening +
                _uiState.value.similarRecommendations.flatMap { it.items }
        return all.shuffled().firstOrNull()
    }

    fun removeFromHistory(trackId: String) {
        viewModelScope.launch {
            historyRepository.removeFromHistory(trackId)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.clearHistory()
            val seedTracks = NewUserSeedProvider.getInitialSeedTracks()
            val seedSpeedDial = buildSpeedDialPages(emptyList(), emptyList(), seedTracks)
            _uiState.update {
                it.copy(
                    recentTracks = emptyList(),
                    topPlayedTracks = emptyList(),
                    speedDialPages = seedSpeedDial,
                    keepListening = emptyList(),
                    forgottenFavorites = emptyList(),
                    tasteProfile = TasteProfile(
                        recommendedSeeds = NewUserSeedProvider.SEED_ARTISTS,
                        primaryVibe = "Welcome to Auralis"
                    )
                )
            }
            fetchQuickPicks()
            fetchSimilarRecommendations()
            fetchDailyDiscover()
            fetchCommunityPlaylists()
        }
    }

    /**
     * Builds 3x3 Speed Dial Pages (27 items total = 3 pages of 9).
     * Strictly deduplicates songs so identical tracks (even with different YouTube IDs,
     * bracket noise, or artist variations) are never recommended multiple times on Speed Dial.
     * Only page 0 displays the 5-dot "Surprise Me" tile in the 9th slot; pages 1 and 2 display 9 full tracks.
     */
    fun buildSpeedDialPages(
        topTracks: List<Track>,
        historyTracks: List<Track>,
        fallbackCandidates: List<Track> = emptyList()
    ): List<List<SpeedDialItem>> {
        val candidateTracks = topTracks + historyTracks + fallbackCandidates
        val uniqueTracks = TrackDeduplicator.deduplicateTracks(candidateTracks)
        if (uniqueTracks.isEmpty()) return emptyList()

        val allItems = mutableListOf<SpeedDialItem>()
        for ((idx, t) in uniqueTracks.take(26).withIndex()) {
            val displayName = TitleCleaner.cleanTitle(t.title).ifBlank { t.title.trim() }
            allItems.add(
                SpeedDialItem(
                    id = "track-${t.id}-$idx",
                    name = displayName,
                    type = SpeedDialType.TRACK,
                    track = t.copy(title = displayName),
                    image = t.thumbnail
                )
            )
        }

        val pages = mutableListOf<List<SpeedDialItem>>()

        // Page 0: 8 tracks + 1 Surprise Me dice tile in the 9th slot
        val page0Items = allItems.take(8).toMutableList()
        if (page0Items.isNotEmpty()) {
            page0Items.add(
                SpeedDialItem(
                    id = "surprise-0",
                    name = "Surprise Me",
                    type = SpeedDialType.SURPRISE
                )
            )
            while (page0Items.size < 9) {
                page0Items.add(
                    SpeedDialItem(
                        id = "placeholder-0-${page0Items.size}",
                        name = "",
                        type = SpeedDialType.PLACEHOLDER
                    )
                )
            }
            pages.add(page0Items)
        }

        // Page 1: 9 tracks (items 8..16) without surprise button
        val page1Items = allItems.drop(8).take(9).toMutableList()
        if (page1Items.isNotEmpty()) {
            while (page1Items.size < 9) {
                page1Items.add(
                    SpeedDialItem(
                        id = "placeholder-1-${page1Items.size}",
                        name = "",
                        type = SpeedDialType.PLACEHOLDER
                    )
                )
            }
            pages.add(page1Items)
        }

        // Page 2: 9 tracks (items 17..25) without surprise button
        val page2Items = allItems.drop(17).take(9).toMutableList()
        if (page2Items.isNotEmpty()) {
            while (page2Items.size < 9) {
                page2Items.add(
                    SpeedDialItem(
                        id = "placeholder-2-${page2Items.size}",
                        name = "",
                        type = SpeedDialType.PLACEHOLDER
                    )
                )
            }
            pages.add(page2Items)
        }

        return pages
    }
}
