package com.auralis.music.windows.player

import com.auralis.music.data.network.SpicySpotifyMetadata
import com.auralis.music.data.network.provider.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class SpicySpotifyMetadataTest {
    @Test fun liveCatalogMatrixResolvesSeveralDifferentArtists() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_LIVE_SPICY_MATRIX") == "1")
        val source = SpicyLyricsSource()
        val cases = listOf(
            Triple("6De8sirPzVyAoBDlQpcutY", "misery.|pupsies", 167L),
            Triple("3qhlB30KknSejmIvZZLjOD", "End of Beginning|Djo", 159L),
            Triple("6dGqGkYDoRrKh5UiIcTT22", "Understand|BoyWithUke", 171L),
            Triple("08Isz2ETWSBhvIl8UpKYsp", "No Love|Shubh", 170L),
            Triple("5O2P9iiztwhomNh8xkR9lJ", "Night Changes|One Direction", 226L))
        val repository = com.auralis.music.desktop.lyrics.DesktopLyricsRepository(
            knownTracks = { cases.asSequence().map { (id, name, _) ->
                val parts = name.split('|')
                com.auralis.music.domain.model.Track("sp_$id", parts[0], parts[1], duration = 0)
            } }, providerLookup = { _, query -> source.search(query.copy(reportFailures = true))?.lyricsData })
        val failures = mutableListOf<String>()
        for ((id, name, duration) in cases) {
            val parts = name.split('|')
            try {
                val result = repository.fetchProvider(com.auralis.music.domain.model.LyricsProvider.SPICY_LYRICS,
                    LyricsSearchQuery(parts[0], parts[1], duration, "fixture-$id", durationMs = duration * 1000))
                if (result == null) failures += "$name: empty result"
                else if (result.syncType != com.auralis.music.domain.model.SyncType.RICHSYNC ||
                    result.lines.none { line -> line.words?.any { it.time > 0 && (it.duration ?: 0) > 0 } == true })
                    failures += "$name: missing word timestamps"
                else println("Live imported-recording lookup succeeded: $name (${result.syncType}, source word timings preserved)")
            } catch (e: java.io.IOException) { failures += "$name: ${e.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test fun liveReportedRecordingResolvesWhenExplicitlyRequested() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_LIVE_SPICY") == "1")
        val result = SpicyLyricsSource().search(LyricsSearchQuery("Love Me Not", "Ravyn Lenae", 213,
            "HfpR4tAmI7E", durationMs = 213000, spotifyId = "1UNEuG9DYOWiikf00ayr52", reportFailures = true))
        assertNotNull(result, "The reported song should resolve through its verified library identity")
        assertTrue(result.lyricsData.lines.isNotEmpty())
    }

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

    @Test fun incompleteLibraryHintMustPassMeasuredDurationBeforeLyricsAreFetched() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("open.spotify.com", chain.request().url.host,
                "A mismatched recording must never reach the lyrics endpoint")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(embed(duration = 210000).toResponseBody("text/html".toMediaType())).build()
        }.build()
        val source = SpicyLyricsSource(client, apiKey = { "sl_pk_fixture" }, spotifyToken = {
            error("Public metadata already proves this is a different recording")
        })
        val repository = com.auralis.music.desktop.lyrics.DesktopLyricsRepository(
            knownTracks = { sequenceOf(com.auralis.music.domain.model.Track("sp_$id", "Fixture", "Artist", duration = 0)) },
            providerLookup = { _, query -> source.search(query)?.lyricsData })
        assertNull(repository.fetchProvider(com.auralis.music.domain.model.LyricsProvider.SPICY_LYRICS,
            LyricsSearchQuery("Fixture", "Artist", 180, "youtube-id", durationMs = 180000)))
        assertEquals(1, requests)
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
