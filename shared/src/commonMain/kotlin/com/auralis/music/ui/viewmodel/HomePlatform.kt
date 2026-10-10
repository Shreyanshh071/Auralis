package com.auralis.music.ui.viewmodel

import com.auralis.music.domain.model.DailyDiscoverItem
import com.auralis.music.domain.model.SimilarRecommendation
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.PlaylistListeningStats
import kotlinx.coroutines.flow.Flow

/**
 * What [HomeViewModel] needs from the platform: its saved home shelves and pins, listening
 * totals, the library artwork check's schedule, and hooks for verified metadata and stream warm-up.
 * The phone implements it with its DataStore cache, Room listening events, player and downloads.
 */
interface HomePlatform {
    suspend fun getCachedSpeedDial(): List<List<SpeedDialItem>>
    suspend fun saveSpeedDial(pages: List<List<SpeedDialItem>>)
    suspend fun getPinnedSpeedDialItems(): List<SpeedDialItem>
    suspend fun savePinnedSpeedDialItems(items: List<SpeedDialItem>)
    suspend fun pinSpeedDialItem(item: SpeedDialItem)
    suspend fun unpinSpeedDialItem(itemId: String)
    suspend fun getCachedSimilarRecommendations(): List<SimilarRecommendation>
    suspend fun saveSimilarRecommendations(recommendations: List<SimilarRecommendation>)
    suspend fun getCachedDailyDiscover(): List<DailyDiscoverItem>
    suspend fun saveDailyDiscover(items: List<DailyDiscoverItem>)

    /** Listening totals per playlist ID. */
    fun playlistListeningStats(): Flow<Map<String, PlaylistListeningStats>>
    fun clearPlaylistListening()
    /** Measured listening time per track ID, in ms. */
    fun trackListenTimes(): Flow<Map<String, Long>>

    /** Whether the library's artwork check should run now. */
    fun artworkRepairDue(): Boolean
    fun artworkRepairDone(unresolved: Int)
    /** A library song's verified album/artwork changed: update the player and offline copies. */
    suspend fun onTrackMetadataVerified(track: Track)

    /** Resolves [track]'s stream ahead of time so it starts instantly. */
    suspend fun prewarmStream(track: Track)
}
