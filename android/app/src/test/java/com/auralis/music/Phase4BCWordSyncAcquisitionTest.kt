package com.auralis.music

import com.auralis.music.data.local.dao.LyricsDao
import com.auralis.music.data.local.entity.LyricsEntity
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.BetterLyricsSource
import com.auralis.music.data.network.provider.LrcLibLyricsSource
import com.auralis.music.data.network.provider.PaxsenixLyricsSource
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class Phase4BCWordSyncAcquisitionTest {

    private val sampleTtml = """
        <?xml version="1.0" encoding="utf-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body>
            <div>
              <p begin="00:00:01.000" end="00:00:04.000">
                <span begin="00:00:01.000" end="00:00:01.500">I </span>
                <span begin="00:00:01.500" end="00:00:02.200">know </span>
                <span begin="00:00:02.200" end="00:00:03.500">that </span>
                <span begin="00:00:03.500" end="00:00:04.000">you </span>
              </p>
              <p begin="00:00:04.500" end="00:00:08.000">
                <span begin="00:00:04.500" end="00:00:05.200">I </span>
                <span begin="00:00:05.200" end="00:00:06.000">do </span>
                <span begin="00:00:06.000" end="00:00:07.000">the </span>
                <span begin="00:00:07.000" end="00:00:08.000">same </span>
              </p>
              <p begin="00:00:08.500" end="00:00:12.000">
                <span begin="00:00:08.500" end="00:00:09.500">Told </span>
                <span begin="00:00:09.500" end="00:00:10.500">you </span>
                <span begin="00:00:10.500" end="00:00:12.000">that </span>
              </p>
              <p begin="00:00:12.500" end="00:00:16.000">
                <span begin="00:00:12.500" end="00:00:13.500">I'd </span>
                <span begin="00:00:13.500" end="00:00:16.000">change </span>
              </p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    private fun sampleWordLyrics(provider: LyricsProvider = LyricsProvider.BETTER_LYRICS): LyricsData = LyricsData(
        syncType = SyncType.RICHSYNC,
        provider = provider,
        trackName = "STAY",
        artistName = "The Kid LAROI",
        lines = listOf(
            LyricLine(
                time = 1000L,
                text = "I know that you",
                words = listOf(
                    LyricWord("I", 1000L, 500L),
                    LyricWord("know", 1500L, 700L),
                    LyricWord("that", 2200L, 1300L),
                    LyricWord("you", 3500L, 500L)
                )
            ),
            LyricLine(
                time = 4500L,
                text = "I do the same",
                words = listOf(
                    LyricWord("I", 4500L, 700L),
                    LyricWord("do", 5200L, 800L),
                    LyricWord("the", 6000L, 1000L),
                    LyricWord("same", 7000L, 1000L)
                )
            ),
            LyricLine(
                time = 8500L,
                text = "Told you that",
                words = listOf(
                    LyricWord("Told", 8500L, 1000L),
                    LyricWord("you", 9500L, 1000L),
                    LyricWord("that", 10500L, 1500L)
                )
            ),
            LyricLine(
                time = 12500L,
                text = "I'd change",
                words = listOf(
                    LyricWord("I'd", 12500L, 1000L),
                    LyricWord("change", 13500L, 2500L)
                )
            )
        )
    )

    private fun sampleLineLyrics(provider: LyricsProvider = LyricsProvider.LRCLIB): LyricsData = LyricsData(
        syncType = SyncType.LINE_SYNC,
        provider = provider,
        trackName = "STAY",
        artistName = "The Kid LAROI",
        lines = listOf(
            LyricLine(time = 1000L, text = "I know that you"),
            LyricLine(time = 4500L, text = "I do the same"),
            LyricLine(time = 8500L, text = "Told you that"),
            LyricLine(time = 12500L, text = "I'd change")
        )
    )

    // ── 1. Boidu &d= 401 -> Retry without duration ──

    @Test
    fun `boidu request with d parameter returning 401 retries without d and succeeds`() = runBlocking {
        val triedWithD = AtomicBoolean(false)
        val triedWithoutD = AtomicBoolean(false)

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("&d=")) {
                    triedWithD.set(true)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("{\"error\": \"Unauthorized\"}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    triedWithoutD.set(true)
                    val json = """
                        {
                            "ttml": "${sampleTtml.replace("\n", "").replace("\"", "\\\"")}",
                            "trackName": "STAY",
                            "artistName": "The Kid LAROI",
                            "duration": 142
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
            }
            .build()

        val source = BetterLyricsSource(client = okHttpClient)
        val candidate = source.search(
            LyricsSearchQuery(
                title = "STAY",
                artist = "The Kid LAROI",
                durationSec = 142L
            )
        )

        assertTrue("Should have attempted initial query with &d=", triedWithD.get())
        assertTrue("Should have retried query without &d= upon receiving 401", triedWithoutD.get())
        assertNotNull("Candidate must not be null", candidate)
        assertEquals(SyncType.RICHSYNC, candidate?.syncType)
        assertEquals(LyricsProvider.BETTER_LYRICS, candidate?.provider)
        assertTrue(
            "Candidate must have genuine word starts",
            WordTiming.hasGenuineWordStarts(candidate?.lyricsData?.lines ?: emptyList())
        )
    }

    // ── 2. Successful no-duration Boidu response with genuine word timing ──

    @Test
    fun `boidu response without duration parses genuine word timing`() = runBlocking {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val json = """
                    {
                        "ttml": "${sampleTtml.replace("\n", "").replace("\"", "\\\"")}",
                        "trackName": "Shape of My Heart",
                        "artistName": "Sting",
                        "duration": 279
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

        val source = BetterLyricsSource(client = okHttpClient)
        val candidate = source.search(
            LyricsSearchQuery(
                title = "Shape of My Heart",
                artist = "Sting",
                durationSec = 279L
            )
        )

        assertNotNull(candidate)
        assertEquals(LyricsProvider.BETTER_LYRICS, candidate?.provider)
        assertEquals(SyncType.RICHSYNC, candidate?.syncType)
        val firstLine = candidate?.lyricsData?.lines?.firstOrNull()
        assertNotNull(firstLine)
        assertEquals(4, firstLine?.words?.size)
        assertTrue(firstLine?.words?.all { it.duration != null && it.duration!! > 0 } == true)
    }

    // ── 3. Fast LRCLIB cannot settle while BetterLyrics is still actively running ──

    @Test
    fun `fast LRCLIB cannot settle while BetterLyrics is actively running`() = runBlocking {
        val lrcClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val json = """
                    {
                        "trackName": "STAY",
                        "artistName": "The Kid LAROI",
                        "duration": 142,
                        "syncedLyrics": "[00:01.00] I know that you\n[00:04.50] I do the same\n[00:08.50] Told you that\n[00:12.50] I'd change"
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

        val blClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Thread.sleep(120) // Simulate slower active BetterLyrics network query
                val json = """
                    {
                        "ttml": "${sampleTtml.replace("\n", "").replace("\"", "\\\"")}",
                        "trackName": "STAY",
                        "artistName": "The Kid LAROI",
                        "duration": 142
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

        // Keep the other speaker-capable providers off the live network: the real Apple
        // TTML for STAY is speaker-tagged (v1/v2) and would rightly outrank this untagged
        // fixture, which is not what this test is about.
        val emptyClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val lyricsClient = LyricsClient(
            betterLyricsSource = BetterLyricsSource(client = blClient),
            lrcLibSource = LrcLibLyricsSource(client = lrcClient),
            paxsenixSource = PaxsenixLyricsSource(client = emptyClient),
            unisonSource = com.auralis.music.data.network.provider.UnisonLyricsSource(client = emptyClient),
            amllSource = com.auralis.music.data.network.provider.AmllLyricsSource(client = emptyClient),
            youLyPlusSource = com.auralis.music.data.network.provider.YouLyPlusLyricsSource(client = emptyClient),
            simpMusicSource = com.auralis.music.data.network.provider.SimpMusicLyricsSource(client = emptyClient),
            captionsSource = com.auralis.music.data.network.provider.YouTubeCaptionsLyricsSource(client = emptyClient)
        )

        val winner = lyricsClient.getLyrics("STAY", "The Kid LAROI", durationSec = 142L)
        assertNotNull("Winner should not be null", winner)
        assertEquals(
            "BetterLyrics should win despite LRCLIB answering first",
            LyricsProvider.BETTER_LYRICS,
            winner?.provider
        )
        assertEquals(SyncType.RICHSYNC, winner?.syncType)
        assertTrue(WordTiming.hasGenuineWordStarts(winner?.lines ?: emptyList()))
    }

    // ── 4. Genuine BetterLyrics word-sync beats LRCLIB line-sync ──

    @Test
    fun `genuine BetterLyrics word sync beats LRCLIB line sync`() {
        val wordCandidate = sampleWordLyrics()
        val lineCandidate = sampleLineLyrics()

        val wordTier = LyricsClient.tierOf(wordCandidate)
        val lineTier = LyricsClient.tierOf(lineCandidate)

        assertTrue(
            LyricsClient.outranks(
                tier = wordTier,
                score = 80.0,
                bestTier = lineTier,
                bestScore = 140.0,
                provider = LyricsProvider.BETTER_LYRICS,
                bestProvider = LyricsProvider.LRCLIB
            )
        )
    }

    // ── 5. Master-mismatched BetterLyrics still loses ──

    @Test
    fun `master mismatched BetterLyrics is rejected and line sync is kept`() {
        val lineCandidate = sampleLineLyrics()
        val lineTier = LyricsClient.tierOf(lineCandidate)

        // When a word candidate has MASTER_MISMATCH:
        val isMismatched = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
            lyrics = sampleWordLyrics().copy(durationMs = 300_000L), // 300s vs playback 142s = mismatch!
            playbackDurationMs = 142_000L,
            playbackTitle = "STAY",
            candidateTitle = "STAY"
        )

        assertEquals(com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH, isMismatched)

        // Outranks should reject when masterMatch is MASTER_MISMATCH
        val beats = LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD,
            score = 150.0,
            bestTier = lineTier,
            bestScore = 90.0,
            masterMatch = com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH,
            bestMasterMatch = com.auralis.music.domain.lyrics.MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.BETTER_LYRICS,
            bestProvider = LyricsProvider.LRCLIB
        )
        assertFalse("Mismatched word sync must not outrank matching line sync", beats)
    }

    // ── 6. Existing cached LINE_SYNC can upgrade to genuine word-sync ──

    @Test
    fun `cached line sync entry does not block upgrading to genuine word sync`() = runBlocking {
        val cachedEntity = LyricsEntity(
            trackId = "stay_id",
            syncType = SyncType.LINE_SYNC.name,
            linesJson = """
                [
                    {"time":1000,"text":"I know that you"},
                    {"time":4500,"text":"I do the same"},
                    {"time":8500,"text":"Told you that"},
                    {"time":12500,"text":"I'd change"}
                ]
            """.trimIndent(),
            plainLyrics = "I know that you\nI do the same\nTold you that\nI'd change",
            provider = "LRCLIB",
            trackName = "STAY",
            artistName = "The Kid LAROI",
            hasWordTiming = false,
            pipelineVersion = 1,
            durationMs = 142000L
        )

        val insertedEntity = java.util.concurrent.atomic.AtomicReference<LyricsEntity>()

        val mockDao = object : LyricsDao {
            override suspend fun getLyrics(trackId: String): LyricsEntity? = cachedEntity
            override suspend fun getBestLyricsByMetadata(title: String, artist: String, durationMs: Long, pipelineVersion: Int, durationToleranceMs: Long): LyricsEntity? = null
            override suspend fun insertLyrics(entity: LyricsEntity) {
                insertedEntity.set(entity)
            }
            override suspend fun deleteLyrics(trackId: String) {}
            override suspend fun clearAllLyrics() {}
            override suspend fun purgeStalePipeline(version: Int) {}
            override suspend fun purgeStaleLineSync(version: Int) {}
        }

        val blClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val json = """
                    {
                        "ttml": "${sampleTtml.replace("\n", "").replace("\"", "\\\"")}",
                        "trackName": "STAY",
                        "artistName": "The Kid LAROI",
                        "duration": 142
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

        val testLyricsClient = LyricsClient(
            betterLyricsSource = BetterLyricsSource(client = blClient)
        )

        val repository = LyricsRepositoryImpl(
            lyricsClient = testLyricsClient,
            lyricsDao = mockDao
        )

        // Fetch lyrics with forceRefresh = true (as used by background upgrade)
        val result = repository.getLyrics(
            title = "STAY",
            artist = "The Kid LAROI",
            durationSec = 142L,
            videoId = "stay_id",
            forceRefresh = true
        )

        assertNotNull(result)
        assertEquals(SyncType.RICHSYNC, result?.syncType)
        assertTrue(WordTiming.hasGenuineWordStarts(result?.lines ?: emptyList()))

        // Verify database was upgraded to genuine word sync
        val saved = insertedEntity.get()
        assertNotNull("Upgraded lyrics must be inserted into DB", saved)
        assertEquals("RICHSYNC", saved?.syncType)
        assertTrue("Saved entity must have hasWordTiming = true", saved?.hasWordTiming == true)
    }

    // ── 7. Existing genuine word-sync cache remains untouched ──

    @Test
    fun `purgeStaleLineSync preserves valid genuine word-sync cache entries`() = runBlocking {
        val wordSyncRow = LyricsEntity(
            trackId = "creep_id",
            syncType = SyncType.RICHSYNC.name,
            linesJson = "[{\"time\":1000,\"text\":\"I'm a creep\",\"words\":[{\"word\":\"creep\",\"time\":1000,\"duration\":500}]}]",
            plainLyrics = "I'm a creep",
            provider = "BETTER_LYRICS",
            trackName = "Creep",
            artistName = "Radiohead",
            hasWordTiming = true,
            pipelineVersion = 1,
            durationMs = 238000L
        )

        val lineSyncRow = LyricsEntity(
            trackId = "stay_old_id",
            syncType = SyncType.LINE_SYNC.name,
            linesJson = "[{\"time\":1000,\"text\":\"I know that you\"}]",
            plainLyrics = "I know that you",
            provider = "LRCLIB",
            trackName = "STAY",
            artistName = "The Kid LAROI",
            hasWordTiming = false,
            pipelineVersion = 1,
            durationMs = 142000L
        )

        val dbMap = mutableMapOf(
            "creep_id" to wordSyncRow,
            "stay_old_id" to lineSyncRow
        )

        val mockDao = object : LyricsDao {
            override suspend fun getLyrics(trackId: String): LyricsEntity? = dbMap[trackId]
            override suspend fun getBestLyricsByMetadata(title: String, artist: String, durationMs: Long, pipelineVersion: Int, durationToleranceMs: Long): LyricsEntity? = null
            override suspend fun insertLyrics(entity: LyricsEntity) { dbMap[entity.trackId] = entity }
            override suspend fun deleteLyrics(trackId: String) { dbMap.remove(trackId) }
            override suspend fun clearAllLyrics() { dbMap.clear() }
            override suspend fun purgeStalePipeline(version: Int) {}
            override suspend fun purgeStaleLineSync(version: Int) {
                // Simulates: DELETE FROM lyrics_cache WHERE hasWordTiming = 0 AND pipelineVersion < version
                val toRemove = dbMap.filter { !it.value.hasWordTiming && it.value.pipelineVersion < version }.keys
                toRemove.forEach { dbMap.remove(it) }
            }
        }

        // Run purge with new pipeline version
        mockDao.purgeStaleLineSync(LyricsRepositoryImpl.LYRICS_PIPELINE_VERSION)

        // Verify Creep (genuine word sync) is PRESERVED 100%
        assertTrue("Genuine word sync row must be preserved", dbMap.containsKey("creep_id"))
        assertEquals(true, dbMap["creep_id"]?.hasWordTiming)

        // Verify old line-sync STAY row was safely purged for upgrade
        assertFalse("Stale line-sync row from old pipeline must be purged", dbMap.containsKey("stay_old_id"))
    }

    @Test
    fun `getCachedLyrics purges and returns null for stale line-sync cache entry`() = runBlocking {
        val staleLineSyncRow = LyricsEntity(
            trackId = "sunflower_id",
            syncType = SyncType.LINE_SYNC.name,
            linesJson = "[{\"time\":3600,\"text\":\"Ayy, ayy, ayy, ayy (ooh)\"}]",
            plainLyrics = "Ayy, ayy, ayy, ayy (ooh)",
            provider = "LRCLIB",
            trackName = "Sunflower",
            artistName = "Post Malone, Swae Lee",
            hasWordTiming = false,
            pipelineVersion = 1,
            durationMs = 158000L
        )

        val dbMap = mutableMapOf("sunflower_id" to staleLineSyncRow)
        val mockDao = object : LyricsDao {
            override suspend fun getLyrics(trackId: String): LyricsEntity? = dbMap[trackId]
            override suspend fun getBestLyricsByMetadata(title: String, artist: String, durationMs: Long, pipelineVersion: Int, durationToleranceMs: Long): LyricsEntity? = null
            override suspend fun insertLyrics(entity: LyricsEntity) { dbMap[entity.trackId] = entity }
            override suspend fun deleteLyrics(trackId: String) { dbMap.remove(trackId) }
            override suspend fun clearAllLyrics() { dbMap.clear() }
            override suspend fun purgeStalePipeline(version: Int) {}
            override suspend fun purgeStaleLineSync(version: Int) {
                dbMap.filter { !it.value.hasWordTiming && it.value.pipelineVersion < version }.keys.forEach { dbMap.remove(it) }
            }
        }

        val testLyricsClient = LyricsClient()
        val repository = LyricsRepositoryImpl(
            lyricsClient = testLyricsClient,
            lyricsDao = mockDao
        )

        val cached = repository.getCachedLyrics(
            title = "Sunflower",
            artist = "Post Malone, Swae Lee",
            durationSec = 158L,
            videoId = "sunflower_id"
        )

        assertNull("Stale line-sync cache entry must return null to allow upgrade cascade", cached)
        assertFalse("Stale line-sync entry must be removed from DB", dbMap.containsKey("sunflower_id"))
    }

    @Test
    fun `getLyrics never downgrades existing genuine word-sync DB entry to line-sync`() = runBlocking {
        val existingWordSyncRow = LyricsEntity(
            trackId = "love_me_not_id",
            syncType = SyncType.RICHSYNC.name,
            linesJson = "[{\"time\":16834,\"text\":\"See, right now, I need you\",\"words\":[{\"word\":\"See, \",\"time\":16834,\"duration\":355}]}]",
            plainLyrics = "See, right now, I need you",
            provider = "BETTER_LYRICS",
            trackName = "Love Me Not",
            artistName = "Ravyn Lenae",
            hasWordTiming = true,
            pipelineVersion = LyricsRepositoryImpl.LYRICS_PIPELINE_VERSION,
            durationMs = 213000L
        )

        val dbMap = mutableMapOf("love_me_not_id" to existingWordSyncRow)
        val mockDao = object : LyricsDao {
            override suspend fun getLyrics(trackId: String): LyricsEntity? = dbMap[trackId]
            override suspend fun getBestLyricsByMetadata(title: String, artist: String, durationMs: Long, pipelineVersion: Int, durationToleranceMs: Long): LyricsEntity? = null
            override suspend fun insertLyrics(entity: LyricsEntity) { dbMap[entity.trackId] = entity }
            override suspend fun deleteLyrics(trackId: String) { dbMap.remove(trackId) }
            override suspend fun clearAllLyrics() { dbMap.clear() }
            override suspend fun purgeStalePipeline(version: Int) {}
            override suspend fun purgeStaleLineSync(version: Int) {}
        }

        // Mock a network client that returns LINE_SYNC (e.g. transient LRCLIB winner)
        val lrcLibClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val json = """
                    {
                        "id": 12345,
                        "trackName": "Love Me Not",
                        "artistName": "Ravyn Lenae",
                        "duration": 213,
                        "syncedLyrics": "[00:00.00] Plain intro\n[00:05.00] See, right now"
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

        val emptyClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val testLyricsClient = LyricsClient(
            lrcLibSource = LrcLibLyricsSource(client = lrcLibClient),
            paxsenixSource = PaxsenixLyricsSource(client = emptyClient)
        )

        val repository = LyricsRepositoryImpl(
            lyricsClient = testLyricsClient,
            lyricsDao = mockDao
        )

        // Run getLyrics with forceRefresh = true
        val result = repository.getLyrics(
            title = "Love Me Not",
            artist = "Ravyn Lenae",
            durationSec = 213L,
            videoId = "love_me_not_id",
            forceRefresh = true
        )

        // DB row must NOT have been downgraded to LRCLIB LINE_SYNC
        val inDb = dbMap["love_me_not_id"]
        assertNotNull(inDb)
        assertEquals("BETTER_LYRICS", inDb?.provider)
        assertEquals("RICHSYNC", inDb?.syncType)
        assertTrue("Genuine word-sync must remain in DB", inDb?.hasWordTiming == true)
    }
}
