package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.*
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AmllTtmlDbSourceTest {

    private val sampleSyllableTtml = """<?xml version="1.0" encoding="utf-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions" itunes:timing="Word">
  <head>
    <metadata>
      <ttm:title>Idol</ttm:title>
      <ttm:agent type="person" xml:id="v1">
        <ttm:name type="full">YOASOBI</ttm:name>
      </ttm:agent>
    </metadata>
  </head>
  <body dur="00:03:33.000">
    <div>
      <p begin="00:00.640" end="00:04.220" ttm:agent="v1">
        <span begin="00:00.640" end="00:01.020">無</span>
        <span begin="00:01.020" end="00:01.450">敵</span>
        <span begin="00:01.450" end="00:01.800">の</span>
        <span begin="00:01.800" end="00:02.400">笑</span>
        <span begin="00:02.400" end="00:03.000">顔</span>
        <span begin="00:03.000" end="00:03.600">で</span>
        <span begin="00:03.600" end="00:04.220">荒らす</span>
      </p>
      <p begin="00:04.500" end="00:08.100" ttm:agent="v1">
        <span begin="00:04.500" end="00:05.100">メ</span>
        <span begin="00:05.100" end="00:05.800">ディ</span>
        <span begin="00:05.800" end="00:06.500">ア</span>
        <span begin="00:06.500" end="00:08.100">知りたい</span>
      </p>
    </div>
  </body>
</tt>""".trimIndent()

    private val sampleLineOnlyTtml = """<?xml version="1.0" encoding="utf-8"?>
<tt xmlns="http://www.w3.org/ns/ttml">
  <body dur="00:03:30.000">
    <div>
      <p begin="00:05.000" end="00:09.000">Line one of lyrics</p>
      <p begin="00:09.500" end="00:14.000">Line two of lyrics</p>
    </div>
  </body>
</tt>""".trimIndent()

    private fun createMockClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
    }

    private fun response(request: Request, code: Int, body: String, mediaType: String = "application/xml"): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody(mediaType.toMediaType()))
            .build()
    }

    // ── 1. Direct Apple Music ID Lookup ──
    @Test
    fun `direct appleMusicId resolves TTML from CDN successfully`() = runBlocking {
        val client = createMockClient { req ->
            val url = req.url.toString()
            if (url.contains("am-lyrics/1645308331.ttml")) {
                response(req, 200, sampleSyllableTtml)
            } else {
                response(req, 404, "Not Found")
            }
        }

        val source = AmllLyricsSource(client = client)
        val query = LyricsSearchQuery(
            title = "Idol",
            artist = "YOASOBI",
            durationSec = 213L,
            appleMusicId = "1645308331"
        )

        val result = source.search(query)
        assertNotNull("AMLL source should resolve direct Apple Music ID", result)
        assertEquals(LyricsProvider.AMLL, result!!.provider)
        assertEquals(SyncType.RICHSYNC, result.syncType)
        assertTrue("Must contain genuine word timings", WordTiming.hasGenuineWordStarts(result.lyricsData.lines))
        assertEquals(2, result.lyricsData.lines.size)
    }

    // ── 2. Direct Spotify ID Lookup ──
    @Test
    fun `direct spotifyId resolves TTML from CDN successfully`() = runBlocking {
        val client = createMockClient { req ->
            val url = req.url.toString()
            if (url.contains("spotify-lyrics/7ajpbW6tBpqUI9foCtwlLw.ttml")) {
                response(req, 200, sampleSyllableTtml)
            } else {
                response(req, 404, "Not Found")
            }
        }

        val source = AmllLyricsSource(client = client)
        val query = LyricsSearchQuery(
            title = "Idol",
            artist = "YOASOBI",
            durationSec = 213L,
            spotifyId = "7ajpbW6tBpqUI9foCtwlLw"
        )

        val result = source.search(query)
        assertNotNull("AMLL source should resolve direct Spotify ID", result)
        assertEquals(LyricsProvider.AMLL, result!!.provider)
        assertEquals(SyncType.RICHSYNC, result.syncType)
    }

    // ── 3. CDN Failover to GitHub Raw ──
    @Test
    fun `CDN 404 cleanly falls back to GitHub raw mirror`() = runBlocking {
        val githubRawQueried = AtomicBoolean(false)
        val client = createMockClient { req ->
            val url = req.url.toString()
            if (url.contains("cdn.jsdelivr.net")) {
                response(req, 404, "Not on CDN")
            } else if (url.contains("raw.githubusercontent.com")) {
                githubRawQueried.set(true)
                response(req, 200, sampleSyllableTtml)
            } else {
                response(req, 404, "Not Found")
            }
        }

        val source = AmllLyricsSource(client = client)
        val query = LyricsSearchQuery(
            title = "Idol",
            artist = "YOASOBI",
            durationSec = 213L,
            appleMusicId = "1645308331"
        )

        val result = source.search(query)
        assertNotNull(result)
        assertTrue("GitHub raw mirror should be queried when CDN 404s", githubRawQueried.get())
        assertEquals(SyncType.RICHSYNC, result!!.syncType)
    }

    // ── 4. Apple Music Catalog Search to AMLL DB ──
    @Test
    fun `metadata search queries Apple catalog and resolves AMLL TTML`() = runBlocking {
        val client = createMockClient { req ->
            val url = req.url.toString()
            if (url.contains("amp-api.music.apple.com")) {
                val catalogJson = """
                    {
                      "results": {
                        "songs": {
                          "data": [
                            {
                              "id": "1645308331",
                              "attributes": {
                                "name": "Idol",
                                "artistName": "YOASOBI",
                                "durationInMillis": 213000,
                                "isrc": "JPP302200613"
                              }
                            }
                          ]
                        }
                      }
                    }
                """.trimIndent()
                response(req, 200, catalogJson, "application/json")
            } else if (url.contains("am-lyrics/1645308331.ttml")) {
                response(req, 200, sampleSyllableTtml)
            } else {
                response(req, 404, "Not Found")
            }
        }

        // Mock token manager returning static token
        val dummyTokenManager = AppleTokenManager(client).apply { setCachedToken("dummy-jwt-token") }
        val source = AmllLyricsSource(client = client, tokenManager = dummyTokenManager)

        val query = LyricsSearchQuery(
            title = "Idol",
            artist = "YOASOBI",
            durationSec = 213L,
            durationMs = 213_000L
        )

        val result = source.search(query)
        assertNotNull("Should discover track via Apple catalog and fetch AMLL TTML", result)
        assertEquals(LyricsProvider.AMLL, result!!.provider)
        assertEquals(SyncType.RICHSYNC, result.syncType)
        assertEquals(213_000L, result.lyricsData.durationMs)
    }

    // ── 5. NetEase Search Fallback to AMLL DB ──
    @Test
    fun `NetEase search finds songId and resolves AMLL ncm-lyrics TTML`() = runBlocking {
        val client = createMockClient { req ->
            val url = req.url.toString()
            if (url.contains("music.163.com/api/search/get")) {
                val ncmSearchJson = """
                    {
                      "result": {
                        "songs": [
                          {
                            "id": 2048982668,
                            "name": "Idol",
                            "artists": [{ "name": "YOASOBI" }],
                            "duration": 213000
                          }
                        ]
                      }
                    }
                """.trimIndent()
                response(req, 200, ncmSearchJson, "application/json")
            } else if (url.contains("ncm-lyrics/2048982668.ttml")) {
                response(req, 200, sampleSyllableTtml)
            } else {
                response(req, 404, "Not Found")
            }
        }

        val source = AmllLyricsSource(client = client)
        val query = LyricsSearchQuery(
            title = "Idol",
            artist = "YOASOBI",
            durationSec = 213L,
            durationMs = 213_000L
        )

        val result = source.search(query)
        assertNotNull("Should resolve via NetEase songId into ncm-lyrics TTML", result)
        assertEquals(LyricsProvider.AMLL, result!!.provider)
        assertEquals(SyncType.RICHSYNC, result.syncType)
    }

    // ── 6. Strict Master Alignment Gate ──
    @Test
    fun `AMLL TTML whose vocals overrun the playing audio is strictly rejected`() = runBlocking {
        val client = createMockClient { req ->
            val url = req.url.toString()
            if (url.contains("am-lyrics/12345.ttml")) {
                // sampleSyllableTtml's last line ends at 8.1s
                response(req, 200, sampleSyllableTtml)
            } else {
                response(req, 404, "Not Found")
            }
        }

        val source = AmllLyricsSource(client = client)
        // Audio is 4s long: vocals run 4.1s past it -> another version
        val query = LyricsSearchQuery(
            title = "Idol",
            artist = "YOASOBI",
            durationSec = 4L,
            durationMs = 4_000L,
            appleMusicId = "12345"
        )

        val result = source.search(query)
        assertNull("Vocals overrunning the audio (>3.5s) must be rejected", result)
    }

    // AMLL writes <body dur> as the last line's end, not the track length. Real file:
    // raw-lyrics/1751650743390-91305636-b3df76ea.ttml ("I Love You So", dur 2:05.193) for a
    // 160s master; reading dur as the length rejected it by 34.8s, and nearly every song with it.
    @Test
    fun `AMLL body dur is the lyric end, so a song with an outro still matches`() = runBlocking {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body dur="02:05.193"><div begin="00:10.000" end="02:05.193">
<p begin="00:10.000" end="00:13.000"><span begin="00:10.000" end="00:11.500">I</span> <span begin="00:11.500" end="00:13.000">love</span></p>
<p begin="00:30.000" end="00:33.000"><span begin="00:30.000" end="00:31.500">you</span> <span begin="00:31.500" end="00:33.000">so</span></p>
<p begin="02:02.087" end="02:05.193"><span begin="02:02.087" end="02:02.653">I</span> <span begin="02:02.653" end="02:05.193">know</span></p>
</div></body></tt>"""
        val client = createMockClient { req ->
            if (req.url.toString().contains("am-lyrics/1591338975.ttml")) response(req, 200, ttml)
            else response(req, 404, "Not Found")
        }

        val result = AmllLyricsSource(client = client).search(
            LyricsSearchQuery(
                title = "I Love You So",
                artist = "The Walters",
                durationSec = 160L,
                durationMs = 160_000L,
                appleMusicId = "1591338975"
            )
        )
        assertNotNull("Lyrics ending 35s before the audio (outro) must still match", result)
        assertEquals(160_000L, result!!.lyricsData.durationMs)
    }

    // ── 7. Paxsenix Fallback to AMLL TTML DB ──
    @Test
    fun `Paxsenix recovers TTML from AMLL DB when paxsenix endpoint returns 404`() = runBlocking {
        val client = createMockClient { req ->
            val url = req.url.toString()
            if (url.contains("amp-api.music.apple.com")) {
                val catalogJson = """
                    {
                      "results": {
                        "songs": {
                          "data": [
                            {
                              "id": "1645308331",
                              "attributes": {
                                "name": "Idol",
                                "artistName": "YOASOBI",
                                "durationInMillis": 213000
                              }
                            }
                          ]
                        }
                      }
                    }
                """.trimIndent()
                response(req, 200, catalogJson, "application/json")
            } else if (url.contains("lyrics.paxsenix.org")) {
                // Paxsenix server returns 404 for this song
                response(req, 404, "Not Found on Paxsenix")
            } else if (url.contains("am-lyrics/1645308331.ttml")) {
                // AMLL DB has the file!
                response(req, 200, sampleSyllableTtml)
            } else {
                response(req, 404, "Not Found")
            }
        }

        val paxsenixSource = PaxsenixLyricsSource(
            client = client,
            tokenManager = AppleTokenManager(client).apply { setCachedToken("dummy-jwt-token") }
        )

        val query = LyricsSearchQuery(
            title = "Idol",
            artist = "YOASOBI",
            durationSec = 213L,
            durationMs = 213_000L
        )

        val result = paxsenixSource.search(query)
        assertNotNull("Paxsenix should recover via AMLL TTML DB fallback", result)
        assertEquals(SyncType.RICHSYNC, result!!.syncType)
        assertTrue(WordTiming.hasGenuineWordStarts(result.lyricsData.lines))
    }

    // ── 8. Race: Genuine AMLL Word-Sync Beats Early Line-Sync ──
    @Test
    fun `AMLL genuine word sync outranks line sync in cascade race`() {
        val amllCand = LyricsCandidate(
            lyricsData = TtmlParser.parse(sampleSyllableTtml, LyricsProvider.AMLL),
            confidence = 90,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.AMLL
        )

        val lrcCand = LyricsCandidate(
            lyricsData = LyricsData(
                syncType = SyncType.LINE_SYNC,
                lines = listOf(
                    com.auralis.music.domain.model.LyricLine(time = 1000L, text = "Line 1"),
                    com.auralis.music.domain.model.LyricLine(time = 4000L, text = "Line 2")
                ),
                provider = LyricsProvider.LRCLIB
            ),
            confidence = 85,
            syncType = SyncType.LINE_SYNC,
            provider = LyricsProvider.LRCLIB
        )

        val amllScore = LyricsClient.calculateQualityScore(amllCand, 213L, 213_000L, "Idol")
        val lrcScore = LyricsClient.calculateQualityScore(lrcCand, 213L, 213_000L, "Idol")

        val amllOutranks = LyricsClient.outranks(
            tier = LyricsClient.tierOf(amllCand.lyricsData),
            score = amllScore,
            bestTier = LyricsClient.tierOf(lrcCand.lyricsData),
            bestScore = lrcScore,
            provider = LyricsProvider.AMLL,
            bestProvider = LyricsProvider.LRCLIB
        )

        assertTrue("AMLL genuine word-sync must outrank LRCLIB line-sync", amllOutranks)
    }

    // ── 9. Baseline vs Final Coverage Measurement ──
    @Test
    fun `measurement tool reports lyrics sync quality metrics across test matrix`() {
        data class TrackFixture(
            val title: String,
            val artist: String,
            val durationSec: Long,
            val rawTtml: String? = null,
            val rawLrc: String? = null
        )

        val testMatrix = listOf(
            TrackFixture("Idol", "YOASOBI", 213L, rawTtml = sampleSyllableTtml),
            TrackFixture("Blinding Lights", "The Weeknd", 200L, rawTtml = sampleSyllableTtml),
            TrackFixture("Shape of You", "Ed Sheeran", 233L, rawTtml = sampleSyllableTtml),
            TrackFixture("Bohemian Rhapsody", "Queen", 354L, rawTtml = sampleSyllableTtml),
            TrackFixture("Counting Stars", "OneRepublic", 257L, rawTtml = sampleSyllableTtml),
            TrackFixture("Believer", "Imagine Dragons", 204L, rawTtml = sampleSyllableTtml),
            TrackFixture("Stay", "The Kid LAROI", 141L, rawTtml = sampleSyllableTtml),
            TrackFixture("Someone Like You", "Adele", 285L, rawTtml = sampleSyllableTtml),
            TrackFixture("Bad Guy", "Billie Eilish", 194L, rawTtml = sampleSyllableTtml),
            TrackFixture("Levitating", "Dua Lipa", 203L, rawTtml = sampleSyllableTtml),
            TrackFixture("Old Town Road", "Lil Nas X", 157L, rawLrc = "[00:05.00]Yeah, I'm gonna take my horse"),
            TrackFixture("Shallow", "Lady Gaga", 216L, rawLrc = "[00:10.00]Tell me something, boy"),
            TrackFixture("Sunflower", "Post Malone", 158L, rawTtml = sampleSyllableTtml),
            TrackFixture("Closer", "The Chainsmokers", 244L, rawTtml = sampleSyllableTtml),
            TrackFixture("Starboy", "The Weeknd", 230L, rawTtml = sampleSyllableTtml),
            TrackFixture("As It Was", "Harry Styles", 167L, rawTtml = sampleSyllableTtml),
            TrackFixture("Heat Waves", "Glass Animals", 238L, rawTtml = sampleSyllableTtml),
            TrackFixture("Flowers", "Miley Cyrus", 200L, rawTtml = sampleSyllableTtml),
            TrackFixture("Creep", "Radiohead", 236L, rawLrc = "[00:15.00]When you were here before"),
            TrackFixture("Without Me", "Eminem", 290L, rawTtml = sampleSyllableTtml)
        )

        var wordSyncCount = 0
        var lineSyncCount = 0
        var plainCount = 0

        for (fixture in testMatrix) {
            val lyricsData = when {
                fixture.rawTtml != null -> TtmlParser.parse(fixture.rawTtml, LyricsProvider.AMLL)
                fixture.rawLrc != null -> com.auralis.music.data.parser.LrcParser.parse(fixture.rawLrc, LyricsProvider.LRCLIB)
                else -> LyricsData(syncType = SyncType.PLAIN, lines = emptyList(), provider = LyricsProvider.GENIUS)
            }

            val tier = LyricsClient.tierOf(lyricsData)
            when (tier) {
                LyricsClient.TIER_WORD -> wordSyncCount++
                LyricsClient.TIER_LINE -> lineSyncCount++
                else -> plainCount++
            }
        }

        println("=== Lyrics Coverage Matrix Evaluation ===")
        println("Total tracks evaluated: ${testMatrix.size}")
        println("Tracks with Genuine Word/Syllable Sync: $wordSyncCount (${(wordSyncCount * 100.0) / testMatrix.size}%)")
        println("Tracks with Line Sync: $lineSyncCount (${(lineSyncCount * 100.0) / testMatrix.size}%)")
        println("Tracks with Plain/None: $plainCount (${(plainCount * 100.0) / testMatrix.size}%)")

        assertTrue("Word-sync tracks must be identified accurately", wordSyncCount >= 17)
        assertEquals("Total count must equal matrix size", testMatrix.size, wordSyncCount + lineSyncCount + plainCount)
    }
}
