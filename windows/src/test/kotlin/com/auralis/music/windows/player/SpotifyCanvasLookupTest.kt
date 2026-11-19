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

    private fun seedDefault(folder: java.io.File): java.io.File {
        java.util.Properties().apply { setProperty(first, fileId) }.also { properties ->
            java.io.File(folder, "index.properties").outputStream().use { properties.store(it, null) }
        }
        return java.io.File(folder, "$fileId-hq.mp4").apply { writeBytes(ByteArray(120_000) { 1 }) }
    }

    @Test
    fun urlOnlyProviderWorksWithoutAnyIndexAndAfterDeletingTheVideo() = runBlocking {
        for (includeType in listOf(true, false)) {
            val folder = Files.createTempDirectory("canvas-clean-install").toFile()
            val requests = mutableListOf<String>()
            val video = ByteArray(120_000) { 3 }
            val item = JSONObject().put("url", url).put("uri", "spotify:canvas:public-canvas")
                .apply { if (includeType) put("type", "VIDEO_LOOPING") }
            val payload = JSONObject().put("data", JSONObject().put(first, item)).toString()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request().url.host
                when (chain.request().url.host) {
                    "regional.canvasstore.slnode.spicylyrics.org" -> response(chain, payload.toByteArray())
                    "canvaz.scdn.co" -> response(chain, video)
                    else -> error("A URL-only response must not need a manifest ID or account")
                }
            }.build()
            try {
                assertFalse(java.io.File(folder, "index.properties").exists())
                val client = SpotifyCanvasClient(http, folder)
                val path = assertNotNull(client.find(first), "A new laptop must discover a URL-only Canvas")
                assertContentEquals(video, java.io.File(path).readBytes())
                assertNull(client.sharp(first), "The URL hash must not be used as a manifest ID")
                assertEquals(2, requests.size)
                assertTrue(java.io.File(path).delete())
                val downloadedAgain = assertNotNull(SpotifyCanvasClient(http, folder).find(first))
                assertContentEquals(video, java.io.File(downloadedAgain).readBytes())
                assertEquals(listOf("regional.canvasstore.slnode.spicylyrics.org", "canvaz.scdn.co", "canvaz.scdn.co"), requests)
            } finally { folder.deleteRecursively() }
        }
    }

    @Test
    fun urlOnlyResponseDoesNotAllowUntrustedDownloadHosts() = runBlocking {
        val folder = Files.createTempDirectory("canvas-clean-install").toFile()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("regional.canvasstore.slnode.spicylyrics.org", chain.request().url.host)
            response(chain, JSONObject().put("data", JSONObject().put(first,
                JSONObject().put("url", "https://untrusted.example/video.cnvs.mp4").put("type", "VIDEO_LOOPING")))
                .toString().toByteArray())
        }.build()
        try {
            assertNull(SpotifyCanvasClient(http, folder).find(first))
            assertFalse(folder.listFiles().orEmpty().any { it.extension == "mp4" })
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun highestQualityCachedCanvasWinsOverDefaultEncode() = runBlocking {
        val folder = Files.createTempDirectory("canvas-quality-test").toFile()
        try {
            seedDefault(folder)
            val sharp = java.io.File(folder, "$fileId-hq.ts").apply { writeBytes(ByteArray(160_000) { 2 }) }
            val http = OkHttpClient.Builder().addInterceptor { error("Cached quality must work offline") }.build()
            val client = SpotifyCanvasClient(http, folder)
            assertEquals(sharp.absolutePath, client.find(first))
            assertEquals(sharp.absolutePath, client.sharp(first))
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun defaultCanvasUpgradesToHighestManifestProfileAndPersists() = runBlocking {
        val folder = Files.createTempDirectory("canvas-quality-test").toFile()
        val segments = java.util.Collections.synchronizedList(mutableListOf<String>())
        val manifest = """{
            "base_urls":["https://video.scdn.co"],
            "segment_template":"/{{profile_id}}/{{segment_timestamp}}.{{file_type}}",
            "contents":[{"encryption_infos":[],"start_time_millis":0,"end_time_millis":4000,"segment_length":2,
                "profiles":[
                    {"id":10,"file_type":"ts","video_width":360,"video_height":640,"video_bitrate":500000},
                    {"id":20,"file_type":"ts","video_width":1080,"video_height":1920,"video_bitrate":3000000}
                ]}]
        }"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            when (chain.request().url.host) {
                "open.spotify.com" -> response(chain, """{"accessToken":"anonymous-canvas-test-token"}""".toByteArray())
                "spclient.wg.spotify.com" -> response(chain, manifest.toByteArray())
                "video.scdn.co" -> {
                    segments += chain.request().url.encodedPath
                    response(chain, ByteArray(80_000) { 2 })
                }
                else -> error("Unexpected quality request")
            }
        }.build()
        try {
            val default = seedDefault(folder)
            val client = SpotifyCanvasClient(http, folder)
            assertEquals(default.absolutePath, client.find(first), "The default starts before upgrading")
            val sharp = assertNotNull(client.sharp(first))
            assertEquals(listOf("/20/0.ts", "/20/2.ts"), segments.sorted())
            assertContentEquals(ByteArray(160_000) { 2 }, java.io.File(sharp).readBytes())
            assertTrue(default.isFile, "Keep the playable default as a fallback")
            assertEquals(sharp, SpotifyCanvasClient(http, folder).find(first), "Restart keeps the quality upgrade")
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun failedQualityUpgradeKeepsDefaultAndBacksOff() = runBlocking {
        val folder = Files.createTempDirectory("canvas-quality-test").toFile()
        var requests = 0
        var now = 1000L
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            response(chain, byteArrayOf(), 503)
        }.build()
        try {
            val default = seedDefault(folder)
            val client = SpotifyCanvasClient(http, folder, clock = { now })
            assertNull(client.sharp(first))
            assertNull(client.sharp(first))
            assertEquals(1, requests)
            assertEquals(default.absolutePath, client.find(first))
            now += 60 * 60_000L + 1
            assertNull(client.sharp(first))
            assertEquals(2, requests)
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun rejectedQualityUpgradeFallsBackToDefaultWithoutDownloading() = runBlocking {
        val folder = Files.createTempDirectory("canvas-quality-test").toFile()
        try {
            val default = seedDefault(folder)
            val sharp = java.io.File(folder, "$fileId-hq.ts").apply { writeBytes(ByteArray(160_000) { 2 }) }
            val http = OkHttpClient.Builder().addInterceptor { error("The fallback is already cached") }.build()
            val client = SpotifyCanvasClient(http, folder)
            assertEquals(default.absolutePath, client.fallback(first, sharp.absolutePath))
            assertEquals(default.absolutePath, client.find(first))
        } finally { folder.deleteRecursively() }
    }

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
