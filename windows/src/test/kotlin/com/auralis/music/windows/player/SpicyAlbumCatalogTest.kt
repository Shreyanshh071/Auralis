package com.auralis.music.windows.player

import com.auralis.music.data.network.*
import com.auralis.music.data.network.provider.*
import com.auralis.music.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class SpicyAlbumCatalogTest {
    private val albumId = "abcdefghijklmnopqrstuv"
    private val trackId = "zyxwvutsrqponmlkjihgfe"
    private fun album(duration: Long = 180000, uri: String = "spotify:album:$albumId") = """
        <script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{
        "type":"album","uri":"$uri","name":"Release","trackList":[
        {"uri":"spotify:track:$trackId","title":"Fixture","subtitle":"Artist","duration":$duration}]}}}}}}</script>
    """.trimIndent()

    @Test fun albumIdentityAndMeasuredRecordingMustMatch() {
        val tracks = SpicySpotifyMetadata.fromAlbumEmbed(album(), albumId)
        assertEquals(1, tracks.size)
        val query = LyricsSearchQuery("Fixture", "Artist", 180, durationMs = 180000)
        assertTrue(SpicyRecordingMatcher.matches(query, tracks.single()))
        assertFalse(SpicyRecordingMatcher.matches(query.copy(title = "Fixture (Live)"), tracks.single()))
        assertFalse(SpicyRecordingMatcher.matches(query.copy(artist = "Other Artist"), tracks.single()))
        assertFalse(SpicyRecordingMatcher.matches(query.copy(durationMs = 200000), tracks.single()))
        assertTrue(SpicySpotifyMetadata.fromAlbumEmbed(album(uri = "spotify:album:another"), albumId).isEmpty())
        assertTrue(SpicySpotifyMetadata.fromAlbumEmbed(album(duration = 0), albumId).isEmpty())
    }

    @Test fun missingIdentityUsesPublicReleaseMetadataWhenSpotifyApiIsLimitedAndCachesResult() = runBlocking {
        var apiRequests = 0
        var metadataRequests = 0
        var temporaryFailure = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val body: String
            val code: Int
            when {
                url.host == "api.spotify.com" -> { apiRequests++; code = 429; body = "{}" }
                url.host == "musicbrainz.org" && url.encodedPath.endsWith("/release/") -> {
                    metadataRequests++; code = 200
                    body = """{"releases":[{"id":"11111111-2222-3333-4444-555555555555","media":[{"format":"Digital Media"}]}]}"""
                }
                url.host == "musicbrainz.org" -> {
                    metadataRequests++
                    if (temporaryFailure) { temporaryFailure = false; code = 503; body = "{}" }
                    else {
                        code = 200
                        body = """{"relations":[{"url":{"resource":"https://open.spotify.com/album/$albumId"}}]}"""
                    }
                }
                url.host == "open.spotify.com" -> { code = 200; body = album() }
                url.host == "api.spicylyrics.org" -> {
                    code = 200
                    body = """{"Status":200,"Body":{"id":"$trackId","source":"apple_music","Type":"Syllable","Content":[
                    {"Type":"Vocal","Lead":{"StartTime":10,"EndTime":12,"Syllables":[{"Text":"Fixture","StartTime":10,"EndTime":12}]}}]}}"""
                }
                else -> error("Unexpected metadata request: ${url.host}")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Retry-After", if (code == 503) "1" else "7200").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val source = SpicyLyricsSource(client, apiKey = { "sl_pk_fixture" }, spotifyToken = { "fixture" })
        val query = LyricsSearchQuery("Fixture", "Artist", 180, "youtube-id", album = "Release", durationMs = 180000, reportFailures = true)
        val found = assertNotNull(source.search(query)).lyricsData
        assertEquals(SyncType.RICHSYNC, found.syncType)
        assertEquals(10000L, found.lines.single().words!!.single().time)
        assertEquals(2000L, found.lines.single().words!!.single().duration)
        assertNotNull(source.search(query.copy(videoId = "another-video", spotifyId = albumId)),
            "An old identity without usable track metadata can recover through verified album metadata")
        assertEquals(1, apiRequests, "Honor the API backoff instead of retrying its search endpoint")
        assertEquals(3, metadataRequests, "Retry one temporary failure, then reuse metadata across videos")
    }

    @Test fun publicCatalogueHonorsServerBackoffAcrossDifferentAlbumRequests() = runBlocking {
        var now = 1000L
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Limited")
                .header("Retry-After", "7200").body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val catalog = SpicyAlbumCatalog(client, clock = { now })
        val query = LyricsSearchQuery("Fixture", "Artist", 180, album = "Release", durationMs = 180000)
        assertNull(catalog.resolve(query)); assertEquals(1, requests)
        now += 300_000
        assertNull(catalog.resolve(query.copy(album = "Another Release")))
        assertEquals(1, requests, "Negative-cache expiry must not bypass the server's longer backoff")
        now += 6_900_000
        assertNull(catalog.resolve(query.copy(album = "Third Release")))
        assertEquals(2, requests)
    }

    @Test fun liveReportedSongWithoutImportedSpotifyIdentityHasSpicyTiming() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_LIVE_SPICY_CATALOG") == "1")
        val client = NetworkClientProvider.lyricsHttpClient.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false)
            .addInterceptor { chain ->
                chain.proceed(chain.request()).also { println("Recording metadata check: ${it.request.url.host} HTTP ${it.code}") }
            }.build()
        val source = SpicyLyricsSource(client, spotifyToken = { null })
        val result = assertNotNull(LyricsClient(spicyLyricsSource = source).searchProvider(LyricsProvider.SPICY_LYRICS,
            "Every Breath You Take", "The Police", 253, "-SaUrDhp034", album = "Synchronicity (Remastered 2003)",
            durationMs = 253000, reportFailures = true))
        assertEquals(SyncType.RICHSYNC, result.syncType)
        assertTrue(result.lines.any { it.words?.any { word -> (word.duration ?: 0) > 0 } == true })
        println("Public recording lookup succeeded: Every Breath You Take (${result.syncType}; real word timestamps)")
    }

    @Test fun communityLookupTriesValidatedEquivalentReleaseAfter404() = runBlocking {
        val firstId = "abcdefghijklmnopqrstuv"
        val secondId = "zyxwvutsrqponmlkjihgfe"
        val queried = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val body: String
            val code: Int
            if (url.host == "api.spotify.com") {
                code = 200
                body = """{"tracks":{"items":[
                {"id":"$firstId","name":"Fixture","duration_ms":180000,"artists":[{"name":"Artist"}]},
                {"id":"$secondId","name":"Fixture","duration_ms":180000,"artists":[{"name":"Artist"}]},
                {"id":"wrongwrongwrongwrongwro","name":"Fixture (Live)","duration_ms":180000,"artists":[{"name":"Artist"}]}]}}"""
            } else {
                queried += url.pathSegments.last()
                code = if (queried.last() == firstId) 404 else 200
                body = if (code == 404) "{}" else """{"Status":200,"Body":{"id":"$secondId","Type":"Syllable","Content":[
                {"Type":"Vocal","Lead":{"StartTime":10,"EndTime":12,"Syllables":[{"Text":"Fixture","StartTime":10,"EndTime":12}]}}]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val source = SpicyLyricsSource(client, apiKey = { "sl_pk_fixture" }, spotifyToken = { "fixture" })
        val result = source.search(LyricsSearchQuery("Fixture", "Artist", 180, durationMs = 180000))
        assertNotNull(result)
        assertEquals(listOf(firstId, secondId), queried)
    }
}
