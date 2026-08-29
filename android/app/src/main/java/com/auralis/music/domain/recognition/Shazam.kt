package com.auralis.music.domain.recognition

import com.auralis.music.data.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object Shazam {
    private const val CACHE_DURATION_MS = 300_000L // 5 minutes
    private val resultCache = ConcurrentHashMap<String, CachedResult>()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val userAgents = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 14; Build/UP1A.231005.007)",
        "Dalvik/2.1.0 (Linux; U; Android 13; SM-S918B Build/TP1A.220624.014)",
        "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 6 Build/SQ3A.220705.004)",
        "Dalvik/2.1.0 (Linux; U; Android 11; SM-G998B Build/RP1A.200720.012)",
        "Dalvik/2.1.0 (Linux; U; Android 10; SM-G973F Build/QP1A.190711.020)"
    )

    private val timezones = listOf(
        "Europe/Paris", "Europe/London", "America/New_York",
        "America/Los_Angeles", "Asia/Tokyo", "Asia/Dubai", "Asia/Kolkata"
    )

    private data class CachedResult(
        val timestamp: Long,
        val result: RecognitionResult
    )

    suspend fun recognize(signature: String, sampleDurationMs: Long): Result<RecognitionResult> = withContext(Dispatchers.IO) {
        val cacheKey = signature.hashCode().toString()
        getCachedResult(cacheKey)?.let {
            return@withContext Result.success(it)
        }

        try {
            val timestamp = System.currentTimeMillis() / 1000
            val uuid1 = UUID.randomUUID().toString().uppercase()
            val uuid2 = UUID.randomUUID().toString()

            val requestObj = ShazamRequestJson(
                geolocation = ShazamRequestJson.Geolocation(
                    altitude = Random.nextDouble() * 400 + 100,
                    latitude = Random.nextDouble() * 180 - 90,
                    longitude = Random.nextDouble() * 360 - 180
                ),
                signature = ShazamRequestJson.Signature(
                    samplems = sampleDurationMs,
                    timestamp = timestamp,
                    uri = signature
                ),
                timestamp = timestamp,
                timezone = timezones.random()
            )

            val jsonBodyStr = json.encodeToString(requestObj)

            val urlBuilder = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/$uuid1/$uuid2".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("sync", "true")
                ?.addQueryParameter("webv3", "true")
                ?.addQueryParameter("sampling", "true")
                ?.addQueryParameter("connected", "")
                ?.addQueryParameter("shazamapiversion", "v3")
                ?.addQueryParameter("sharehub", "true")
                ?.addQueryParameter("video", "v3")
                ?.build() ?: return@withContext Result.failure(Exception("Invalid URL"))

            val request = Request.Builder()
                .url(urlBuilder)
                .header("User-Agent", userAgents.random())
                .header("Content-Language", "en_US")
                .header("Content-Type", "application/json")
                .post(jsonBodyStr.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = NetworkClientProvider.okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Shazam error: HTTP ${response.code}"))
            }

            val body = response.body?.string().orEmpty()
            val respJson = json.decodeFromString<ShazamResponseJson>(body)
            val recognitionResult = respJson.toRecognitionResult()

            if (recognitionResult != null) {
                cacheResult(cacheKey, recognitionResult)
                Result.success(recognitionResult)
            } else {
                Result.failure(Exception("No match found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getCachedResult(key: String): RecognitionResult? {
        val cached = resultCache[key] ?: return null
        if (System.currentTimeMillis() - cached.timestamp > CACHE_DURATION_MS) {
            resultCache.remove(key)
            return null
        }
        return cached.result
    }

    private fun cacheResult(key: String, result: RecognitionResult) {
        resultCache[key] = CachedResult(System.currentTimeMillis(), result)
    }

    private fun ShazamResponseJson.toRecognitionResult(): RecognitionResult? {
        val tr = this.track ?: return null

        val songSection = tr.sections?.find { it?.type == "SONG" }
        val metadata = songSection?.metadata
        val album = metadata?.find { it?.title == "Album" }?.text
        val label = metadata?.find { it?.title == "Label" }?.text
        val releaseDate = metadata?.find { it?.title == "Released" }?.text

        val lyricsSection = tr.sections?.find { it?.type == "LYRICS" }
        val lyrics = lyricsSection?.text

        val cover = tr.images?.coverarthq ?: tr.images?.coverart

        val youtubeAction = tr.hub?.options?.find {
            it?.type?.contains("video", ignoreCase = true) == true
        }?.actions?.firstOrNull() ?: tr.hub?.actions?.find {
            it?.type?.contains("video", ignoreCase = true) == true
        }

        val youtubeVideoId = youtubeAction?.uri?.let { uri ->
            uri.substringAfterLast("v=", "").takeIf { it.isNotEmpty() }
                ?: uri.substringAfterLast("/", "").takeIf { it.isNotEmpty() && it.length == 11 }
        }

        return RecognitionResult(
            trackId = tr.key ?: tagid ?: UUID.randomUUID().toString(),
            title = tr.title.orEmpty(),
            artist = tr.subtitle.orEmpty(),
            album = album,
            coverArtUrl = cover,
            coverArtHqUrl = tr.images?.coverarthq,
            genre = tr.genres?.primary,
            releaseDate = releaseDate,
            label = label,
            lyrics = lyrics,
            shazamUrl = tr.url,
            isrc = tr.isrc,
            youtubeVideoId = youtubeVideoId
        )
    }
}
