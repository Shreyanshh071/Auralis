package com.auralis.music.windows.player

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.nio.file.Files
import kotlin.test.*

class SpotifyCanvasLookupTest {
    private val first = "3pY25LQJWfvvxdHhSg8ucj"
    private val second = "3OHfY25tqY28d16oZczHc8"
    private val fileId = "5ea1c72b2b0a33c5bc57d2f03f130114"
    private val url = "https://canvaz.scdn.co/upload/test/video/$fileId.cnvs.mp4"

    private fun answer(id: String, video: Boolean = true) = JSONObject().put("data", JSONObject()
        .put(id, if (video) JSONObject().put("type", "VIDEO_LOOPING_RANDOM").put("fileId", fileId).put("url", url)
        else JSONObject.NULL)).toString()

    private fun response(chain: Interceptor.Chain, body: ByteArray, code: Int = 200, retry: String? = null): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .apply { if (retry != null) header("Retry-After", retry) }.body(body.toResponseBody()).build()

    @Test
    fun shortCompressedCanvasDownloadsAndSurvivesRestartWithoutManifestOrToken() = runBlocking {
        val folder = Files.createTempDirectory("canvas-test").toFile()
        val requests = mutableListOf<String>()
        // The old 1.5 MB heuristic rejected this despite the provider declaring a video canvas.
        val mp4 = ByteArray(120_000) { 1 }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            when (chain.request().url.host) {
                "regional.canvasstore.slnode.spicylyrics.org" -> response(chain, answer(first).toByteArray())
                "canvaz.scdn.co" -> response(chain, mp4)
                else -> error("A valid small canvas must not require a Spotify manifest")
            }
        }.build()
        try {
            val path = assertNotNull(SpotifyCanvasClient(http, folder).find(first))
            assertContentEquals(mp4, java.io.File(path).readBytes())
            assertEquals(path, SpotifyCanvasClient(http, folder).find(first))
            assertEquals(2, requests.size, "The next app session should use the downloaded canvas")
            assertFalse(folder.listFiles().orEmpty().any { it.name.endsWith("partial") })
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun oneFailedTrackDoesNotDisableCanvasForTheNextTrack() = runBlocking {
        val folder = Files.createTempDirectory("canvas-test").toFile()
        val requested = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val id = chain.request().url.pathSegments.last()
            requested += id
            response(chain, if (id == first) "failure".toByteArray() else answer(second, false).toByteArray(),
                if (id == first) 500 else 200)
        }.build()
        try {
            val client = SpotifyCanvasClient(http, folder)
            assertNull(client.find(first))
            assertNull(client.find(second))
            assertEquals(listOf(first, second), requested)
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun rateLimitStillPausesAllProviderRequests() = runBlocking {
        val folder = Files.createTempDirectory("canvas-test").toFile()
        var now = 1000L
        var count = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            response(chain, byteArrayOf(), 429, "120")
        }.build()
        try {
            val client = SpotifyCanvasClient(http, folder) { now }
            assertNull(client.find(first))
            assertNull(client.find(second))
            assertEquals(1, count)
            now += 120_001
            assertNull(client.find(second))
            assertEquals(2, count)
        } finally { folder.deleteRecursively() }
    }
}
