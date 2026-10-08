package com.auralis.music.data.repository

import com.auralis.music.data.local.dao.SearchHistoryDao
import com.auralis.music.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/** Search history in the app's Room database (search_history table). */
class RoomSearchHistoryStore(private val dao: SearchHistoryDao) : SearchHistoryStore {
    override fun recentQueries(): Flow<List<String>> = dao.getRecentQueriesFlow()

    override suspend fun add(query: String, timestampMs: Long) =
        dao.insertSearchQuery(SearchHistoryEntity(query = query, timestamp = timestampMs))

    override suspend fun remove(query: String) = dao.deleteSearchQuery(query)

    override suspend fun clear() = dao.clearSearchHistory()
}
