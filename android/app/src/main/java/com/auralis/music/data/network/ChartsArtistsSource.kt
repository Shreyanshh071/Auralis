package com.auralis.music.data.network

import com.auralis.music.domain.model.Artist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The YouTube Music "Top artists" chart for a country (FEmusic_charts, ~40 artists), so the
 * onboarding picker suggests what people there actually listen to.
 */
object ChartsArtistsSource {
    private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"

    /** Search terms used only if charts cannot load; results still come from real artist searches. */
    fun fallbackNames(country: String): List<String> = when (country.uppercase()) {
        "IN" -> listOf("Arijit Singh", "Shreya Ghoshal", "Karan Aujla", "A R Rahman", "Diljit Dosanjh", "Sunidhi Chauhan")
        "KR" -> listOf("BTS", "BLACKPINK", "IU", "SEVENTEEN", "TWICE", "aespa")
        "JP" -> listOf("YOASOBI", "Ado", "Kenshi Yonezu", "Fujii Kaze", "Mrs. GREEN APPLE", "LiSA")
        "DE", "AT" -> listOf("Apache 207", "Nina Chuba", "Ayliva", "Rammstein", "Cro", "Taylor Swift")
        "FR" -> listOf("Aya Nakamura", "Gims", "Jul", "Stromae", "Angèle", "The Weeknd")
        "BR" -> listOf("Anitta", "Marília Mendonça", "Jorge & Mateus", "Ludmilla", "Luísa Sonza", "The Weeknd")
        "ES", "MX", "AR", "CO", "CL" -> listOf("Bad Bunny", "Karol G", "Shakira", "Rauw Alejandro", "Feid", "Rosalía")
        else -> listOf("Taylor Swift", "The Weeknd", "Billie Eilish", "Drake", "Kendrick Lamar", "Dua Lipa")
    }

    suspend fun topArtists(country: String, language: String): List<Artist> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("browseId", "FEmusic_charts")
            .put("formData", JSONObject().put("selectedValues", JSONArray().put(country)))
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20241201.01.00")
                .put("hl", language)
                .put("gl", country)))
        val request = Request.Builder()
            .url(BROWSE_URL)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Referer", "https://music.youtube.com/")
            .header("Origin", "https://music.youtube.com")
            .build()
        NetworkClientProvider.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            parseTopArtists(response.body?.string().orEmpty())
        }
    }

    /** Every artist row (channel browse id "UC…") in the chart response, in chart order. */
    fun parseTopArtists(raw: String): List<Artist> {
        val root = try { JSONObject(raw) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<Artist>()
        collectRows(root, out)
        return out.distinctBy { it.id }
    }

    private fun collectRows(node: Any?, out: MutableList<Artist>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { row -> artistOf(row)?.let(out::add) }
                for (key in node.keys()) collectRows(node.opt(key), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectRows(node.opt(i), out)
        }
    }

    private fun artistOf(row: JSONObject): Artist? {
        val id = row.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        if (!id.startsWith("UC")) return null
        val name = row.optJSONArray("flexColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.let { runs ->
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }?.trim().orEmpty()
        if (name.isBlank()) return null
        val thumbs = row.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
        return Artist(id = id, name = name, thumbnail = thumb?.let(::largerPortrait))
    }

    /** Chart rows carry 60–120px portraits; the same URL serves any size. */
    internal fun largerPortrait(url: String): String =
        url.replace(Regex("=w\\d+-h\\d+"), "=w400-h400")
}
