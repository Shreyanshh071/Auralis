package com.auralis.music.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class SearchSuggestionsClient(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient
) {
    companion object {
        private const val YT_MUSIC_SUGGEST_API = "https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun getSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || trimmed.length < 2) return@withContext emptyList()

        // 1. Primary: Direct YouTube Music InnerTube Suggestion Engine
        try {
            val payload = JSONObject().apply {
                put("input", trimmed)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241201.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val request = Request.Builder()
                .url(YT_MUSIC_SUGGEST_API)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val ytmSuggestions = parseYtMusicSuggestionsJson(body)
                    if (ytmSuggestions.isNotEmpty()) {
                        return@withContext ytmSuggestions
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback: Google Suggest with music query
        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            parseSuggestionsJson(body)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseYtMusicSuggestionsJson(jsonString: String): List<String> {
        val results = mutableListOf<String>()
        try {
            val root = JSONObject(jsonString)
            val contents = root.optJSONArray("contents")
                ?.optJSONObject(0)
                ?.optJSONObject("searchSuggestionsSectionRenderer")
                ?.optJSONArray("contents") ?: JSONArray()

            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i)?.optJSONObject("searchSuggestionRenderer")
                val runs = item?.optJSONObject("suggestion")?.optJSONArray("runs")
                if (runs != null) {
                    val text = buildString {
                        for (j in 0 until runs.length()) {
                            append(runs.optJSONObject(j)?.optString("text") ?: "")
                        }
                    }.trim()
                    if (text.isNotBlank()) {
                        results.add(text)
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    fun parseSuggestionsJson(jsonString: String): List<String> {
        val results = mutableListOf<String>()
        try {
            val root = JSONArray(jsonString)
            if (root.length() > 1) {
                val suggestions = root.optJSONArray(1)
                if (suggestions != null) {
                    for (i in 0 until suggestions.length()) {
                        val item = suggestions.optString(i)
                        if (!item.isNullOrBlank()) {
                            results.add(item)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored
        }
        return results
    }
}
