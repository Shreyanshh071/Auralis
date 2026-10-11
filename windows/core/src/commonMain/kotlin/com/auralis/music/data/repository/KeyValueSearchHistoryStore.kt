package com.auralis.music.data.repository

import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Search history in a [KeyValueStore] (apps without a database for it, like the Windows app). */
class KeyValueSearchHistoryStore(private val store: KeyValueStore) : SearchHistoryStore {
    private val queries = MutableStateFlow(load())

    private fun load(): List<String> = runCatching {
        val saved = JSONArray(store.getString(KEY, "[]").orEmpty().ifBlank { "[]" })
        List(saved.length()) { saved.getString(it) }
    }.getOrDefault(emptyList())

    private fun save(list: List<String>) {
        queries.value = list
        store.putStrings(mapOf(KEY to JSONArray().apply { list.forEach { put(it) } }.toString()))
    }

    override fun recentQueries(): Flow<List<String>> = queries.asStateFlow()

    override suspend fun add(query: String, timestampMs: Long) =
        save((listOf(query) + queries.value.filterNot { it.equals(query, ignoreCase = true) }).take(MAX))

    override suspend fun remove(query: String) = save(queries.value.filterNot { it == query })

    override suspend fun clear() = save(emptyList())

    private companion object {
        const val KEY = "recent_searches"
        const val MAX = 20
    }
}
