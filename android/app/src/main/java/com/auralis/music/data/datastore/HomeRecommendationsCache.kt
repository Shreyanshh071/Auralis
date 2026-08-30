package com.auralis.music.data.datastore

import android.content.Context
import com.auralis.music.domain.model.DailyDiscoverItem
import com.auralis.music.domain.model.SimilarRecommendation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * High-performance disk & memory persistence for Home recommendation shelves
 * ("Similar to [Artist]" & "Similar to [Song]" & "Daily Discover").
 * Guarantees that recommendations are instantly visible upon opening the app (0ms latency),
 * persisting seamlessly across app sessions and updates.
 */
object HomeRecommendationsCache {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var inMemorySimilarRecs: List<SimilarRecommendation>? = null

    @Volatile
    private var inMemoryDailyDiscover: List<DailyDiscoverItem>? = null

    private fun getSimilarRecsFile(context: Context): File {
        return File(context.filesDir, "similar_recommendations_cache.json")
    }

    private fun getDailyDiscoverFile(context: Context): File {
        return File(context.filesDir, "daily_discover_cache.json")
    }

    suspend fun getCachedSimilarRecommendations(context: Context): List<SimilarRecommendation> {
        inMemorySimilarRecs?.let { if (it.isNotEmpty()) return it }

        return withContext(Dispatchers.IO) {
            try {
                val file = getSimilarRecsFile(context)
                if (file.exists()) {
                    val content = file.readText()
                    if (content.isNotBlank()) {
                        val parsed = json.decodeFromString<List<SimilarRecommendation>>(content)
                        inMemorySimilarRecs = parsed
                        return@withContext parsed
                    }
                }
            } catch (_: Exception) {}
            emptyList()
        }
    }

    suspend fun saveSimilarRecommendations(context: Context, recommendations: List<SimilarRecommendation>) {
        if (recommendations.isEmpty()) return
        inMemorySimilarRecs = recommendations
        withContext(Dispatchers.IO) {
            try {
                val file = getSimilarRecsFile(context)
                val content = json.encodeToString(recommendations)
                file.writeText(content)
            } catch (_: Exception) {}
        }
    }

    suspend fun getCachedDailyDiscover(context: Context): List<DailyDiscoverItem> {
        inMemoryDailyDiscover?.let { if (it.isNotEmpty()) return it }

        return withContext(Dispatchers.IO) {
            try {
                val file = getDailyDiscoverFile(context)
                if (file.exists()) {
                    val content = file.readText()
                    if (content.isNotBlank()) {
                        val parsed = json.decodeFromString<List<DailyDiscoverItem>>(content)
                        inMemoryDailyDiscover = parsed
                        return@withContext parsed
                    }
                }
            } catch (_: Exception) {}
            emptyList()
        }
    }

    suspend fun saveDailyDiscover(context: Context, items: List<DailyDiscoverItem>) {
        if (items.isEmpty()) return
        inMemoryDailyDiscover = items
        withContext(Dispatchers.IO) {
            try {
                val file = getDailyDiscoverFile(context)
                val content = json.encodeToString(items)
                file.writeText(content)
            } catch (_: Exception) {}
        }
    }
}
