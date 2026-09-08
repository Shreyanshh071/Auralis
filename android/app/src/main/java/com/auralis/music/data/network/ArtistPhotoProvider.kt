package com.auralis.music.data.network

import android.content.Context
import android.content.SharedPreferences
import com.auralis.music.data.local.dao.LibraryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object ArtistPhotoProvider {
    private val memoryCache = ConcurrentHashMap<String, String>()
    private var prefs: SharedPreferences? = null
    private var innerTubeClient: InnerTubeClient? = null
    private var libraryDao: LibraryDao? = null

    fun init(context: Context, client: InnerTubeClient, libDao: LibraryDao? = null) {
        prefs = context.getSharedPreferences("auralis_artist_photos", Context.MODE_PRIVATE)
        innerTubeClient = client
        libraryDao = libDao

        prefs?.all?.forEach { (key, value) ->
            if (value is String && value.isNotBlank() && !value.contains("i.ytimg.com/vi/")) {
                memoryCache[key.lowercase().trim()] = value
            }
        }
    }

    fun getCachedPhoto(artistName: String): String? {
        val key = artistName.trim().lowercase()
        if (key.isBlank()) return null
        return memoryCache[key]?.takeIf { it.isNotBlank() && !it.contains("i.ytimg.com/vi/") }
            ?: prefs?.getString(key, null)?.takeIf { it.isNotBlank() && !it.contains("i.ytimg.com/vi/") }
    }

    internal fun resetForTesting(client: InnerTubeClient? = null) {
        memoryCache.clear()
        prefs = null
        innerTubeClient = client
        libraryDao = null
    }

    suspend fun resolveArtistPhoto(artistName: String): String? = withContext(Dispatchers.IO) {
        val cleanName = artistName.trim()
        val key = cleanName.lowercase()
        if (key.isBlank()) return@withContext null

        getCachedPhoto(cleanName)?.let { return@withContext it }

        // 1. Check LibraryDao saved_artists if available
        try {
            val savedThumb = libraryDao?.getSavedArtistThumbnail(cleanName)
            if (!savedThumb.isNullOrBlank() && !savedThumb.contains("i.ytimg.com/vi/")) {
                memoryCache[key] = savedThumb
                prefs?.edit()?.putString(key, savedThumb)?.apply()
                return@withContext savedThumb
            }
        } catch (_: Exception) {}

        // 2. Query InnerTube YouTube Music artist search for official artist photo
        val client = innerTubeClient ?: return@withContext null
        try {
            val results = client.search(cleanName, InnerTubeClient.FILTER_ARTISTS).artists
            val match = results.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
                ?: results.firstOrNull { it.name.contains(cleanName, ignoreCase = true) || cleanName.contains(it.name, ignoreCase = true) }
                ?: results.firstOrNull()

            val thumb = match?.thumbnail
            if (!thumb.isNullOrBlank() && !thumb.contains("i.ytimg.com/vi/")) {
                memoryCache[key] = thumb
                prefs?.edit()?.putString(key, thumb)?.apply()
                return@withContext thumb
            }
        } catch (_: Exception) {}
        null
    }
}
