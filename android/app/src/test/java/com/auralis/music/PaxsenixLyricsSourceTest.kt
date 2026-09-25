package com.auralis.music

import com.auralis.music.data.local.dao.LyricsDao
import com.auralis.music.data.local.entity.LyricsEntity
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.*
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PaxsenixLyricsSourceTest {

    private val sampleSyllableTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <body>
            <div>
              <p begin="0:16.344" end="0:19.850">
                <span begin="0:16.344" end="0:16.550">I'm </span>
                <span begin="0:16.550" end="0:16.820">tryn-</span>
                <span begin="0:16.820" end="0:17.010">a </span>
                <span begin="0:17.010" end="0:17.200">put</span>
              </p>
              <p begin="0:20.100" end="0:23.500">
                <span begin="0:20.100" end="0:21.000">you </span>
                <span begin="0:21.000" end="0:22.000">in </span>
                <span begin="0:22.000" end="0:23.500">the </span>
              </p>
              <p begin="0:24.000" end="0:27.000">
                <span begin="0:24.000" end="0:25.000">worst </span>
                <span begin="0:25.000" end="0:27.000">mood</span>
              </p>
              <p begin="0:27.500" end="0:30.000">
                <span begin="0:27.500" end="0:28.500">P1 </span>
                <span begin="0:28.500" end="0:30.000">cleaner</span>
              </p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    private val sampleLineTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body>
            <div>
              <p begin="0:11.040" end="0:15.000">Loving machine will break your heart</p>
              <p begin="0:15.500" end="0:19.000">Tears are falling apart</p>
              <p begin="0:19.500" end="0:23.000">Running out of time</p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    private val sampleSearchJson = """
        {
          "results": {
            "songs": {
              "data": [
                {
                  "id": "1440870375",
                  "type": "songs",
                  "attributes": {
                    "name": "Starboy (feat. Daft Punk)",
                    "artistName": "The Weeknd",
                    "albumName": "Starboy",
                    "durationInMillis": 230461,
                    "isrc": "USUG11600977"
                  }
                }
              ]
            }
          }
        }
    """.trimIndent()

    // ── A. Apple Music search returns correct track ──

    @Test
    fun `test A - Apple Music search returns correct track`(): Unit = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("/catalog/us/search")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(sampleSearchJson.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply {
            setCachedToken("mock_token_abc")
        }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        val track = source.searchAppleMusicTrack(
            cleanTitle = "Starboy",
            cleanArtist = "The Weeknd",
            targetDurationMs = 230000L,
            album = "Starboy"
        )

        assertNotNull("Search must return matching track", track)
        assertEquals("1440870375", track?.id)
        assertEquals("Starboy (feat. Daft Punk)", track?.name)
        assertEquals(230461L, track?.durationInMillis)
    }

    // ── B. Paxsenix Syllable response parses into genuine timed LyricWords ──

    @Test
    fun `test B - Paxsenix Syllable response parses into genuine timed LyricWords`(): Unit = runBlocking {
        val paxsenixJson = """
            {
              "type": "Syllable",
              "ttmlContent": "${sampleSyllableTtml.replace("\n", "").replace("\"", "\\\"")}"
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = if (url.contains("/catalog/us/search")) sampleSearchJson else paxsenixJson
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        val cand = source.search(
            LyricsSearchQuery(title = "Starboy", artist = "The Weeknd", durationSec = 230L)
        )

        assertNotNull("Candidate should be found", cand)
        assertEquals(SyncType.RICHSYNC, cand?.syncType)
        assertEquals(LyricsProvider.PAXSENIX, cand?.provider)
        assertFalse("Paxsenix must NOT be marked exact video", cand?.isExactVideoMatch ?: true)
        assertNull("Paxsenix matchedVideoId must be null", cand?.matchedVideoId)

        val line = cand?.lyricsData?.lines?.firstOrNull()
        assertNotNull("Must have parsed line", line)
        val words = line?.words
        assertNotNull("Must have genuine words", words)
        assertTrue("Must have multiple words/syllables", (words?.size ?: 0) >= 3)
        words?.forEach { w ->
            assertTrue("Word start timestamp must be positive", w.time >= 16344L)
            assertNotNull("Duration must be non-null for genuine richsync", w.duration)
            assertTrue("Duration must be positive", (w.duration ?: 0L) > 0L)
        }
        Unit
    }

    // ── C. Paxsenix Line response remains LINE_SYNC ──

    @Test
    fun `test C - Paxsenix Line response remains LINE_SYNC with null words`(): Unit = runBlocking {
        val paxsenixLineJson = """
            {
              "type": "Line",
              "ttmlContent": "${sampleLineTtml.replace("\n", "").replace("\"", "\\\"")}"
            }
        """.trimIndent()

        val searchLineJson = """
            {
              "results": {
                "songs": {
                  "data": [
                    {
                      "id": "1623924754",
                      "type": "songs",
                      "attributes": {
                        "name": "Loving Machine",
                        "artistName": "TV Girl",
                        "durationInMillis": 227000
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = if (url.contains("/catalog/us/search")) searchLineJson else paxsenixLineJson
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        val cand = source.search(
            LyricsSearchQuery(title = "Loving Machine", artist = "TV Girl", durationSec = 227L)
        )

        assertNotNull("Candidate should be found", cand)
        assertEquals(SyncType.LINE_SYNC, cand?.syncType)
        assertEquals(LyricsProvider.PAXSENIX, cand?.provider)
        cand?.lyricsData?.lines?.forEach { line ->
            assertNull("Line sync must have null words to honour timing contract", line.words)
        }
        Unit
    }

    // ── D. Missing or invalid ttmlContent fails safely ──

    @Test
    fun `test D - Missing or invalid ttmlContent fails safely returning null`(): Unit = runBlocking {
        val badJson = """{"type":"Syllable","ttmlContent":""}"""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = if (url.contains("/catalog/us/search")) sampleSearchJson else badJson
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        val cand = source.search(
            LyricsSearchQuery(title = "Starboy", artist = "The Weeknd", durationSec = 230L)
        )
        assertNull("Missing ttmlContent must return null", cand)
    }

    // ── E. HTTP 401 triggers token refresh ──

    @Test
    fun `test E - HTTP 401 triggers token refresh and retry`(): Unit = runBlocking {
        val searchCallCount = AtomicInteger(0)
        val tokenPageHit = AtomicBoolean(false)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.contains("/assets/index~") -> {
                        val js = """const token = "eyJhbGciOi.freshTokenPayload.mockSig";"""
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(js.toResponseBody("application/javascript".toMediaType()))
                            .build()
                    }
                    url.contains("beta.music.apple.com") -> {
                        tokenPageHit.set(true)
                        val html = """<html><script src="/assets/index~abc123.js"></script></html>"""
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(html.toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                    url.contains("/catalog/us/search") -> {
                        val count = searchCallCount.incrementAndGet()
                        if (count == 1) {
                            // First attempt with stale token returns 401
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(401)
                                .message("Unauthorized")
                                .body("{}".toResponseBody("application/json".toMediaType()))
                                .build()
                        } else {
                            // Retry with refreshed token succeeds
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(sampleSearchJson.toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                    }
                    else -> {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("stale_token") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        val track = source.searchAppleMusicTrack(
            cleanTitle = "Starboy",
            cleanArtist = "The Weeknd",
            targetDurationMs = 230000L,
            album = null
        )

        assertEquals("Search should succeed after retry", 2, searchCallCount.get())
        assertTrue("Token scrape should have been triggered", tokenPageHit.get())
        assertNotNull("Track should be resolved after 401 recovery", track)
        assertEquals("1440870375", track?.id)
    }

    // ── F. HTTP 404/500 fails safely without breaking other providers ──

    @Test
    fun `test F - HTTP 404 or 500 fails safely returning null`(): Unit = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("Internal Error".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        val cand = source.search(
            LyricsSearchQuery(title = "Starboy", artist = "The Weeknd", durationSec = 230L)
        )
        assertNull("Server error must return null without throwing", cand)
    }

    // ── G. Response body is always closed ──

    @Test
    fun `test G - Response body is always closed`(): Unit = runBlocking {
        val bodyClosed = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = sampleSearchJson.toResponseBody("application/json".toMediaType())
                val wrappedBody = object : okhttp3.ResponseBody() {
                    override fun contentLength() = body.contentLength()
                    override fun contentType() = body.contentType()
                    override fun source() = body.source()
                    override fun close() {
                        bodyClosed.set(true)
                        body.close()
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(wrappedBody)
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        source.searchAppleMusicTrack("Starboy", "The Weeknd", 230000L, null)
        assertTrue("Search response body must be closed", bodyClosed.get())
    }

    // ── H. Correctly matched Apple Music duration is accepted ──

    @Test
    fun `test H - Correctly matched Apple Music duration is accepted`(): Unit = runBlocking {
        val paxsenixJson = """
            {
              "type": "Syllable",
              "ttmlContent": "${sampleSyllableTtml.replace("\n", "").replace("\"", "\\\"")}"
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = if (url.contains("/catalog/us/search")) sampleSearchJson else paxsenixJson
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        // Target: 230s (230,000ms), Apple Music track: 230,461ms (delta = 461ms <= 1.5s)
        val cand = source.search(
            LyricsSearchQuery(title = "Starboy", artist = "The Weeknd", durationSec = 230L)
        )
        assertNotNull("Correctly matched duration must be accepted", cand)
        assertEquals(SyncType.RICHSYNC, cand?.syncType)
    }

    // ── I. Greater than 3_5s duration mismatch is rejected ──

    @Test
    fun `test I - Greater than 3_5s duration mismatch is rejected`(): Unit = runBlocking {
        val mismatchedSearch = """
            {
              "results": {
                "songs": {
                  "data": [
                    {
                      "id": "12345",
                      "type": "songs",
                      "attributes": {
                        "name": "Test Song",
                        "artistName": "Test Artist",
                        "durationInMillis": 300000
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mismatchedSearch.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        // Target 250s (250,000ms) vs Apple Music 300s (300,000ms) -> delta 50s > 3.5s
        val cand = source.search(
            LyricsSearchQuery(title = "Test Song", artist = "Test Artist", durationSec = 250L)
        )
        assertNull("Duration mismatch > 3.5s must be rejected", cand)
    }

    // ── J. Bitter Sweet Symphony: 357s album lyrics rejected for 276s radio edit ──

    @Test
    fun `test J - Bitter Sweet Symphony 357s album lyrics rejected for 276s radio edit`(): Unit = runBlocking {
        val bssSearchJson = """
            {
              "results": {
                "songs": {
                  "data": [
                    {
                      "id": "1443258189",
                      "type": "songs",
                      "attributes": {
                        "name": "Bitter Sweet Symphony",
                        "artistName": "The Verve",
                        "albumName": "Urban Hymns",
                        "durationInMillis": 357267
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bssSearchJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        // Playback audio is 276s radio edit
        val cand = source.search(
            LyricsSearchQuery(title = "Bitter Sweet Symphony", artist = "The Verve", durationSec = 276L)
        )
        assertNull("357s album cut MUST be rejected for 276s radio edit playback", cand)
    }

    // ── K. This Is What You Came For: 221_9s Apple Music lyrics rejected for 239s master ──

    @Test
    fun `test K - This Is What You Came For 221_9s Apple Music lyrics rejected for 239s master`(): Unit = runBlocking {
        val tiwycfSearchJson = """
            {
              "results": {
                "songs": {
                  "data": [
                    {
                      "id": "1108212668",
                      "type": "songs",
                      "attributes": {
                        "name": "This Is What You Came For",
                        "artistName": "Calvin Harris & Rihanna",
                        "durationInMillis": 221900
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(tiwycfSearchJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        // Playback audio is 239s master
        val cand = source.search(
            LyricsSearchQuery(title = "This Is What You Came For", artist = "Calvin Harris ft. Rihanna", durationSec = 239L)
        )
        assertNull("221.9s single edit MUST be rejected for 239s master audio playback (delta=17.1s)", cand)
    }

    // ── L. Paxsenix RichSync beats aligned LRCLIB line-sync ──

    @Test
    fun `test L - Paxsenix RichSync beats aligned LRCLIB line-sync`() {
        val lineCandidate = LyricsCandidate(
            lyricsData = LyricsData(
                syncType = SyncType.LINE_SYNC,
                lines = listOf(
                    LyricLine(time = 1000L, text = "Line 1"),
                    LyricLine(time = 5000L, text = "Line 2")
                ),
                provider = LyricsProvider.LRCLIB,
                durationMs = 230000L
            ),
            confidence = 85,
            syncType = SyncType.LINE_SYNC,
            provider = LyricsProvider.LRCLIB
        )

        val paxsenixCandidate = LyricsCandidate(
            lyricsData = LyricsData(
                syncType = SyncType.RICHSYNC,
                lines = listOf(
                    LyricLine(
                        time = 1000L,
                        text = "Line 1",
                        words = listOf(
                            LyricWord(word = "Line ", time = 1000L, duration = 500L),
                            LyricWord(word = "1", time = 1500L, duration = 400L)
                        )
                    ),
                    LyricLine(
                        time = 5000L,
                        text = "Line 2",
                        words = listOf(
                            LyricWord(word = "Line ", time = 5000L, duration = 500L),
                            LyricWord(word = "2", time = 5500L, duration = 400L)
                        )
                    )
                ),
                provider = LyricsProvider.PAXSENIX,
                durationMs = 230461L
            ),
            confidence = 95,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.PAXSENIX
        )

        val lineTier = LyricsClient.tierOf(lineCandidate.lyricsData)
        val lineScore = LyricsClient.calculateQualityScore(lineCandidate, queryDurationSec = 230L, queryDurationMs = 230000L)

        val paxTier = LyricsClient.tierOf(paxsenixCandidate.lyricsData)
        val paxScore = LyricsClient.calculateQualityScore(paxsenixCandidate, queryDurationSec = 230L, queryDurationMs = 230000L)

        assertEquals("LRCLIB must be TIER_LINE", LyricsClient.TIER_LINE, lineTier)
        assertEquals("Paxsenix must be TIER_WORD", LyricsClient.TIER_WORD, paxTier)

        val outranks = LyricsClient.outranks(
            tier = paxTier,
            score = paxScore,
            bestTier = lineTier,
            bestScore = lineScore,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.PAXSENIX,
            bestProvider = LyricsProvider.LRCLIB,
            isExactVideoMatch = false,
            bestIsExactVideoMatch = false
        )

        assertTrue("Aligned Paxsenix RichSync must outrank aligned LRCLIB line-sync", outranks)
    }

    // ── M. Cached LRCLIB line-sync can upgrade to Paxsenix RichSync ──

    @Test
    fun `test M - Cached LRCLIB line-sync upgrades to Paxsenix RichSync`(): Unit = runBlocking {
        val paxsenixJson = """
            {
              "type": "Syllable",
              "ttmlContent": "${sampleSyllableTtml.replace("\n", "").replace("\"", "\\\"")}"
            }
        """.trimIndent()

        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = if (url.contains("/catalog/us/search")) sampleSearchJson else paxsenixJson
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(okClient).apply { setCachedToken("mock_jwt") }
        val paxsenixSource = PaxsenixLyricsSource(client = okClient, tokenManager = tokenManager)

        // Mock DAO that returns a cached LRCLIB line-sync entry
        val mockDao = object : LyricsDao {
            val stored = mutableMapOf<String, LyricsEntity>()

            override suspend fun getLyrics(trackId: String): LyricsEntity? = stored[trackId]
            override suspend fun getBestLyricsByMetadata(
                title: String,
                artist: String,
                durationMs: Long,
                pipelineVersion: Int,
                durationToleranceMs: Long
            ): LyricsEntity? = null

            override suspend fun insertLyrics(entity: LyricsEntity) {
                stored[entity.trackId] = entity
            }

            override suspend fun deleteLyrics(trackId: String) {
                stored.remove(trackId)
            }

            override suspend fun clearAllLyrics() {
                stored.clear()
            }

            override suspend fun purgeStalePipeline(version: Int) {}
            override suspend fun purgeStaleLineSync(version: Int) {}
        }

        val cachedLineEntity = LyricsEntity(
            trackId = "starboy::the weeknd::230",
            syncType = "LINE_SYNC",
            linesJson = """[{"time":1000,"text":"Line 1"},{"time":5000,"text":"Line 2"},{"time":9000,"text":"Line 3"}]""",
            plainLyrics = "Line 1\nLine 2\nLine 3",
            provider = "LRCLIB",
            trackName = "Starboy",
            artistName = "The Weeknd",
            durationMs = 230000L,
            hasWordTiming = false,
            pipelineVersion = LyricsRepositoryImpl.LYRICS_PIPELINE_VERSION
        )
        mockDao.insertLyrics(cachedLineEntity)

        val dummyClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()

        val lyricsClient = LyricsClient(
            betterLyricsSource = BetterLyricsSource(client = dummyClient),
            unisonSource = UnisonLyricsSource(client = dummyClient),
            paxsenixSource = paxsenixSource,
            lrcLibSource = LrcLibLyricsSource(client = dummyClient),
            jioSaavnSource = JioSaavnLyricsSource(client = dummyClient),
            netEaseSource = NetEaseLyricsSource(client = dummyClient),
            kuGouSource = KuGouLyricsSource(client = dummyClient),
            musixmatchSource = MusixmatchLyricsSource(client = dummyClient),
            amllSource = AmllLyricsSource(client = dummyClient),
            youLyPlusSource = com.auralis.music.data.network.provider.YouLyPlusLyricsSource(client = dummyClient),
            simpMusicSource = com.auralis.music.data.network.provider.SimpMusicLyricsSource(client = dummyClient),
            captionsSource = com.auralis.music.data.network.provider.YouTubeCaptionsLyricsSource(client = dummyClient)
        )

        val repo = LyricsRepositoryImpl(
            lyricsClient = lyricsClient,
            lyricsDao = mockDao
        )

        val result = repo.getLyrics(
            title = "Starboy",
            artist = "The Weeknd",
            durationSec = 230L,
            forceRefresh = false
        )

        assertNotNull("Repository must return lyrics", result)
        assertEquals("Cached line sync must be upgraded to RICHSYNC", SyncType.RICHSYNC, result?.syncType)
        assertEquals(LyricsProvider.PAXSENIX, result?.provider)
        assertTrue("Upgraded lyrics must contain word timing", result?.lines?.any { !it.words.isNullOrEmpty() } ?: false)
    }

    // ── N. Paxsenix must not override an aligned exact-video Unison candidate ──

    @Test
    fun `test N - Paxsenix must not override an aligned exact-video Unison candidate`() {
        val unisonExactVideo = LyricsCandidate(
            lyricsData = LyricsData(
                syncType = SyncType.RICHSYNC,
                lines = listOf(
                    LyricLine(
                        time = 500L,
                        text = "Touch me",
                        words = listOf(
                            LyricWord(word = "Touch ", time = 500L, duration = 400L),
                            LyricWord(word = "me", time = 900L, duration = 300L)
                        )
                    )
                ),
                provider = LyricsProvider.UNISON,
                isExactVideoMatch = true,
                matchedVideoId = "H5tO_9wZ0hg"
            ),
            confidence = 95,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            isExactVideoMatch = true,
            matchedVideoId = "H5tO_9wZ0hg"
        )

        val paxsenixCand = LyricsCandidate(
            lyricsData = LyricsData(
                syncType = SyncType.RICHSYNC,
                lines = listOf(
                    LyricLine(
                        time = 500L,
                        text = "Touch me",
                        words = listOf(
                            LyricWord(word = "Touch ", time = 500L, duration = 400L),
                            LyricWord(word = "me", time = 900L, duration = 300L)
                        )
                    )
                ),
                provider = LyricsProvider.PAXSENIX,
                isExactVideoMatch = false,
                matchedVideoId = null
            ),
            confidence = 95,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.PAXSENIX,
            isExactVideoMatch = false,
            matchedVideoId = null
        )

        val unisonTier = LyricsClient.tierOf(unisonExactVideo.lyricsData)
        val unisonScore = LyricsClient.calculateQualityScore(unisonExactVideo, 130L, 130000L, "Touch")

        val paxTier = LyricsClient.tierOf(paxsenixCand.lyricsData)
        val paxScore = LyricsClient.calculateQualityScore(paxsenixCand, 130L, 130000L, "Touch")

        // Does Paxsenix outrank exact-video Unison?
        val paxOutranksUnison = LyricsClient.outranks(
            tier = paxTier,
            score = paxScore,
            bestTier = unisonTier,
            bestScore = unisonScore,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.PAXSENIX,
            bestProvider = LyricsProvider.UNISON,
            isExactVideoMatch = false,
            bestIsExactVideoMatch = true // Unison is exact video!
        )

        assertFalse("Paxsenix must NEVER outrank an aligned exact-video Unison candidate", paxOutranksUnison)

        // Does exact-video Unison outrank Paxsenix?
        val unisonOutranksPax = LyricsClient.outranks(
            tier = unisonTier,
            score = unisonScore,
            bestTier = paxTier,
            bestScore = paxScore,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.UNISON,
            bestProvider = LyricsProvider.PAXSENIX,
            isExactVideoMatch = true,
            bestIsExactVideoMatch = false
        )

        assertTrue("Exact-video Unison candidate must outrank metadata-only Paxsenix candidate", unisonOutranksPax)
    }

    // ── O. No synthetic word timing is created anywhere ──

    @Test
    fun `test O - No synthetic word timing is created anywhere`(): Unit = runBlocking {
        val paxsenixLineJson = """
            {
              "type": "Line",
              "ttmlContent": "${sampleLineTtml.replace("\n", "").replace("\"", "\\\"")}"
            }
        """.trimIndent()

        val searchLineJson = """
            {
              "results": {
                "songs": {
                  "data": [
                    {
                      "id": "1492152237",
                      "type": "songs",
                      "attributes": {
                        "name": "MIDDLE OF THE NIGHT",
                        "artistName": "Elley Duhé",
                        "durationInMillis": 184000
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = if (url.contains("/catalog/us/search")) searchLineJson else paxsenixLineJson
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokenManager = AppleTokenManager(client).apply { setCachedToken("mock_jwt") }
        val source = PaxsenixLyricsSource(client = client, tokenManager = tokenManager)

        val cand = source.search(
            LyricsSearchQuery(title = "MIDDLE OF THE NIGHT", artist = "Elley Duhé", durationSec = 184L)
        )

        assertNotNull(cand)
        assertEquals("Must remain LINE_SYNC", SyncType.LINE_SYNC, cand?.syncType)
        cand?.lyricsData?.lines?.forEach { line ->
            assertNull("No words or subdivided timestamps can be manufactured on line-sync", line.words)
        }
        assertFalse("Must not have genuine word starts", WordTiming.hasGenuineWordStarts(cand?.lyricsData?.lines ?: emptyList()))
    }
}
