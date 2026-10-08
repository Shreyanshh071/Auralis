package com.auralis.music.data.repository

import kotlinx.coroutines.flow.Flow

/** Where recent search queries are kept. Android keeps them in its Room database. */
interface SearchHistoryStore {
    /** The 20 most recent queries, newest first. */
    fun recentQueries(): Flow<List<String>>

    /** Records [query]; searching it again moves it back to the top. */
    suspend fun add(query: String, timestampMs: Long)

    suspend fun remove(query: String)

    suspend fun clear()
}
