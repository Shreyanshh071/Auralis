package com.auralis.music.data.network

import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.data.network.provider.SpicyLyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import kotlin.test.*

class SpicyCatalogRecoveryTest {
    private val albumId = "abcdefghijklmnopqrstuv"
    private val trackId = "zyxwvutsrqponmlkjihgfe"
    private val releaseId = "11111111-2222-3333-4444-555555555555"
    private val query = LyricsSearchQuery("Fixture", "Artist", 180, "youtube-id", durationMs = 180000)
    private val releases = """[{"id":"$releaseId"}]"""
    private fun album(duration: Long = 180000) = """
        <script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{
        "type":"album","uri":"spotify:album:$albumId","name":"Release","trackList":[
        {"uri":"spotify:track:$trackId","title":"Fixture","subtitle":"Artist","duration":$duration}]}}}}}}</script>
    """.trimIndent()
    private fun response(request: Request, body: String, code: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun albumlessSongReachesSpicyAfterSpotifyRateLimitAndRejectsWrongDuration() = runBlocking {
        for (duration in listOf(180000L, 210000L)) {
            var lyricsRequests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val url = request.url
                when {
                    url.host == "api.spotify.com" -> response(request, "{}", 429)
                    url.encodedPath.endsWith("/recording/") -> {
                        assertEquals("recording:\"Fixture\" AND artist:\"Artist\"", url.queryParameter("query"))
                        response(request, """{"recordings":[{"releases":$releases}]}""")
                    }
                    url.host == "musicbrainz.org" -> response(request,
                        """{"relations":[{"url":{"resource":"https://open.spotify.com/album/$albumId"}}]}""")
                    url.host == "open.spotify.com" -> response(request, album(duration))
                    url.host == "api.spicylyrics.org" -> {
                        lyricsRequests++
                        assertTrue(url.encodedPath.endsWith(trackId))
                        response(request, """{"Status":200,"Body":{"id":"$trackId","source":"spotify","Type":"Line","Content":[{"Type":"Vocal","Text":"Fixture line","StartTime":10,"EndTime":12}]}}""")
                    }
                    else -> error("Unexpected metadata request: $url")
                }
            }.build()
            val source = SpicyLyricsSource(client, apiKey = { "sl_pk_fixture" }, spotifyToken = { "fixture" })
            val result = source.search(query)
            if (duration == 180000L) assertNotNull(result) else assertNull(result)
            assertEquals(if (duration == 180000L) 1 else 0, lyricsRequests)
        }
    }

    @Test fun albumlessMissIsScopedToSongAndTemporaryFailureIsRetriedAfterBackoff() = runBlocking {
        var now = 1000L
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            if (requests == 1) response(chain.request(), "{}", 500)
            else response(chain.request(), """{"recordings":[]}""")
        }.build()
        val catalog = SpicyAlbumCatalog(client, clock = { now })
        assertNull(catalog.resolve(query))
        assertEquals(1, requests)
        now += 300000L
        assertNull(catalog.resolve(query))
        assertEquals(2, requests, "A transient failure must not install a fresh negative cache")
        assertNull(catalog.resolve(query))
        assertEquals(2, requests, "A completed empty search may be cached")
        assertNull(catalog.resolve(query.copy(title = "Another Song")))
        assertEquals(3, requests, "Songs without an album need separate negative entries")
    }
}
