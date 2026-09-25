package com.auralis.music

import com.auralis.music.data.local.dao.LyricsDao
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.*
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.MetroSpeakerLayout
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * 7.7 — speaker metadata as a lyrics-selection quality signal.
 *
 * Live probe (2026-09-25) that motivated this: for "Agar Tum Saath Ho" BetterLyrics served
 * 49 word-synced lines all tagged `v1`, while Paxsenix served the same 49 lines split
 * `v1`/`v2`. BetterLyrics was an instant winner, so the speaker-tagged copy was discarded.
 * LRC-based providers (LRCLIB, NetEase line, JioSaavn) never carry line speakers.
 */
class LyricsSpeakerSelectionTest {

    private val durationMs = 215_000L

    private fun wordLine(time: Long, agent: String?, bg: Boolean = false) = LyricLine(
        time = time,
        text = "alpha beta gamma",
        words = listOf(
            LyricWord("alpha ", time, 250L),
            LyricWord("beta ", time + 300L, 250L),
            LyricWord("gamma", time + 600L, 250L)
        ),
        endTime = time + 850L,
        isBackground = bg,
        agent = agent
    )

    private fun plainLine(time: Long, agent: String? = null) =
        LyricLine(time = time, text = "alpha beta gamma", agent = agent)

    /** 40 lines every 5 s from 12 s: realistic enough for the validators and master match. */
    private fun lyrics(provider: LyricsProvider, word: Boolean, agents: (Int) -> String?) = LyricsData(
        syncType = if (word) SyncType.RICHSYNC else SyncType.LINE_SYNC,
        lines = (0 until 40).map { i -> if (word) wordLine(12_000L + i * 5_000L, agents(i)) else plainLine(12_000L + i * 5_000L, agents(i)) },
        provider = provider,
        trackName = "Agar Tum Saath Ho",
        artistName = "Alka Yagnik & Arijit Singh",
        durationMs = durationMs
    )

    private val duet: (Int) -> String? = { if (it % 3 == 0) "v2" else "v1" }
    private val soloV1: (Int) -> String? = { "v1" }
    private val untagged: (Int) -> String? = { null }

    // ── What counts as speaker metadata ──

    @Test
    fun `speaker metadata needs two individual vocalists on lead lines`() {
        assertFalse(LyricsClient.hasSpeakerMetadata(null))
        assertFalse(LyricsClient.hasSpeakerMetadata(lyrics(LyricsProvider.LRCLIB, false, untagged)))
        assertFalse(LyricsClient.hasSpeakerMetadata(lyrics(LyricsProvider.BETTER_LYRICS, true, soloV1)))
        assertFalse(LyricsClient.hasSpeakerMetadata(lyrics(LyricsProvider.PAXSENIX, true) { if (it == 5) "v1000" else "v1" }))
        assertTrue(LyricsClient.hasSpeakerMetadata(lyrics(LyricsProvider.PAXSENIX, true, duet)))
        // An ad-lib by a second agent alone is not a duet.
        val adLibOnly = LyricsData(SyncType.RICHSYNC, listOf(wordLine(1_000, "v1"), wordLine(1_200, "v2", bg = true)), provider = LyricsProvider.PAXSENIX)
        assertFalse(LyricsClient.hasSpeakerMetadata(adLibOnly))
    }

    @Test
    fun `selection signal agrees with the MetroLyrics layout threshold`() {
        val cases = listOf(untagged, soloV1, duet, { i: Int -> if (i == 3) "v1000" else "v1" }, { i: Int -> listOf("v1", "v2", "v3")[i % 3] })
        for (agents in cases) {
            val data = lyrics(LyricsProvider.PAXSENIX, true, agents)
            assertEquals(MetroSpeakerLayout.build(data.lines) != null, LyricsClient.hasSpeakerMetadata(data))
        }
    }

    // ── Ranking (outranks) ──

