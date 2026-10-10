package com.auralis.music.desktop.home

import com.auralis.music.desktop.playback.DesktopStreamResolver
import com.auralis.music.domain.model.DailyDiscoverItem
import com.auralis.music.domain.model.SimilarRecommendation
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.PlaylistListeningStats
import com.auralis.music.domain.recommendations.SpeedDialPins
import com.auralis.music.ui.viewmodel.HomePlatform
import com.auralis.music.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * [HomePlatform] on desktop: the phone's Home cache files (same names and JSON) in [dir].
 * Windows doesn't measure listening time yet, so the "listened" shelves stay empty rather
 * than guessed; there are no offline copies to update, and streams are looked up ahead.
 */
class DesktopHomePlatform(private val dir: File) : HomePlatform {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val speedDialSerializer = ListSerializer(ListSerializer(SpeedDialItem.serializer()))
    private val pinnedSerializer = ListSerializer(SpeedDialItem.serializer())
    private val similarSerializer = ListSerializer(SimilarRecommendation.serializer())
    private val dailySerializer = ListSerializer(DailyDiscoverItem.serializer())

    @Volatile private var speedDial: List<List<SpeedDialItem>>? = null
    @Volatile private var pinned: List<SpeedDialItem>? = null
    @Volatile private var similar: List<SimilarRecommendation>? = null
    @Volatile private var daily: List<DailyDiscoverItem>? = null

    private suspend fun <T> load(name: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        try {
            File(dir, name).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString(serializer, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable $name: ${e.message}")
            null
        }
    }

    private suspend fun <T> save(name: String, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            val file = File(dir, name)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save $name: ${e.message}")
        }
    }

    override suspend fun getCachedSpeedDial(): List<List<SpeedDialItem>> =
        speedDial?.takeIf { it.isNotEmpty() } ?: (load(SPEED_DIAL, speedDialSerializer) ?: emptyList()).also { speedDial = it }

    override suspend fun saveSpeedDial(pages: List<List<SpeedDialItem>>) {
        if (pages.isEmpty()) return
        speedDial = pages
        save(SPEED_DIAL, speedDialSerializer, pages)
    }

    override suspend fun getPinnedSpeedDialItems(): List<SpeedDialItem> =
        pinned ?: (load(PINNED, pinnedSerializer) ?: emptyList()).also { pinned = it }

    override suspend fun savePinnedSpeedDialItems(items: List<SpeedDialItem>) {
        pinned = items
        save(PINNED, pinnedSerializer, items)
    }

    override suspend fun pinSpeedDialItem(item: SpeedDialItem) =
        savePinnedSpeedDialItems(SpeedDialPins.pin(getPinnedSpeedDialItems(), item))

    override suspend fun unpinSpeedDialItem(itemId: String) =
        savePinnedSpeedDialItems(SpeedDialPins.unpin(getPinnedSpeedDialItems(), itemId))

    override suspend fun getCachedSimilarRecommendations(): List<SimilarRecommendation> =
        similar?.takeIf { it.isNotEmpty() } ?: (load(SIMILAR, similarSerializer) ?: emptyList()).also { similar = it }

    override suspend fun saveSimilarRecommendations(recommendations: List<SimilarRecommendation>) {
        if (recommendations.isEmpty()) return
        similar = recommendations
        save(SIMILAR, similarSerializer, recommendations)
    }

    override suspend fun getCachedDailyDiscover(): List<DailyDiscoverItem> =
        daily?.takeIf { it.isNotEmpty() } ?: (load(DAILY, dailySerializer) ?: emptyList()).also { daily = it }

    override suspend fun saveDailyDiscover(items: List<DailyDiscoverItem>) {
        if (items.isEmpty()) return
        daily = items
        save(DAILY, dailySerializer, items)
    }

    override fun playlistListeningStats(): Flow<Map<String, PlaylistListeningStats>> = flowOf(emptyMap())
    override fun clearPlaylistListening() {}
    override fun trackListenTimes(): Flow<Map<String, Long>> = flowOf(emptyMap())

    // The phone's library artwork check rewrites Room rows and offline copies; Windows has neither yet.
    override fun artworkRepairDue() = false
    override fun artworkRepairDone(unresolved: Int) {}
    override suspend fun onTrackMetadataVerified(track: Track) {}

    override suspend fun prewarmStream(track: Track) {
        DesktopStreamResolver.resolve(track.id)
    }

    private companion object {
        const val TAG = "DesktopHomePlatform"
        const val SPEED_DIAL = "speed_dial_cache.json"
        const val PINNED = "pinned_speed_dial.json"
        const val SIMILAR = "similar_recommendations_cache.json"
        const val DAILY = "daily_discover_cache.json"
    }
}
