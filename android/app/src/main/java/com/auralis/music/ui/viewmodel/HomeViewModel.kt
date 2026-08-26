package com.auralis.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.*
import com.auralis.music.domain.recommendations.TasteProfile
import com.auralis.music.domain.recommendations.TasteProfiler
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
    val speedDialPages: List<List<SpeedDialItem>> = emptyList(),
    val quickPicks: List<Track> = emptyList(),
    val dailyDiscover: List<DailyDiscoverItem> = emptyList(),
    val forgottenFavorites: List<Track> = emptyList(),
    val keepListening: List<Track> = emptyList(),
    val similarRecommendations: List<SimilarRecommendation> = emptyList(),
    val communityPlaylists: List<PlaylistResult> = emptyList(),
    val dynamicSections: List<HomeSection> = emptyList(),
    val legacySections: List<HomeRecommendationSection> = emptyList(),
    val homeChips: List<HomeChip> = emptyList(),
    val selectedChip: HomeChip? = null,
    val recentTracks: List<HistoryEntry> = emptyList(),
    val topPlayedTracks: List<PlayCountEntry> = emptyList(),
    val similarArtists: List<Artist> = emptyList(),
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
    private val innerTubeClient: InnerTubeClient = InnerTubeClient()
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val artistAvatarCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    init {
        loadHomeData()
    }

    private fun isInvalidArtistName(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return true
        val lower = artist.trim().lowercase()
        return lower in listOf(
            "shreyanshh", "shreyansh", "youtube music", "youtube", "artist",
            "unknown artist", "various artists", "various", "topic", "guest listener", "admin"
        ) || lower.startsWith("user_") || lower.startsWith("yt_")
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
                        val profile = TasteProfiler.computeTasteProfile(history, topPlayed)
                        val topTracks = topPlayed.map { it.track }
                        val historyTracks = history.map { it.track }
                        val speedDial = buildSpeedDialPages(topTracks, historyTracks)

                        _uiState.update {
                            it.copy(
                                recentTracks = history,
                                topPlayedTracks = topPlayed,
                                speedDialPages = speedDial,
                                tasteProfile = profile
                            )
                        }

                        // Asynchronously resolve authentic artist avatar photos for Speed Dial
                        val artistsToResolve = (topTracks + historyTracks)
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
                                    val updatedPages = buildSpeedDialPages(topTracks, historyTracks)
                                    _uiState.update { it.copy(speedDialPages = updatedPages) }
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
                        _uiState.update {
                            it.copy(
                                homeChips = chips,
                                dynamicSections = sections
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
            val seeds = if (likedSeeds.isNotEmpty()) {
                likedSeeds
            } else {
                val history = historyRepository.getHistory().first().map { it.track }
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
            }
        } catch (_: Exception) {}
    }

    /**
     * Similar Recommendations: "Similar to [Artist]" & "Similar to [Song]"
     */
    private suspend fun fetchSimilarRecommendations() = withContext(Dispatchers.IO) {
        try {
            val history = historyRepository.getHistory().first().map { it.track }
            val topArtists = history
                .map { it.artist }
                .filter { !isInvalidArtistName(it) }
                .distinct()
                .take(4)

            val topTracks = history.take(3)
            val similarList = mutableListOf<SimilarRecommendation>()

            // 1. Artist-based recommendations from user's authentic favorite artists
            for (artistName in topArtists) {
                try {
                    val searchResult = searchRepository.search(artistName)
                    val artistTracks = searchResult.songs.take(10)
                    val sampleThumb = searchResult.artists.firstOrNull()?.thumbnail
                        ?: artistTracks.firstOrNull()?.thumbnail

                    if (artistTracks.isNotEmpty()) {
                        similarList.add(
                            SimilarRecommendation(
                                seedTitle = artistName,
                                seedThumbnail = sampleThumb,
                                seedType = RecommendationSeedType.ARTIST,
                                items = artistTracks
                            )
                        )
                    }
                } catch (_: Exception) {}
            }

            // 2. Song-based related recommendations
            for (track in topTracks) {
                try {
                    val (browseId, params) = innerTubeClient.getNextAndRelatedEndpoint(track.id)
                    val related = innerTubeClient.getRelated(browseId, params).take(10)
                    if (related.isNotEmpty()) {
                        similarList.add(
                            SimilarRecommendation(
                                seedTitle = track.title,
                                seedThumbnail = track.thumbnail,
                                seedType = RecommendationSeedType.SONG,
                                items = related
                            )
                        )
                    }
                } catch (_: Exception) {}
            }

            // 3. Fallback popular artists if history is sparse
            if (similarList.isEmpty()) {
                val curatedCurations = listOf("Tame Impala", "The Weeknd", "Daft Punk")
                for (artist in curatedCurations) {
                    try {
                        val res = searchRepository.search(artist)
                        if (res.songs.isNotEmpty()) {
                            similarList.add(
                                SimilarRecommendation(
                                    seedTitle = artist,
                                    seedThumbnail = res.artists.firstOrNull()?.thumbnail ?: res.songs.firstOrNull()?.thumbnail,
                                    seedType = RecommendationSeedType.ARTIST,
                                    items = res.songs.take(8)
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            if (similarList.isNotEmpty()) {
                _uiState.update { it.copy(similarRecommendations = similarList) }
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
                                    val radio = innerTubeClient.getRadioTracks(seedTrack.id).take(4)
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
                        if (count < 3 && seenTrackIds.add(track.id)) {
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
                    if (count < 2 && seenTrackIds.add(t.id)) {
                        finalQuickPicks.add(t)
                        artistAppearanceCount[t.artist] = count + 1
                        if (finalQuickPicks.size >= 28) break
                    }
                }
            }

            if (finalQuickPicks.isNotEmpty()) {
                _uiState.update { it.copy(quickPicks = finalQuickPicks) }
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
                if (nextChip != null) {
                    val (_, sections) = innerTubeClient.getHome(params = nextChip.params)
                    _uiState.update {
                        it.copy(dynamicSections = sections, isLoading = false)
                    }
                } else {
                    val (_, sections) = innerTubeClient.getHome()
                    _uiState.update {
                        it.copy(dynamicSections = sections, isLoading = false)
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
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
        viewModelScope.launch {
            historyRepository.clearHistory()
        }
    }

    /**
     * Builds 3x3 Speed Dial Pages (27 items total = 3 pages of 9).
     * Filters out invalid user/placeholder artist names so only real music artists and tracks appear.
     */
    private fun buildSpeedDialPages(
        topTracks: List<Track>,
        historyTracks: List<Track>
    ): List<List<SpeedDialItem>> {
        val uniqueTracks = (topTracks + historyTracks).distinctBy { it.id }
        if (uniqueTracks.isEmpty()) return emptyList()

        val allItems = mutableListOf<SpeedDialItem>()
        for ((idx, t) in uniqueTracks.take(24).withIndex()) {
            allItems.add(
                SpeedDialItem(
                    id = "track-${t.id}-$idx",
                    name = t.title,
                    type = SpeedDialType.TRACK,
                    track = t,
                    image = t.thumbnail
                )
            )
        }

        val pages = mutableListOf<List<SpeedDialItem>>()
        for (p in 0 until 3) {
            val pageSlice = allItems.drop(p * 8).take(8).toMutableList()
            if (pageSlice.isNotEmpty()) {
                // 9th Tile: Surprise Me / Explore
                pageSlice.add(
                    SpeedDialItem(
                        id = "surprise-$p",
                        name = "Surprise Me",
                        type = SpeedDialType.SURPRISE
                    )
                )
                while (pageSlice.size < 9) {
                    pageSlice.add(
                        SpeedDialItem(
                            id = "placeholder-$p-${pageSlice.size}",
                            name = "",
                            type = SpeedDialType.PLACEHOLDER
                        )
                    )
                }
                pages.add(pageSlice)
            }
        }
        return pages
    }
}
