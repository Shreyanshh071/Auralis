package com.auralis.music.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The portrait Apple Music shows on an artist's page, found from their name.
 *
 * Adapted from BitChord's AppleArtistPhotos and AppleCatalogue (GPL-3.0):
 * https://github.com/kushagrasinghx/BitChord
 *
 * A name is not an identity: Apple has a "Krsna" who is not KR$NA. So a same-named Apple artist only
 * counts when their catalogue shares a song or release title with the page we already have. An
 * artist Apple has no photo of gets one of their record covers instead, which counts as no photo.
 */
internal object AppleArtistPhoto {
    private const val SEARCH = "https://itunes.apple.com/search"
    private const val LOOKUP = "https://itunes.apple.com/lookup"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"
    private const val HEAD_LIMIT = 64 * 1024
    private const val MATCHES_TRIED = 2
    private const val MIN_TITLE_KEY = 4

    private val OG_IMAGE = Regex("""property="og:image"\s+content="([^"]+)"""")

    private val client: OkHttpClient by lazy {
        NetworkClientProvider.okHttpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** The artist's photo, or null when Apple has none that provably is them. Throws when the network fails. */
    suspend fun find(artistName: String, titles: Collection<String>): String? = withContext(Dispatchers.IO) {
        val name = matchKey(artistName)
        val keys = titles.mapNotNullTo(HashSet(), ::titleKey)
        if (name.isEmpty() || keys.isEmpty()) return@withContext null
        val url = SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("term", artistName)
            .addQueryParameter("entity", "musicArtist")
            .addQueryParameter("limit", "5")
            .build().toString()
        JSONObject(get(url)).optJSONArray("results").objects().asSequence()
            .filter { matchKey(it.optString("artistName")) == name }
            .filter { it.optString("artistLinkUrl").startsWith("https://music.apple.com/") }
            .filter { sharesTitles(it.optLong("artistId"), keys) }
            .map { it.optString("artistLinkUrl").substringBefore('?') }
            .take(MATCHES_TRIED)
            .firstNotNullOfOrNull(::pagePhoto)
    }

    /** One lookup covers songs and releases: each track row carries its album's name. */
    private fun sharesTitles(artistId: Long, keys: Set<String>): Boolean {
        if (artistId <= 0) return false
        return JSONObject(get("$LOOKUP?id=$artistId&entity=song&limit=200")).optJSONArray("results").objects().any { row ->
            titleKey(row.optString("trackName")) in keys || titleKey(row.optString("collectionName")) in keys
        }
    }

    /** The tag sits near the top of a page that runs to most of a megabyte, so the rest is never downloaded. */
    private fun pagePhoto(pageUrl: String): String? = call(pageUrl).use { response ->
        if (!response.isSuccessful) throw IOException("Apple Music page failed: ${response.code}")
        val source = response.body?.source() ?: return null
        val buffer = Buffer()
        val head = StringBuilder()
        while (head.length < HEAD_LIMIT && source.read(buffer, 8_192) != -1L) {
            head.append(buffer.readUtf8())
            parsePhoto(head)?.let { return it }
            if ("</head>" in head) return null
        }
        null
    }

    private fun get(url: String): String = call(url).use { response ->
        if (!response.isSuccessful) throw IOException("iTunes request failed: ${response.code}")
        response.body?.string() ?: throw IOException("iTunes sent no body")
    }

    private fun call(url: String) = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()

    /** Apple's tag asks for a 1200x630 crop; the last path segment picks the size, keeping the whole photo. */
    internal fun parsePhoto(head: CharSequence): String? = OG_IMAGE.find(head)?.groupValues?.get(1)
        ?.takeIf(::isArtistPhoto)
        ?.let { it.substringBeforeLast('/') + "/1200x1200bb.jpg" }

    /** Apple's artist photos live under Features or AMCArtistImages; a record cover lives under Music. */
    internal fun isArtistPhoto(url: String): Boolean {
        val bucket = url.substringAfter("mzstatic.com/image/thumb/", "").substringBefore('/')
        return bucket.startsWith("Features") || bucket.startsWith("AMCArtistImages")
    }

    /** Case, accents and punctuation don't make two names different. A `$` standing in for an S is read as one. */
    internal fun matchKey(name: String): String =
        Normalizer.normalize(name.lowercase(Locale.ROOT).replace('$', 's'), Normalizer.Form.NFD).filter(Char::isLetterOrDigit)

    /** Apple adds " - Single" and " - EP". Too short a title ("War", "Intro") proves nothing and is dropped. */
    internal fun titleKey(title: String): String? = matchKey(
        title.substringBefore(" (").substringBefore(" [").removeSuffix(" - Single").removeSuffix(" - EP")
    ).takeIf { it.length >= MIN_TITLE_KEY }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
}
