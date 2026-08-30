package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.data.network.provider.*
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class LyricsClient(
    private val amllSource: AmllLyricsSource = AmllLyricsSource(),
    private val betterLyricsSource: BetterLyricsSource = BetterLyricsSource(),
    private val lrcLibSource: LrcLibLyricsSource = LrcLibLyricsSource(),
    private val jioSaavnSource: JioSaavnLyricsSource = JioSaavnLyricsSource(),
    private val netEaseSource: NetEaseLyricsSource = NetEaseLyricsSource(),
    private val kuGouSource: KuGouLyricsSource = KuGouLyricsSource(),
    private val musixmatchSource: MusixmatchLyricsSource = MusixmatchLyricsSource(),
    private val geniusSource: GeniusLyricsSource = GeniusLyricsSource(),
    private val ytMusicSource: YouTubeInnerTubeLyricsSource = YouTubeInnerTubeLyricsSource()
) {
    companion object {
        private const val TAG = "LyricsCascade"
        private const val TIMING_TAG = "AuralisLyricsTiming"
        private const val PROVIDER_TIMEOUT_MS = 1800L
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val lyricsDispatcher = Dispatchers.IO.limitedParallelism(2)
    }

    /**
     * High-speed parallel multi-provider search prioritizing:
     * Tier 1: Word-Synced / Karaoke (AMLL TTML, Better Lyrics, Musixmatch RichSync, NetEase YRC)
     * Tier 2: Line-Synced LRC in Parallel (LRCLIB, JioSaavn, KuGou)
     * Tier 3: Plain Lyrics Fallback (Genius, YouTube Music, Plain fallbacks)
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null,
        videoId: String? = null,
        album: String? = null
    ): LyricsData? = withContext(Dispatchers.IO) {
        val (splitArtist, splitTitle) = TitleCleaner.splitArtistAndTitle(title, artist)
        val cleanedTitle = TitleCleaner.cleanTitle(splitTitle)
        val coreTitle = cleanedTitle
            .replace(Regex("(?i)\\b(official\\s*(music)?\\s*video|official\\s*audio|lyric(al)?\\s*video|full\\s*song|video|audio|remastered|remaster)\\b.*"), "")
            .replace(Regex("""\s*[\(\[](?:feat\.?|ft\.?|with)\s+[^)\]]+[\)\]]""", RegexOption.IGNORE_CASE), "")
            .trim(' ', '-', '|', ':', '_')
            .ifBlank { cleanedTitle }

        val query = LyricsSearchQuery(
            title = coreTitle,
            artist = TitleCleaner.cleanArtist(splitArtist),
            durationSec = durationSec,
            videoId = videoId,
            album = album
        )

        val t0 = System.currentTimeMillis()
        Log.d(TAG, "Starting parallel synced lyrics search for: '$coreTitle' by '${query.artist}' (${durationSec ?: 0}s)")
        val providerTimings = java.util.Collections.synchronizedList(mutableListOf<String>())

        // ── TIER 1 & 2: PARALLEL HIGH-SPEED SYNCED & RICHSYNC SEARCH ──
        val syncedProviders: List<LyricsSource> = listOf(
            betterLyricsSource,
            amllSource,
            musixmatchSource,
            netEaseSource,
            lrcLibSource,
            jioSaavnSource,
            kuGouSource
        )

        val results = kotlinx.coroutines.coroutineScope {
            syncedProviders.map { source ->
                async {
                    try {
                        val tStart = System.currentTimeMillis()
                        val cand = kotlinx.coroutines.withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            source.search(query)
                        }
                        val tSpent = System.currentTimeMillis() - tStart
                        providerTimings.add("${source.provider}: ${tSpent}ms")
                        cand
                    } catch (e: Exception) {
                        Log.w(TAG, "Provider ${source.provider} search failed: ${e.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // 1. Prioritize Word-Synced (RICHSYNC) candidate first (AMLL TTML, Musixmatch RichSync, NetEase YRC)
        val richSyncCandidate = results.filter { it.syncType == SyncType.RICHSYNC && it.confidence >= 55 }
            .maxByOrNull { it.confidence }

        if (richSyncCandidate != null) {
            Log.d(TAG, "[WORD-SYNC WINNER] Using RichSync word-by-word lyrics from ${richSyncCandidate.provider} (Confidence: ${richSyncCandidate.confidence}%)")
            logTimingSummary(coreTitle, query.artist, t0, providerTimings, "${richSyncCandidate.provider.name} (Word-Synced)", true)
            return@withContext richSyncCandidate.lyricsData
        }

        // 2. High-confidence line-synced match (>= 75%)
        val topSynced = results.filter { it.syncType == SyncType.LINE_SYNC }.maxByOrNull { it.confidence }
        if (topSynced != null && topSynced.confidence >= 75) {
            Log.d(TAG, "[TIER 2 WINNER] Using synced lyrics from ${topSynced.provider} (Confidence: ${topSynced.confidence}%)")
            logTimingSummary(coreTitle, query.artist, t0, providerTimings, topSynced.provider.name, true)
            return@withContext topSynced.lyricsData
        }

        if (topSynced != null && topSynced.confidence >= 50) {
            Log.d(TAG, "[TIER 2 Acceptable] Using synced lyrics from ${topSynced.provider} (Confidence: ${topSynced.confidence}%)")
            logTimingSummary(coreTitle, query.artist, t0, providerTimings, topSynced.provider.name, true)
            return@withContext topSynced.lyricsData
        }

        // 2. Fallback check on LRCLIB with raw input title if different from cleaned coreTitle
        if (topSynced == null && title != coreTitle) {
            try {
                val fallbackQuery = LyricsSearchQuery(
                    title = TitleCleaner.cleanTitle(title),
                    artist = TitleCleaner.cleanArtist(artist),
                    durationSec = durationSec,
                    videoId = videoId,
                    album = album
                )
                val lrcFallback = lrcLibSource.search(fallbackQuery)
                if (lrcFallback != null && lrcFallback.syncType != SyncType.PLAIN && lrcFallback.confidence >= 50) {
                    Log.d(TAG, "[TIER 2 Fallback] Found synced lyrics via LRCLIB with original title: '$title'")
                    return@withContext lrcFallback.lyricsData
                }
            } catch (_: Exception) {}
        }

        // ── TIER 3: PLAIN LYRICS FALLBACK (PARALLEL SEARCH) ──
        val plainCandidate = results.firstOrNull { it.syncType == SyncType.PLAIN }
        if (plainCandidate != null) {
            Log.d(TAG, "[TIER 3] Using plain lyrics from ${plainCandidate.provider}")
            logTimingSummary(coreTitle, query.artist, t0, providerTimings, plainCandidate.provider.name, false)
            return@withContext plainCandidate.lyricsData
        }

        // Try Genius & YouTube Music concurrently with 1500ms timeout
        val plainTierResults = kotlinx.coroutines.coroutineScope {
            val geniusDeferred = async {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(1500L) { geniusSource.search(query) }
                } catch (_: Exception) { null }
            }
            val ytDeferred = async {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(1500L) { ytMusicSource.search(query) }
                } catch (_: Exception) { null }
            }
            listOfNotNull(geniusDeferred.await(), ytDeferred.await())
        }

        val plainWinner = plainTierResults.firstOrNull { it.lyricsData.lines.isNotEmpty() }
        if (plainWinner != null) {
            Log.d(TAG, "[TIER 3] Found plain lyrics via ${plainWinner.provider} (Confidence: ${plainWinner.confidence}%)")
            logTimingSummary(coreTitle, query.artist, t0, providerTimings, plainWinner.provider.name, false)
            return@withContext plainWinner.lyricsData
        }

        Log.d(TAG, "No lyrics found across any tier for '$coreTitle' by '${query.artist}'")
        logTimingSummary(coreTitle, query.artist, t0, providerTimings, "None", false)
        null
    }

    private fun logTimingSummary(
        title: String,
        artist: String,
        t0: Long,
        providers: List<String>,
        winner: String,
        isSynced: Boolean
    ) {
        val totalTime = System.currentTimeMillis() - t0
        Log.i(TIMING_TAG, """
            ==================== LYRICS TIMING ====================
            Track:          '$title' by '$artist'
            Winner:         $winner (Synced: $isSynced)
            Total Time:     ${totalTime}ms
            Cascade Steps:  ${providers.joinToString(" -> ")}
            =======================================================
        """.trimIndent())
    }
}
