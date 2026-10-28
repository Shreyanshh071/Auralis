package com.auralis.music.windows.player

import com.auralis.music.data.network.SpicySpotifyMetadata
import com.auralis.music.data.network.provider.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class SpicySpotifyMetadataTest {
    private val id = "abcdefghijklmnopqrstuv"
    private fun embed(uri: String = "spotify:track:$id", duration: Long = 180000) = """
        <script type="application/json" id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{
        "type":"track","uri":"$uri","name":"Fixture","duration":$duration,"artists":[{"name":"Artist"}]}}}}}}</script>
    """.trimIndent()

    @Test fun publicEmbedRequiresExactTrackIdentityAndMeasuredDuration() {
        val result = assertNotNull(SpicySpotifyMetadata.fromEmbed(embed(), id))
        assertEquals(180000L, result.durationMs)
        assertEquals("Artist", result.artist)
        assertNull(SpicySpotifyMetadata.fromEmbed(embed(uri = "spotify:track:zyxwvutsrqponmlkjihgfe"), id))
        assertNull(SpicySpotifyMetadata.fromEmbed(embed(duration = 0), id))
        assertNull(SpicySpotifyMetadata.fromEmbed("<html>unavailable</html>", id))
    }

    @Test fun knownRecordingDoesNotRequireAnAccountTokenAndMetadataRateLimitsAreRespected() = runBlocking {
        var requests = 0
        var now = 1000L
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Limited")
                .header("Retry-After", "7200").body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val source = SpicyLyricsSource(client, apiKey = { "sl_pk_fixture" }, spotifyToken = {
            error("An explicitly supplied recording must not need a session when public metadata is limited")
        }, clock = { now })
        val query = LyricsSearchQuery("Fixture", "Artist", 180, "abcdefghijk", spotifyId = id)
        assertNull(source.search(query)); assertEquals(1, requests)
        assertNull(source.search(query.copy(videoId = "other-song"))); assertEquals(1, requests)
        now += 60000
        assertNull(source.search(query)); assertEquals(1, requests, "Honor the full server backoff, including waits longer than one hour")
        now += 7_140_000L
        assertNull(source.search(query)); assertEquals(2, requests)
    }
}
