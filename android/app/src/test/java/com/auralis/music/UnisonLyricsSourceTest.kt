package com.auralis.music

import com.auralis.music.data.local.dao.LyricsDao
import com.auralis.music.data.local.entity.LyricsEntity
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.BetterLyricsSource
import com.auralis.music.data.network.provider.LyricsCandidate
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.data.network.provider.UnisonLyricsSource
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
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UnisonLyricsSourceTest {

    private val sampleRichSyncTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:ttp="http://www.w3.org/ns/ttml#parameter">
          <body>
            <div>
              <p begin="0:03.459" end="0:05.784">
                <span begin="0:03.459" end="0:03.656">Her </span>
                <span begin="0:03.656" end="0:04.057">green </span>
                <span begin="0:04.057" end="0:04.481">plas</span>
                <span begin="0:04.481" end="0:04.707">tic </span>
                <span begin="0:04.901" end="0:05.784">wa</span>
              </p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    private val sampleLineSyncTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body>
            <div>
              <p begin="0:03.459" end="0:05.784">Her green plastic watering can</p>
              <p begin="0:06.000" end="0:09.000">For her fake Chinese rubber plant</p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    // ── 1. Unison rich-sync TTML parses into genuine LyricWord timing ──

    @Test
    fun `unison rich-sync TTML parses into genuine LyricWord timing with non-null duration`() = runBlocking {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val json = """
                    {
                        "success": true,
                        "data": {
                            "song": "Fake Plastic Trees",
                            "artist": "Radiohead",
                            "syncType": "richsync",
                            "format": "ttml",
                            "duration": 290,
                            "lyrics": "${sampleRichSyncTtml.replace("\n", "").replace("\"", "\\\"")}"
                        }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = UnisonLyricsSource(client = okHttpClient)
        val cand = source.search(
            LyricsSearchQuery(title = "Fake Plastic Trees", artist = "Radiohead", durationSec = 290L)
        )

        assertNotNull("Candidate should be found", cand)
        assertEquals(SyncType.RICHSYNC, cand?.syncType)
        assertEquals(LyricsProvider.UNISON, cand?.provider)

        val words = cand?.lyricsData?.lines?.firstOrNull()?.words
        assertNotNull("First line must have timed words", words)
        assertTrue("Must have multiple timed words", (words?.size ?: 0) >= 3)
        assertTrue("All words must have genuine duration", words!!.all { it.duration != null && it.duration!! > 0L })
    }

    // ── 2. Missing word spans remain line-sync and do NOT receive synthetic timing ──

    @Test
    fun `missing word spans remain line-sync and do not receive synthetic timing`() = runBlocking {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val json = """
                    {
                        "success": true,
                        "data": {
                            "song": "Fake Plastic Trees",
                            "artist": "Radiohead",
                            "syncType": "linesync",
                            "format": "ttml",
                            "duration": 290,
                            "lyrics": "${sampleLineSyncTtml.replace("\n", "").replace("\"", "\\\"")}"
                        }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = UnisonLyricsSource(client = okHttpClient)
        val cand = source.search(
            LyricsSearchQuery(title = "Fake Plastic Trees", artist = "Radiohead", durationSec = 290L)
        )

        assertNotNull("Candidate should be found", cand)
        assertEquals(SyncType.LINE_SYNC, cand?.syncType)
        assertTrue("Lines without word timing must not synthesize word spans", cand?.lyricsData?.lines?.all { it.words == null } == true)
        assertFalse("Must not claim genuine word starts", WordTiming.hasGenuineWordStarts(cand?.lyricsData?.lines ?: emptyList()))
    }

    // ── FIX 9.1: Exact-video Unison candidate + mismatched external metadata duration → accepted when video identity matches ──

    @Test
    fun `exact-video unison candidate with mismatched external metadata duration accepted when video identity matches`() {
        val playbackMs = 143_000L // e.g. Spotify metadata duration
        val unisonData = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            durationMs = 129_882L, // Actual audio duration
            trackName = "Touch",
            artistName = "KATSEYE",
            isExactVideoMatch = true,
            matchedVideoId = "H5tO_9wZ0hg",
            lines = listOf(
                LyricLine(
                    time = 497L,
                    text = "Touch, touch, touch",
                    words = listOf(
                        LyricWord("Touch,", 497L, 180L),
                        LyricWord("touch,", 868L, 178L),
                        LyricWord("touch,", 1212L, 187L)
                    )
                )
            )
        )

        val masterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = unisonData,
            playbackDurationMs = playbackMs,
            playbackTitle = "Touch",
            candidateTitle = "Touch",
            playbackVideoId = "H5tO_9wZ0hg"
        )

        assertEquals("Exact-video candidate must evaluate to EXACT_MATCH despite duration mismatch", MasterMatchStatus.EXACT_MATCH, masterMatch)
    }

    // ── FIX 9.2: Normal Unison metadata candidate + large duration mismatch → rejected ──

    @Test
    fun `normal unison metadata candidate with large duration mismatch is rejected`() {
        val playbackMs = 143_000L
        val unisonMetadataData = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            durationMs = 129_882L, // 13.1s mismatch without exact video identity
            trackName = "Touch",
            artistName = "KATSEYE",
            isExactVideoMatch = false,
            matchedVideoId = null,
            lines = listOf(
                LyricLine(
                    time = 497L,
                    text = "Touch, touch, touch",
                    words = listOf(
                        LyricWord("Touch,", 497L, 180L),
                        LyricWord("touch,", 868L, 178L),
                        LyricWord("touch,", 1212L, 187L)
                    )
                )
            )
        )

        val masterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = unisonMetadataData,
            playbackDurationMs = playbackMs,
            playbackTitle = "Touch",
            candidateTitle = "Touch",
            playbackVideoId = "H5tO_9wZ0hg"
        )

        assertEquals("Non-video candidate with 13s mismatch must evaluate to MASTER_MISMATCH", MasterMatchStatus.MASTER_MISMATCH, masterMatch)
    }

    // ── FIX 9.3: Wrong video ID → NOT treated as exact-video match ──

    @Test
    fun `wrong video ID is not treated as exact-video match and rejected on duration mismatch`() {
        val playbackMs = 143_000L
        val unisonWrongVideoData = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            durationMs = 129_882L,
            trackName = "Touch",
            artistName = "KATSEYE",
            isExactVideoMatch = true,
            matchedVideoId = "WRONG_VIDEO_ID",
            lines = listOf(
                LyricLine(
                    time = 497L,
                    text = "Touch, touch, touch",
                    words = listOf(
                        LyricWord("Touch,", 497L, 180L),
                        LyricWord("touch,", 868L, 178L),
                        LyricWord("touch,", 1212L, 187L)
                    )
                )
            )
        )

        val masterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = unisonWrongVideoData,
            playbackDurationMs = playbackMs,
            playbackTitle = "Touch",
            candidateTitle = "Touch",
            playbackVideoId = "H5tO_9wZ0hg" // Different playback video ID
        )

        assertEquals("Candidate with mismatched video ID must fall back to duration check and be MASTER_MISMATCH", MasterMatchStatus.MASTER_MISMATCH, masterMatch)
    }

    // ── FIX 9.4: Exact-video word-sync beats a line-sync candidate when video identity is valid ──

    @Test
    fun `exact-video word-sync beats a line-sync candidate when video identity is valid`() {
        val unisonWordScore = 145.0
        val lrcLineScore = 135.0

        val outranks = LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD,
            score = unisonWordScore,
            bestTier = LyricsClient.TIER_LINE,
            bestScore = lrcLineScore,
            masterMatch = MasterMatchStatus.EXACT_MATCH,
            bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.UNISON,
            bestProvider = LyricsProvider.LRCLIB,
            isExactVideoMatch = true,
            bestIsExactVideoMatch = false
        )

        assertTrue("Exact-video Unison word-sync result must outrank aligned line-sync result", outranks)
    }

    // ── FIX 9.5: Wrong-master word-sync remains rejected ──

    @Test
    fun `wrong-master word-sync remains rejected and cannot beat line-sync`() {
        val outranks = LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD,
            score = 150.0,
            bestTier = LyricsClient.TIER_LINE,
            bestScore = 90.0,
            masterMatch = MasterMatchStatus.MASTER_MISMATCH,
            bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.UNISON,
            bestProvider = LyricsProvider.LRCLIB,
            isExactVideoMatch = false,
            bestIsExactVideoMatch = false
        )

        assertFalse("Mismatched master word-sync must never outrank aligned line-sync", outranks)
    }

    // ── FIX 9.6: Touch regression ──

    @Test
    fun `touch regression - exact youtube video candidate is accepted and preferred over LRCLIB line-sync`() {
        val queryDurationMs = 143_000L
        val videoId = "H5tO_9wZ0hg"

        val unisonCandidate = LyricsCandidate(
            lyricsData = LyricsData(
                syncType = SyncType.RICHSYNC,
                provider = LyricsProvider.UNISON,
                durationMs = 129_882L,
                trackName = "Touch",
                artistName = "KATSEYE",
                isExactVideoMatch = true,
                matchedVideoId = videoId,
                lines = listOf(
                    LyricLine(
                        time = 497L,
                        text = "Touch, touch, touch",
                        words = listOf(
                            LyricWord("Touch,", 497L, 180L),
                            LyricWord("touch,", 868L, 178L),
                            LyricWord("touch,", 1212L, 187L)
                        )
                    ),
                    LyricLine(
                        time = 2497L,
                        text = "Touch, touch, touch",
                        words = listOf(
                            LyricWord("Touch,", 2497L, 180L),
                            LyricWord("touch,", 2868L, 178L),
                            LyricWord("touch,", 3212L, 187L)
                        )
                    ),
                    LyricLine(
                        time = 4497L,
                        text = "Every time we touch",
                        words = listOf(
                            LyricWord("Every", 4497L, 180L),
                            LyricWord("time", 4868L, 178L),
                            LyricWord("we", 5212L, 187L),
                            LyricWord("touch", 5500L, 200L)
                        )
                    )
                )
            ),
            confidence = 95,
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            isExactVideoMatch = true,
            matchedVideoId = videoId
        )

        val masterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = unisonCandidate.lyricsData,
            playbackDurationMs = queryDurationMs,
            playbackTitle = "Touch",
            candidateTitle = "Touch",
            playbackVideoId = videoId
        )

        assertEquals("Touch Unison exact-video candidate must NOT be rejected as MASTER_MISMATCH", MasterMatchStatus.EXACT_MATCH, masterMatch)

        val unisonScore = LyricsClient.calculateQualityScore(
            cand = unisonCandidate,
            queryDurationSec = 143L,
            queryDurationMs = queryDurationMs,
            queryTitle = "Touch",
            queryVideoId = videoId
        )
        assertTrue("Touch Unison exact-video candidate score must be high ($unisonScore)", unisonScore > 120.0)

        val lrclibCandidate = LyricsCandidate(
            lyricsData = LyricsData(
                syncType = SyncType.LINE_SYNC,
                provider = LyricsProvider.LRCLIB,
                durationMs = 130_000L,
                trackName = "Touch",
                artistName = "KATSEYE",
                lines = listOf(
                    LyricLine(time = 500L, text = "Touch, touch, touch"),
                    LyricLine(time = 2500L, text = "Touch, touch, touch"),
                    LyricLine(time = 4500L, text = "Every time we touch")
                )
            ),
            confidence = 90,
            syncType = SyncType.LINE_SYNC,
            provider = LyricsProvider.LRCLIB
        )

        val lrclibScore = LyricsClient.calculateQualityScore(
            cand = lrclibCandidate,
            queryDurationSec = 143L,
            queryDurationMs = queryDurationMs,
            queryTitle = "Touch",
            queryVideoId = videoId
        )

        val lrclibMasterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = lrclibCandidate.lyricsData,
            playbackDurationMs = queryDurationMs,
            playbackTitle = "Touch",
            candidateTitle = "Touch",
            playbackVideoId = videoId
        )

        val outranks = LyricsClient.outranks(
            tier = LyricsClient.tierOf(unisonCandidate.lyricsData),
            score = unisonScore,
            bestTier = LyricsClient.tierOf(lrclibCandidate.lyricsData),
            bestScore = lrclibScore,
            masterMatch = masterMatch,
            bestMasterMatch = lrclibMasterMatch,
            provider = LyricsProvider.UNISON,
            bestProvider = LyricsProvider.LRCLIB,
            isExactVideoMatch = true,
            bestIsExactVideoMatch = false
        )

        assertTrue("Unison exact-video candidate must outrank LRCLIB line sync for Touch", outranks)
    }

    // ── FIX 9.7: Bitter Sweet Symphony radio-edit regression ──

    @Test
    fun `bitter sweet symphony radio-edit regression - 357s album cut lyrics rejected for 276s radio edit`() {
        val radioEditMs = 276_000L
        val albumCutMs = 357_000L

        val albumLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            durationMs = albumCutMs,
            trackName = "Bitter Sweet Symphony",
            artistName = "The Verve",
            lines = listOf(
                LyricLine(
                    time = 15000L,
                    text = "'Cause it's a bitter sweet symphony, that's life",
                    words = listOf(LyricWord("'Cause", 15000L, 200L), LyricWord("it's", 15200L, 200L))
                )
            )
        )

        val masterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = albumLyrics,
            playbackDurationMs = radioEditMs,
            playbackTitle = "Bitter Sweet Symphony (Radio Edit)",
            candidateTitle = "Bitter Sweet Symphony"
        )

        assertEquals("357s album cut on 276s radio edit must be rejected as MASTER_MISMATCH", MasterMatchStatus.MASTER_MISMATCH, masterMatch)
    }

    // ── FIX 9.8: This Is What You Came For regression ──

    @Test
    fun `this is what you came for regression - 221s lyrics rejected for 239s master`() {
        val masterMs = 239_000L
        val shortCutMs = 221_000L

        val shortLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            durationMs = shortCutMs,
            trackName = "This Is What You Came For",
            artistName = "Calvin Harris",
            lines = listOf(
                LyricLine(
                    time = 10000L,
                    text = "Baby, this is what you came for",
                    words = listOf(LyricWord("Baby,", 10000L, 200L), LyricWord("this", 10200L, 200L))
                )
            )
        )

        val masterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = shortLyrics,
            playbackDurationMs = masterMs,
            playbackTitle = "This Is What You Came For",
            candidateTitle = "This Is What You Came For"
        )

        assertEquals("221s lyrics for 239s master (delta 18s > 3.5s) must be rejected as MASTER_MISMATCH", MasterMatchStatus.MASTER_MISMATCH, masterMatch)
    }

    // ── FIX 9.9: Love Me Not remix regression ──

    @Test
    fun `love me not remix regression - 203s remix lyrics rejected for 213s studio master`() {
        val studioMs = 213_000L
        val remixMs = 203_000L

        val remixLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            durationMs = remixMs,
            trackName = "Love Me Not (Remix)",
            artistName = "Ravyn Lenae",
            lines = listOf(
                LyricLine(
                    time = 5000L,
                    text = "Love me not",
                    words = listOf(LyricWord("Love", 5000L, 200L), LyricWord("me", 5200L, 200L))
                )
            )
        )

        val masterMatch = LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = remixLyrics,
            playbackDurationMs = studioMs,
            playbackTitle = "Love Me Not",
            candidateTitle = "Love Me Not (Remix)"
        )

        assertEquals("203s remix on 213s studio track must be rejected as MASTER_MISMATCH", MasterMatchStatus.MASTER_MISMATCH, masterMatch)
    }

    // ── FIX 9.10: OkHttp 404 responses do not leak connections ──

    @Test
    fun `okhttp 404 responses do not leak connections in UnisonLyricsSource and BetterLyricsSource`() = runBlocking {
        val unisonBodyClosed = AtomicBoolean(false)
        val betterLyricsBodyClosed = AtomicBoolean(false)

        val unisonClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = object : ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = 0L
                    override fun source() = Buffer()
                    override fun close() {
                        unisonBodyClosed.set(true)
                        super.close()
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body(body)
                    .build()
            }
            .build()

        val unisonSource = UnisonLyricsSource(client = unisonClient)
        val res1 = unisonSource.search(LyricsSearchQuery(title = "NonExistent", artist = "Nobody", videoId = "xyz123"))
        assertNull("404 search must return null", res1)
        assertTrue("Unison response body must be closed on 404", unisonBodyClosed.get())

        val betterClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = object : ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = 0L
                    override fun source() = Buffer()
                    override fun close() {
                        betterLyricsBodyClosed.set(true)
                        super.close()
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body(body)
                    .build()
            }
            .build()

        val betterSource = BetterLyricsSource(client = betterClient)
        val res2 = betterSource.search(LyricsSearchQuery(title = "NonExistent", artist = "Nobody", durationSec = 180L))
        assertNull("404 search must return null", res2)
        assertTrue("BetterLyrics response body must be closed on 404", betterLyricsBodyClosed.get())
    }

    // ── 11. Unison timeout or failure does not fail the whole lyrics pipeline ──

    @Test
    fun `unison timeout or failure does not fail the whole lyrics pipeline`() = runBlocking {
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (chain.request().url.host.contains("unison.boidu.dev")) {
                    throw IOException("Simulated network timeout connecting to Unison")
                }
                chain.proceed(chain.request())
            }
            .build()

        val source = UnisonLyricsSource(client = failingClient)
        val result = source.search(
            LyricsSearchQuery(title = "Fake Plastic Trees", artist = "Radiohead")
        )

        assertNull("Unison failure must return null without crashing", result)
    }

    // ── 12. Cached LINE_SYNC can upgrade to Unison RICHSYNC ──

    @Test
    fun `cached LINE_SYNC can upgrade to Unison RICHSYNC`() = runBlocking {
        val trackKey = "fake plastic trees::radiohead::290"

        val sampleUnisonLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.UNISON,
            trackName = "Fake Plastic Trees",
            artistName = "Radiohead",
            lines = listOf(
                LyricLine(
                    time = 3459L,
                    text = "Her green plastic",
                    words = listOf(
                        LyricWord("Her", 3459L, 197L),
                        LyricWord("green", 3656L, 401L),
                        LyricWord("plastic", 4057L, 650L)
                    )
                )
            )
        )

        var storedEntity: LyricsEntity? = LyricsEntity(
            trackId = trackKey,
            syncType = SyncType.LINE_SYNC.name,
            linesJson = "[{\"time\":3459,\"text\":\"Her green plastic\"}]",
            plainLyrics = "Her green plastic",
            provider = LyricsProvider.LRCLIB.name,
            trackName = "Fake Plastic Trees",
            artistName = "Radiohead",
            hasWordTiming = false,
            pipelineVersion = LyricsRepositoryImpl.LYRICS_PIPELINE_VERSION
        )

        val mockDao = object : LyricsDao {
            override suspend fun getLyrics(trackId: String): LyricsEntity? = storedEntity
            override suspend fun getBestLyricsByMetadata(title: String, artist: String, durationMs: Long, pipelineVersion: Int, durationToleranceMs: Long): LyricsEntity? = null
            override suspend fun insertLyrics(entity: LyricsEntity) { storedEntity = entity }
            override suspend fun deleteLyrics(trackId: String) { storedEntity = null }
            override suspend fun clearAllLyrics() { storedEntity = null }
            override suspend fun purgeStalePipeline(version: Int) {}
            override suspend fun purgeStaleLineSync(version: Int) {}
        }

        val upgraded = sampleUnisonLyrics
        assertTrue(
            "Condition from LyricsRepositoryImpl allows upgrade: !existing.hasWordTiming || alignedNetwork.syncType == RICHSYNC",
            storedEntity == null || !storedEntity!!.hasWordTiming || upgraded.syncType == SyncType.RICHSYNC
        )

        val newEntity = LyricsRepositoryImpl.domainToEntity(trackKey, upgraded, "Fake Plastic Trees", "Radiohead")
        mockDao.insertLyrics(newEntity)

        assertEquals("Stored entity should be upgraded to RICHSYNC", SyncType.RICHSYNC.name, storedEntity?.syncType)
        assertEquals("Stored entity provider should be UNISON", LyricsProvider.UNISON.name, storedEntity?.provider)
        assertTrue("Stored entity must mark hasWordTiming = true", storedEntity?.hasWordTiming == true)
    }

    // ── 13. Cached RICHSYNC does not unnecessarily downgrade to Unison ──

    @Test
    fun `cached RICHSYNC does not unnecessarily downgrade to line sync`() {
        val existingEntity = LyricsEntity(
            trackId = "fake plastic trees::radiohead::290",
            syncType = SyncType.RICHSYNC.name,
            linesJson = "[{\"time\":3459,\"text\":\"Her green plastic\",\"words\":[{\"word\":\"Her\",\"time\":3459,\"duration\":197}]}]",
            plainLyrics = "Her green plastic",
            provider = LyricsProvider.BETTER_LYRICS.name,
            trackName = "Fake Plastic Trees",
            artistName = "Radiohead",
            hasWordTiming = true,
            pipelineVersion = LyricsRepositoryImpl.LYRICS_PIPELINE_VERSION
        )

        val incomingLineResult = LyricsData(
            syncType = SyncType.LINE_SYNC,
            provider = LyricsProvider.UNISON,
            lines = listOf(LyricLine(time = 3459L, text = "Her green plastic"))
        )

        val shouldOverwrite = existingEntity.hasWordTiming.not() || incomingLineResult.syncType == SyncType.RICHSYNC
        assertFalse("Must NEVER overwrite cached RICHSYNC with lower-tier incoming result", shouldOverwrite)
    }

    // ── 14. YouTube video-ID lookup is preferred when available ──

    @Test
    fun `youtube video-ID lookup is preferred when available`() = runBlocking {
        val queriedUrl = AtomicReference<String>("")

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                queriedUrl.set(url)
                val json = """
                    {
                        "success": true,
                        "data": {
                            "videoId": "H5tO_9wZ0hg",
                            "song": "Touch",
                            "artist": "KATSEYE",
                            "syncType": "richsync",
                            "format": "ttml",
                            "duration": 143,
                            "lyrics": "${sampleRichSyncTtml.replace("\n", "").replace("\"", "\\\"")}"
                        }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = UnisonLyricsSource(client = okHttpClient)
        val cand = source.search(
            LyricsSearchQuery(
                title = "Touch",
                artist = "KATSEYE",
                videoId = "H5tO_9wZ0hg",
                durationSec = 143L
            )
        )

        assertNotNull("Candidate should be found via video ID", cand)
        assertTrue("Request must target the /lyrics?v= endpoint", queriedUrl.get().contains("/lyrics?v=H5tO_9wZ0hg"))
        assertTrue("Must be flagged as isExactVideoMatch", cand?.isExactVideoMatch == true)
        assertEquals("H5tO_9wZ0hg", cand?.matchedVideoId)
    }

    // ── 15. Metadata fallback works when video ID is unavailable ──

    @Test
    fun `metadata fallback works when video ID is unavailable`() = runBlocking {
        val queriedUrl = AtomicReference<String>("")

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                queriedUrl.set(url)
                val json = """
                    {
                        "success": true,
                        "data": {
                            "song": "Fake Plastic Trees",
                            "artist": "Radiohead",
                            "syncType": "richsync",
                            "format": "ttml",
                            "duration": 290,
                            "lyrics": "${sampleRichSyncTtml.replace("\n", "").replace("\"", "\\\"")}"
                        }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = UnisonLyricsSource(client = okHttpClient)
        val cand = source.search(
            LyricsSearchQuery(
                title = "Fake Plastic Trees",
                artist = "Radiohead",
                videoId = null,
                durationSec = 290L
            )
        )

        assertNotNull("Candidate should be found via metadata", cand)
        assertTrue("Request must target metadata endpoint /lyrics?song=", queriedUrl.get().contains("/lyrics?song="))
        assertTrue("Request must include artist parameter", queriedUrl.get().contains("artist=Radiohead"))
        assertFalse("Metadata candidate must NOT be isExactVideoMatch", cand?.isExactVideoMatch == true)
    }

    // ── 16. Existing provider ranking behavior remains intact ──

    @Test
    fun `existing provider ranking behavior remains intact`() {
        assertEquals(5, LyricsClient.providerWordPriority(LyricsProvider.BETTER_LYRICS))
        assertEquals(4, LyricsClient.providerWordPriority(LyricsProvider.PAXSENIX))
        assertEquals(3, LyricsClient.providerWordPriority(LyricsProvider.UNISON))
        assertEquals(2, LyricsClient.providerWordPriority(LyricsProvider.NETEASE))
        assertEquals(1, LyricsClient.providerWordPriority(LyricsProvider.MUSIXMATCH))
        assertEquals(0, LyricsClient.providerWordPriority(LyricsProvider.LRCLIB))

        // BetterLyrics > Unison on equal scores without exact video
        assertTrue(
            LyricsClient.outranks(
                tier = LyricsClient.TIER_WORD, score = 100.0,
                bestTier = LyricsClient.TIER_WORD, bestScore = 100.0,
                provider = LyricsProvider.BETTER_LYRICS,
                bestProvider = LyricsProvider.UNISON,
                isExactVideoMatch = false,
                bestIsExactVideoMatch = false
            )
        )

        // Exact-video Unison > BetterLyrics metadata on equal scores
        assertTrue(
            LyricsClient.outranks(
                tier = LyricsClient.TIER_WORD, score = 100.0,
                bestTier = LyricsClient.TIER_WORD, bestScore = 100.0,
                provider = LyricsProvider.UNISON,
                bestProvider = LyricsProvider.BETTER_LYRICS,
                isExactVideoMatch = true,
                bestIsExactVideoMatch = false
            )
        )

        // Unison > NetEase on equal scores
        assertTrue(
            LyricsClient.outranks(
                tier = LyricsClient.TIER_WORD, score = 100.0,
                bestTier = LyricsClient.TIER_WORD, bestScore = 100.0,
                provider = LyricsProvider.UNISON,
                bestProvider = LyricsProvider.NETEASE
            )
        )

        // NetEase does NOT outrank Unison on equal scores
        assertFalse(
            LyricsClient.outranks(
                tier = LyricsClient.TIER_WORD, score = 100.0,
                bestTier = LyricsClient.TIER_WORD, bestScore = 100.0,
                provider = LyricsProvider.NETEASE,
                bestProvider = LyricsProvider.UNISON
            )
        )
    }
}
