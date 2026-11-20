package com.auralis.music.desktop.library

import com.auralis.music.data.network.YouTubeShortsFilter
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.map

/** Displayable history, without rewriting the account's recorded listening data. */
class MusicHistoryRepository(private val source: HistoryRepository,
    private val filter: suspend (List<Track>) -> List<Track> = YouTubeShortsFilter::filter
) : HistoryRepository by source {
    override fun getHistory() = source.getHistory().map { entries ->
        val ids = filter(entries.map { it.track }).map { it.id }.toSet()
        entries.filter { it.track.id in ids }
    }
    override fun getTopPlayedTracks() = source.getTopPlayedTracks().map { entries ->
        val ids = filter(entries.map { it.track }).map { it.id }.toSet()
        entries.filter { it.track.id in ids }
    }
    override suspend fun getPlayCounts() = source.getPlayCounts().let { entries ->
        val ids = filter(entries.map { it.track }).map { it.id }.toSet()
        entries.filter { it.track.id in ids }
    }
    override suspend fun getForgottenFavorites(cutoffTimestamp: Long) = filter(source.getForgottenFavorites(cutoffTimestamp))
    override suspend fun getRecentHeavyRotation(fromTimestamp: Long) = filter(source.getRecentHeavyRotation(fromTimestamp))
    override suspend fun getLikedSeeds(limit: Int) = filter(source.getLikedSeeds(limit))
}
