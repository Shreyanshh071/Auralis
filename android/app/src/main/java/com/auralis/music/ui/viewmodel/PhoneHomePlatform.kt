package com.auralis.music.ui.viewmodel

import android.content.Context
import com.auralis.music.data.datastore.HomeRecommendationsCache
import com.auralis.music.data.datastore.PlaylistListeningStore
import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.DailyDiscoverItem
import com.auralis.music.domain.model.SimilarRecommendation
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.PlaylistListeningStats
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.data.network.InnerTubeClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** [HomePlatform] on the phone: the same DataStore cache, Room listening events, player and downloads as before. */
class PhoneHomePlatform(
    private val context: Context,
    private val audioPlayer: AuralisAudioPlayer? = null
) : HomePlatform {
    override suspend fun getCachedSpeedDial() = HomeRecommendationsCache.getCachedSpeedDial(context)
    override suspend fun saveSpeedDial(pages: List<List<SpeedDialItem>>) = HomeRecommendationsCache.saveSpeedDial(context, pages)
    override suspend fun getPinnedSpeedDialItems() = HomeRecommendationsCache.getPinnedSpeedDialItems(context)
    override suspend fun savePinnedSpeedDialItems(items: List<SpeedDialItem>) = HomeRecommendationsCache.savePinnedSpeedDialItems(context, items)
    override suspend fun pinSpeedDialItem(item: SpeedDialItem) = HomeRecommendationsCache.pinSpeedDialItem(context, item)
    override suspend fun unpinSpeedDialItem(itemId: String) = HomeRecommendationsCache.unpinSpeedDialItem(context, itemId)
    override suspend fun getCachedSimilarRecommendations() = HomeRecommendationsCache.getCachedSimilarRecommendations(context)
    override suspend fun saveSimilarRecommendations(recommendations: List<SimilarRecommendation>) =
        HomeRecommendationsCache.saveSimilarRecommendations(context, recommendations)
    override suspend fun getCachedDailyDiscover() = HomeRecommendationsCache.getCachedDailyDiscover(context)
    override suspend fun saveDailyDiscover(items: List<DailyDiscoverItem>) = HomeRecommendationsCache.saveDailyDiscover(context, items)

    override fun playlistListeningStats(): Flow<Map<String, PlaylistListeningStats>> {
        PlaylistListeningStore.load(context)
        return PlaylistListeningStore.stats
    }

    override fun clearPlaylistListening() = PlaylistListeningStore.clear(context)

    override fun trackListenTimes(): Flow<Map<String, Long>> =
        com.auralis.music.data.local.AuralisDatabase.getInstance(context).playbackEventDao().getListenTimeByTrack()
            .map { times -> times.associate { it.trackId to it.totalMs } }

    private val artworkRepairPrefs get() = context.getSharedPreferences("auralis_verified_artwork_repair", Context.MODE_PRIVATE)

    override fun artworkRepairDue(): Boolean = artworkRepairPrefs.let { prefs ->
        prefs.getInt("version", 0) < 11 &&
            System.currentTimeMillis() - prefs.getLong("last_attempt_v11", 0L) > 24L * 60 * 60 * 1000
    }

    override fun artworkRepairDone(unresolved: Int) {
        artworkRepairPrefs.edit().putLong("last_attempt_v11", System.currentTimeMillis())
            .apply {
                if (unresolved == 0) putInt("version", 11)
            }.apply()
    }

    override suspend fun onTrackMetadataVerified(track: Track) {
        withContext(Dispatchers.Main) {
            audioPlayer?.applyVerifiedTrackMetadata(track)
        }
        try {
            com.auralis.music.data.download.AuralisDownloadManager.applyVerifiedTrackMetadata(track, context)
        } catch (e: Exception) {
            android.util.Log.w("HomeViewModel", "Offline artwork update deferred: ${e.message}")
        }
    }

    override suspend fun prewarmStream(track: Track) {
        val isCached = AudioStreamResolver.getCachedStream(track.id) != null
        if (!isCached) {
            AudioStreamResolver.resolveAudioStream(track.id, track.title, track.artist, duration = track.duration)
        }
    }
}

/**
 * The phone's way to build [HomeViewModel] (same parameters as before it moved to shared): a
 * Context and player become its [PhoneHomePlatform].
 */
fun HomeViewModel(
    historyRepository: HistoryRepository,
    searchRepository: SearchRepository,
    innerTubeClient: InnerTubeClient = InnerTubeClient(),
    context: Context? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    libraryRepository: LibraryRepository? = null,
    audioPlayer: AuralisAudioPlayer? = null
): HomeViewModel = HomeViewModel(
    historyRepository = historyRepository,
    searchRepository = searchRepository,
    innerTubeClient = innerTubeClient,
    platform = context?.let { PhoneHomePlatform(it, audioPlayer) },
    ioDispatcher = ioDispatcher,
    defaultDispatcher = defaultDispatcher,
    libraryRepository = libraryRepository
)