    @Test
    fun `speaker-tagged word sync beats an equivalent untagged word sync regardless of arrival order`() {
        // Real scores from the live probe: BetterLyrics 193.6 (all v1), Paxsenix 187.6 (v1/v2).
        assertTrue(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 187.6, bestTier = LyricsClient.TIER_WORD, bestScore = 193.6,
            provider = LyricsProvider.PAXSENIX, bestProvider = LyricsProvider.BETTER_LYRICS,
            hasSpeakers = true, bestHasSpeakers = false
        ))
        assertFalse(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 193.6, bestTier = LyricsClient.TIER_WORD, bestScore = 187.6,
            provider = LyricsProvider.BETTER_LYRICS, bestProvider = LyricsProvider.PAXSENIX,
            hasSpeakers = false, bestHasSpeakers = true
        ))
    }

    @Test
    fun `speaker-tagged synced lyrics beat plain synced lyrics of the same tier`() {
        assertTrue(LyricsClient.outranks(
            tier = LyricsClient.TIER_LINE, score = 140.0, bestTier = LyricsClient.TIER_LINE, bestScore = 150.0,
            provider = LyricsProvider.LRCLIB, bestProvider = LyricsProvider.LRCLIB,
            hasSpeakers = true, bestHasSpeakers = false
        ))
    }

    @Test
    fun `speaker metadata never lifts a lower timing tier over a higher one`() {
        // Tagged line-sync must not replace untagged word-sync.
        assertFalse(LyricsClient.outranks(
            tier = LyricsClient.TIER_LINE, score = 190.0, bestTier = LyricsClient.TIER_WORD, bestScore = 150.0,
            provider = LyricsProvider.LRCLIB, bestProvider = LyricsProvider.MUSIXMATCH,
            hasSpeakers = true, bestHasSpeakers = false
        ))
        // Untagged word-sync still replaces tagged line-sync.
        assertTrue(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 150.0, bestTier = LyricsClient.TIER_LINE, bestScore = 190.0,
            provider = LyricsProvider.MUSIXMATCH, bestProvider = LyricsProvider.LRCLIB,
            hasSpeakers = false, bestHasSpeakers = true
        ))
    }

    @Test
    fun `speaker metadata never rescues a master mismatch or beats an exact video match`() {
        assertFalse(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 200.0, bestTier = LyricsClient.TIER_WORD, bestScore = 150.0,
            masterMatch = MasterMatchStatus.MASTER_MISMATCH, bestMasterMatch = MasterMatchStatus.EXACT_MATCH,
            provider = LyricsProvider.PAXSENIX, bestProvider = LyricsProvider.BETTER_LYRICS,
            hasSpeakers = true, bestHasSpeakers = false
        ))
        assertFalse(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 200.0, bestTier = LyricsClient.TIER_WORD, bestScore = 150.0,
            provider = LyricsProvider.PAXSENIX, bestProvider = LyricsProvider.UNISON,
            isExactVideoMatch = false, bestIsExactVideoMatch = true,
            hasSpeakers = true, bestHasSpeakers = false
        ))
    }

    @Test
    fun `a much lower scoring tagged result does not win on speakers alone`() {
        assertFalse(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 150.0, bestTier = LyricsClient.TIER_WORD, bestScore = 150.0 + LyricsClient.SPEAKER_SCORE_TOLERANCE + 1.0,
            provider = LyricsProvider.PAXSENIX, bestProvider = LyricsProvider.BETTER_LYRICS,
            hasSpeakers = true, bestHasSpeakers = false
        ))
    }

    @Test
    fun `without speaker metadata on either side the existing ranking is unchanged`() {
        // BetterLyrics still outranks Paxsenix on provider priority, as before.
        assertTrue(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 180.0, bestTier = LyricsClient.TIER_WORD, bestScore = 190.0,
            provider = LyricsProvider.BETTER_LYRICS, bestProvider = LyricsProvider.PAXSENIX
        ))
        // Both tagged: also unchanged.
        assertTrue(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 180.0, bestTier = LyricsClient.TIER_WORD, bestScore = 190.0,
            provider = LyricsProvider.BETTER_LYRICS, bestProvider = LyricsProvider.PAXSENIX,
            hasSpeakers = true, bestHasSpeakers = true
        ))
    }

    // ── Instant winner / bounded wait ──

    @Test
    fun `untagged top result is not an instant winner only while a speaker source is still running`() {
        fun instant(hasSpeakers: Boolean, pending: Boolean) = LyricsClient.isInstantWinner(
            tier = LyricsClient.TIER_WORD, score = 190.0, provider = LyricsProvider.BETTER_LYRICS,
            hasSpeakers = hasSpeakers, speakerSourcePending = pending
        )
        assertFalse(instant(hasSpeakers = false, pending = true))
        assertTrue(instant(hasSpeakers = false, pending = false)) // existing behaviour
        assertTrue(instant(hasSpeakers = true, pending = true))
    }

    @Test
    fun `settle deadline keeps the existing rules when speakers are present or nothing is pending`() {
        val t0 = 1_000_000L
        val now = t0 + 800L
        for ((hasSpeakers, pending) in listOf(true to true, true to false, false to false)) {
            assertEquals(now, LyricsClient.wordSettleDeadline(Long.MAX_VALUE, t0, now, LyricsProvider.BETTER_LYRICS, false, 0L, true, hasSpeakers, pending))
            assertEquals(t0 + 2_200L, LyricsClient.wordSettleDeadline(Long.MAX_VALUE, t0, now, LyricsProvider.NETEASE, false, 0L, true, hasSpeakers, pending))
            assertEquals(now, LyricsClient.wordSettleDeadline(Long.MAX_VALUE, t0, now, LyricsProvider.UNISON, true, 0L, true, hasSpeakers, pending))
            assertEquals(t0 + 5_500L, LyricsClient.wordSettleDeadline(Long.MAX_VALUE, t0, now, LyricsProvider.NETEASE, false, 60_000L, true, hasSpeakers, pending))
        }
    }

    @Test
    fun `speaker enrichment wait is bounded and never indefinite`() {
        val t0 = 1_000_000L
        // Untagged BetterLyrics at 0.8 s while Paxsenix runs: waits at most 1 s more.
        assertEquals(t0 + 1_800L, LyricsClient.wordSettleDeadline(Long.MAX_VALUE, t0, t0 + 800L, LyricsProvider.BETTER_LYRICS, false, 0L, true, false, true))
        // Late arrival: capped by the race-wide word-provider budget.
        assertEquals(t0 + 5_500L, LyricsClient.wordSettleDeadline(Long.MAX_VALUE, t0, t0 + 5_000L, LyricsProvider.BETTER_LYRICS, false, 0L, true, false, true))
        // Past the budget: settles at once.
        val lateNow = t0 + 6_000L
        assertEquals(lateNow, LyricsClient.wordSettleDeadline(Long.MAX_VALUE, t0, lateNow, LyricsProvider.BETTER_LYRICS, false, 0L, true, false, true))
        // Exact video match never waits for speakers.
        assertEquals(t0 + 800L, LyricsClient.wordSettleDeadline(Long.MAX_VALUE, t0, t0 + 800L, LyricsProvider.UNISON, true, 0L, true, false, true))
        for (now in (t0..t0 + 10_000L step 250L)) {
            val d = LyricsClient.wordSettleDeadline(Long.MAX_VALUE, t0, now, LyricsProvider.BETTER_LYRICS, false, 0L, true, false, true)
            assertTrue(d <= maxOf(now, t0 + 5_500L))
        }
    }

    // ── Full race with mocked providers ──

    private fun raceClient(
        betterLyrics: Pair<Long, LyricsData?>,
        paxsenix: Pair<Long, LyricsData?>,
        lrcLib: Pair<Long, LyricsData?> = 30L to lyrics(LyricsProvider.LRCLIB, false, untagged)
    ): LyricsClient {
        fun <T : LyricsSource> source(mock: T, provider: LyricsProvider, answer: Pair<Long, LyricsData?>): T {
            every { mock.provider } returns provider
            coEvery { mock.search(any()) } coAnswers {
                delay(answer.first)
                answer.second?.let { LyricsCandidate(it, confidence = 95, syncType = it.syncType, provider = provider) }
            }
            return mock
        }
        val none = 10L to null
        return LyricsClient(
            amllSource = source(mockk(), LyricsProvider.AMLL, none),
            betterLyricsSource = source(mockk(), LyricsProvider.BETTER_LYRICS, betterLyrics),
            unisonSource = source(mockk(), LyricsProvider.UNISON, none),
            paxsenixSource = source(mockk(), LyricsProvider.PAXSENIX, paxsenix),
            lrcLibSource = source(mockk(), LyricsProvider.LRCLIB, lrcLib),
            jioSaavnSource = source(mockk(), LyricsProvider.JIOSAAVN, none),
            netEaseSource = source(mockk(), LyricsProvider.NETEASE, none),
            kuGouSource = source(mockk(), LyricsProvider.KUGOU, none),
            musixmatchSource = source(mockk(), LyricsProvider.MUSIXMATCH, none),
            geniusSource = source(mockk(), LyricsProvider.GENIUS, none),
            ytMusicSource = source(mockk(), LyricsProvider.YOUTUBE, none),
            youLyPlusSource = source(mockk(), LyricsProvider.YOULYPLUS, none),
            simpMusicSource = source(mockk(), LyricsProvider.SIMPMUSIC, none),
            captionsSource = io.mockk.mockk<com.auralis.music.data.network.provider.YouTubeCaptionsLyricsSource>().also { io.mockk.coEvery { it.timeFromCaptions(any(), any()) } returns null }
        )
    }

    private suspend fun LyricsClient.race() =
        getLyrics("Agar Tum Saath Ho", "Alka Yagnik & Arijit Singh", durationMs / 1000, null, "Tamasha", null, durationMs)

    @Test
    fun `race prefers the speaker-tagged copy that arrives shortly after an untagged instant winner`() = runBlocking {
        val client = raceClient(
            betterLyrics = 100L to lyrics(LyricsProvider.BETTER_LYRICS, true, soloV1),
            paxsenix = 400L to lyrics(LyricsProvider.PAXSENIX, true, duet)
        )
        val winner = client.race()!!
        assertEquals(LyricsProvider.PAXSENIX, winner.provider)
        assertTrue(LyricsClient.hasSpeakerMetadata(winner))
    }

    @Test
    fun `an untagged instant-eligible copy arriving after a tagged best does not replace it`() = runBlocking {
        val client = raceClient(
            betterLyrics = 300L to lyrics(LyricsProvider.BETTER_LYRICS, true, soloV1),
            paxsenix = 100L to lyrics(LyricsProvider.PAXSENIX, true, duet)
        )
        val winner = client.race()!!
        assertEquals(LyricsProvider.PAXSENIX, winner.provider)
        assertTrue(LyricsClient.hasSpeakerMetadata(winner))
    }

    @Test
    fun `race keeps the existing winner when no source has speaker metadata`() = runBlocking {
        val client = raceClient(
            betterLyrics = 100L to lyrics(LyricsProvider.BETTER_LYRICS, true, untagged),
            paxsenix = 300L to lyrics(LyricsProvider.PAXSENIX, true, untagged)
        )
        assertEquals(LyricsProvider.BETTER_LYRICS, client.race()!!.provider)
    }

    @Test
    fun `race keeps plain synced lyrics when no rich source is available`() = runBlocking {
        val client = raceClient(betterLyrics = 50L to null, paxsenix = 80L to null)
        val winner = client.race()!!
        assertEquals(LyricsProvider.LRCLIB, winner.provider)
        assertFalse(LyricsClient.hasSpeakerMetadata(winner))
    }

    @Test
    fun `race does not wait indefinitely for a hanging speaker-capable provider`() = runBlocking {
        val client = raceClient(
            betterLyrics = 100L to lyrics(LyricsProvider.BETTER_LYRICS, true, untagged),
            paxsenix = 30_000L to lyrics(LyricsProvider.PAXSENIX, true, duet)
        )
        val start = System.currentTimeMillis()
        val winner = client.race()!!
        val elapsed = System.currentTimeMillis() - start
        assertEquals(LyricsProvider.BETTER_LYRICS, winner.provider)
        assertTrue("settled in ${elapsed}ms", elapsed < 100L + LyricsClient.SPEAKER_ENRICHMENT_GRACE_MS + 1_000L)
    }

    @Test
    fun `speaker-tagged line sync does not displace untagged word sync in the race`() = runBlocking {
        val client = raceClient(
            betterLyrics = 150L to lyrics(LyricsProvider.BETTER_LYRICS, true, untagged),
            paxsenix = 60L to null,
            lrcLib = 30L to lyrics(LyricsProvider.LRCLIB, false, duet)
        )
        assertEquals(LyricsProvider.BETTER_LYRICS, client.race()!!.provider)
    }

    // ── Cache enrichment ──

    private val trackKey = "agar_video"

    private fun repo(network: LyricsData?, dao: LyricsDao = mockk(relaxed = true)): Pair<LyricsRepositoryImpl, LyricsDao> {
        val client = mockk<LyricsClient>()
        coEvery { client.getLyrics(any(), any(), any(), any(), any(), any(), any(), any()) } returns network
        return LyricsRepositoryImpl(lyricsClient = client, lyricsDao = dao) to dao
    }

    private suspend fun LyricsRepositoryImpl.enrich() =
        enrichSpeakerMetadata("Agar Tum Saath Ho", "Alka Yagnik & Arijit Singh", durationMs / 1000, "agar_video", "Tamasha", null, durationMs)

    @Test
    fun `cache enrichment stores a speaker-tagged word-sync result`() = runBlocking {
        val (repository, dao) = repo(lyrics(LyricsProvider.PAXSENIX, true, duet))
        val enriched = repository.enrich()
        assertNotNull(enriched)
        assertTrue(LyricsClient.hasSpeakerMetadata(enriched))
        val saved = slot<com.auralis.music.data.local.entity.LyricsEntity>()
        coVerify(exactly = 1) { dao.insertLyrics(capture(saved)) }
        assertEquals(trackKey, saved.captured.trackId)
        val restored = LyricsRepositoryImpl.entityToDomain(saved.captured, "Agar Tum Saath Ho", "Alka Yagnik & Arijit Singh")!!
        assertTrue(LyricsClient.hasSpeakerMetadata(restored))
    }

    @Test
    fun `cache enrichment leaves the cache untouched when the network has no speakers`() = runBlocking {
        val (repository, dao) = repo(lyrics(LyricsProvider.NETEASE, true, untagged))
        assertNull(repository.enrich())
        coVerify(exactly = 0) { dao.insertLyrics(any()) }
    }

    @Test
    fun `cache enrichment never downgrades word sync to speaker-tagged line sync`() = runBlocking {
        val (repository, dao) = repo(lyrics(LyricsProvider.LRCLIB, false, duet))
        assertNull(repository.enrich())
        coVerify(exactly = 0) { dao.insertLyrics(any()) }
    }

    @Test
    fun `cache enrichment rejects a speaker-tagged result for a different master`() = runBlocking {
        val otherMaster = lyrics(LyricsProvider.PAXSENIX, true, duet).copy(durationMs = durationMs + 40_000L)
        val (repository, dao) = repo(otherMaster)
        assertNull(repository.enrich())
        coVerify(exactly = 0) { dao.insertLyrics(any()) }
    }

    @Test
    fun `cache enrichment with no network result changes nothing`() = runBlocking {
        val (repository, dao) = repo(null)
        assertNull(repository.enrich())
        coVerify(exactly = 0) { dao.insertLyrics(any()) }
    }

    @Test
    fun `a cached untagged word-sync entry is still served from cache without a network call`() = runBlocking {
        val cachedData = lyrics(LyricsProvider.BETTER_LYRICS, true, untagged)
        val dao = mockk<LyricsDao>(relaxed = true)
        coEvery { dao.getLyrics(trackKey) } returns LyricsRepositoryImpl.domainToEntity(trackKey, cachedData, "Agar Tum Saath Ho", "Alka Yagnik & Arijit Singh")
        val client = mockk<LyricsClient>()
        val repository = LyricsRepositoryImpl(lyricsClient = client, lyricsDao = dao)

        val cached = repository.getCachedLyrics("Agar Tum Saath Ho", "Alka Yagnik & Arijit Singh", durationMs / 1000, "agar_video", "Tamasha", null, durationMs)

        assertNotNull(cached)
        assertEquals(LyricsProvider.BETTER_LYRICS, cached!!.provider)
        coVerify(exactly = 0) { client.getLyrics(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
