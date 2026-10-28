package com.auralis.music.windows.home

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.domain.model.Artist
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.Test
import com.auralis.music.data.network.YouTubeBackoff
import com.auralis.music.data.network.searchBody
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody

class ArtistPageLoadingTest {
    @Test fun tinyExactNameInArtistFilterDoesNotHideTheEstablishedGeneralSearchArtist() = runBlocking {
        val official = Artist("UCofficial", "De La Soul", subscribers = "650K subscribers")
        val tiny = official.copy(id = "UCfan", subscribers = "11 subscribers")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val id = org.json.JSONObject(chain.request().body!!.let { body -> okio.Buffer().also { body.writeTo(it) }.readUtf8() }).optString("browseId")
            assertEquals(official.id, id)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"De La Soul"}]}}}}""".toResponseBody()).build()
        }.build()
        val tube = object : InnerTubeClient(http) {
            override suspend fun search(query: String, params: String?) =
                if (params == FILTER_ARTISTS) SearchResults(artists = listOf(tiny))
                else SearchResults(artists = listOf(official), songs = listOf(Track("song", "Song", official.name)))
        }
        assertEquals(official.id, assertNotNull(tube.getArtistPage(official.copy(id = ""))).artist.id)
    }

    private var originalPause = 0L
    private var originalTrips = 0
    private val pauseField = YouTubeBackoff::class.java.getDeclaredField("pausedUntil").apply { isAccessible = true }
    private val tripsField = YouTubeBackoff::class.java.getDeclaredField("trips").apply { isAccessible = true }

    @org.junit.Before fun rememberCooldown() {
        originalPause = pauseField.getLong(YouTubeBackoff)
        originalTrips = tripsField.getInt(YouTubeBackoff)
    }

    @org.junit.After fun restoreCooldown() {
        pauseField.setLong(YouTubeBackoff, originalPause)
        tripsField.setInt(YouTubeBackoff, originalTrips)
    }

    @Test fun artistOpensDuringBackgroundCooldownWithoutResumingBackgroundWork() = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Example Artist"}]}}}}""".toResponseBody()).build()
        }.build()
        val artist = Artist("UCexample", "Example Artist", thumbnail = "https://example.com/portrait.jpg")
        val song = Track("exampleSong", "Example Song", artist.name)
        val tube = object : InnerTubeClient(http) {
            override suspend fun search(query: String, params: String?) =
                SearchResults(artists = listOf(artist), songs = listOf(song))
        }
        YouTubeBackoff.trip()
        // Start without a channel ID, as a Familiar Artist with only a portrait does.
        val page = assertNotNull(tube.getArtistPage(artist.copy(id = "")))
        assertEquals(listOf(song), page.topSongs)
        assertEquals(artist.id, page.artist.id)
        assertEquals(1, requests, "The explicit artist browse must reach the transport")
        assertTrue(YouTubeBackoff.active, "Opening an artist must leave background work paused")
        try {
            http.searchBody(Request.Builder().url("https://music.youtube.com/youtubei/v1/search").build(), 1000)
            fail("Ordinary background requests must still respect the cooldown")
        } catch (_: java.io.IOException) { }
        assertEquals(1, requests)
    }

    @Test fun searchOnlyRecoveryIsMarkedIncomplete() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("unavailable".toResponseBody()).build()
        }.build()
        val song = Track("recovery", "Recovery song", "Example Artist")
        val tube = object : InnerTubeClient(http) {
            override suspend fun search(query: String, params: String?) = SearchResults(songs = listOf(song))
        }
        val page = assertNotNull(tube.getArtistPage(Artist("UCexample", "Example Artist")))
        assertTrue(page.isFallback, "Search results must not be cached or shown as a complete artist page")
        assertEquals(listOf(song), page.topSongs)
    }

    @Test fun liveArtistPage() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_VERIFY_ARTIST_PAGES") == "1")
        val http = NetworkClientProvider.okHttpClient.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.peekBody(4_000_000).string()
            println("Artist request ${chain.request().url.encodedPath}: HTTP ${response.code}")
            if (chain.request().url.encodedPath.endsWith("/browse")) {
                java.io.File("build/artist-browse-response.json").writeText(body)
            }
            response
        }.build()
        com.auralis.music.data.network.YouTubeBackoff.trip()
        for (name in listOf("Tame Impala", "TV Girl", "Sidney Gish")) {
            val start = System.nanoTime()
            val page = assertNotNull(InnerTubeClient(http).getArtistPage(Artist("", name)) { preview ->
                println("$name overview ${(System.nanoTime() - start) / 1_000_000}ms: ${preview.topSongs.size} songs, ${preview.albums.size} albums")
            }, name)
            println("$name complete ${(System.nanoTime() - start) / 1_000_000}ms; fallback=${page.isFallback}")
            println("${page.artist.name}: ${page.topSongs.size} songs, ${page.albums.size} albums")
            assertTrue(page.topSongs.isNotEmpty(), name)
            assertTrue(page.albums.isNotEmpty(), name)
        }
    }
}
