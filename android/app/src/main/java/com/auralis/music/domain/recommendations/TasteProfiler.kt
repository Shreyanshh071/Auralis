package com.auralis.music.domain.recommendations

import com.auralis.music.domain.model.HistoryEntry
import com.auralis.music.domain.model.PlayCountEntry
import com.auralis.music.domain.model.Track

/**
 * Affinity scoring for an artist based on play count and recency.
 */
data class ArtistAffinity(
    val artistName: String,
    val totalPlays: Int,
    val recentPlaysCount: Int,
    val affinityScore: Float,
    val sampleTrack: Track? = null
)

/**
 * Computed taste profile for local personalization without telemetry or cloud trackers.
 */
data class TasteProfile(
    val topArtists: List<ArtistAffinity> = emptyList(),
    val totalPlaysLogged: Int = 0,
    val recommendedSeeds: List<String> = emptyList(),
    val primaryVibe: String = "Trending"
)

/**
 * Client-side Taste Profiler analyzing play counts, recency decay, and saved habits
 * to generate dynamic, tailored recommendations.
 */
object TasteProfiler {

    private const val RECENCY_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L // 7 Days

    /**
     * Analyzes local listening history and play count frequency to build an affinity profile.
     */
    fun computeTasteProfile(
        history: List<HistoryEntry>,
        playCounts: List<PlayCountEntry>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): TasteProfile {
        val totalPlays = playCounts.sumOf { it.count }
        if (history.isEmpty() && playCounts.isEmpty()) {
            return TasteProfile(
                recommendedSeeds = NewUserSeedProvider.SEED_ARTISTS,
                primaryVibe = "Welcome to Auralis"
            )
        }

        // Aggregate stats by artist
        val artistPlayMap = mutableMapOf<String, Int>()
        val artistRecentMap = mutableMapOf<String, Int>()
        val artistSampleTrackMap = mutableMapOf<String, Track>()

        // Process total play counts
        for (entry in playCounts) {
            val artist = entry.track.artist.trim()
            if (artist.isNotBlank()) {
                artistPlayMap[artist] = (artistPlayMap[artist] ?: 0) + entry.count
                if (!artistSampleTrackMap.containsKey(artist)) {
                    artistSampleTrackMap[artist] = entry.track
                }
            }
        }

        // Process recency weighting from history
        for (entry in history) {
            val artist = entry.track.artist.trim()
            if (artist.isNotBlank()) {
                val age = nowEpochMs - entry.playedAt
                if (age < RECENCY_WINDOW_MS) {
                    artistRecentMap[artist] = (artistRecentMap[artist] ?: 0) + 1
                }
                if (!artistSampleTrackMap.containsKey(artist)) {
                    artistSampleTrackMap[artist] = entry.track
                }
            }
        }

        // Compute composite affinity score: (TotalPlays * 1.5) + (RecentPlays * 3.0)
        val allArtists = (artistPlayMap.keys + artistRecentMap.keys).distinct()
        val affinities = allArtists.map { artist ->
            val total = artistPlayMap[artist] ?: 0
            val recent = artistRecentMap[artist] ?: 0
            val score = (total * 1.5f) + (recent * 3.0f)

            ArtistAffinity(
                artistName = artist,
                totalPlays = total,
                recentPlaysCount = recent,
                affinityScore = score,
                sampleTrack = artistSampleTrackMap[artist]
            )
        }.sortedByDescending { it.affinityScore }

        // Generate tailored recommendation search seed queries
        val seeds = mutableListOf<String>()
        val topArtistList = affinities.take(4)

        if (topArtistList.isNotEmpty()) {
            for (aff in topArtistList) {
                seeds.add("${aff.artistName} Greatest Hits")
                seeds.add("${aff.artistName} similar tracks")
            }
        } else {
            seeds.add("Top Hits 2026")
            seeds.add("Chill Lo-Fi Beats")
        }

        val primaryVibe = if (topArtistList.isNotEmpty()) {
            "Because you listened to ${topArtistList.first().artistName}"
        } else {
            "Trending Worldwide"
        }

        return TasteProfile(
            topArtists = affinities,
            totalPlaysLogged = totalPlays,
            recommendedSeeds = seeds,
            primaryVibe = primaryVibe
        )
    }
}
