package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.data.network.provider.*
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LyricsClient(
    private val amllSource: AmllLyricsSource = AmllLyricsSource(),
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
        private const val PROVIDER_TIMEOUT_MS = 2500L
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val lyricsDispatcher = Dispatchers.IO.limitedParallelism(2)
    }

    /**
     * Cascading multi-provider search prioritizing:
     * Tier 1: Word-Synced / Karaoke (AMLL TTML)
     * Tier 2: Line-Synced LRC (LRCLIB, JioSaavn, NetEase, KuGou, Musixmatch)
     * Tier 3: Plain Lyrics (Genius, YouTube Music, Plain fallbacks)
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null,
        videoId: String? = null,
        album: String? = null
    ): LyricsData? = withContext(lyricsDispatcher) {
        val cleanTitle = TitleCleaner.cleanTitle(title)
        val cleanArtist = TitleCleaner.cleanArtist(artist)
        val query = LyricsSearchQuery(
            title = cleanTitle,
            artist = cleanArtist,
            durationSec = durationSec,
            videoId = videoId,
            album = album
        )

        val t0 = System.currentTimeMillis()
        Log.d(TAG, "Starting lyrics search for: '$cleanTitle' by '$cleanArtist' (${durationSec ?: 0}s)")
        val providerTimings = mutableListOf<String>()

        // ── TIER 1: WORD-SYNCED RICHSYNC (Karaoke) ──
        try {
            val tStart = System.currentTimeMillis()
            val amllCandidate = kotlinx.coroutines.withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                amllSource.search(query)
            }
            val tSpent = System.currentTimeMillis() - tStart
            providerTimings.add("AMLL: ${tSpent}ms")
            if (amllCandidate != null && amllCandidate.confidence >= 65 && amllCandidate.syncType == SyncType.RICHSYNC) {
                Log.d(TAG, "[TIER 1] Found RichSync lyrics via ${amllCandidate.provider} (Confidence: ${amllCandidate.confidence}%)")
                logTimingSummary(cleanTitle, cleanArtist, t0, providerTimings, amllCandidate.provider.name, true)
                return@withContext amllCandidate.lyricsData
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 AMLL search failed: ${e.message}")
        }

        // ── TIER 2: LINE-SYNCED LRC (Cascaded High-Coverage Providers) ──
        var bestLineCandidate: LyricsCandidate? = null
        var candidatePlain: LyricsCandidate? = null

        val tier2Providers: List<LyricsSource> = listOf(
            lrcLibSource,
            jioSaavnSource,  // Top-tier Indian/Bhojpuri/Bollywood/Regional provider
            netEaseSource,   // Top-tier International/K-Pop/EDM/Asian provider
            kuGouSource,
            musixmatchSource
        )

        for (source in tier2Providers) {
            try {
                val tStart = System.currentTimeMillis()
                val candidate = kotlinx.coroutines.withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                    source.search(query)
                }
                val tSpent = System.currentTimeMillis() - tStart
                providerTimings.add("${source.provider}: ${tSpent}ms")

                if (candidate != null) {
                    if (candidate.syncType == SyncType.LINE_SYNC) {
                        Log.d(TAG, "[TIER 2] Candidate found via ${candidate.provider} (Confidence: ${candidate.confidence}%)")
                        if (candidate.confidence >= 80) {
                            logTimingSummary(cleanTitle, cleanArtist, t0, providerTimings, candidate.provider.name, true)
                            return@withContext candidate.lyricsData
                        }
                        if (bestLineCandidate == null || candidate.confidence > bestLineCandidate.confidence) {
                            bestLineCandidate = candidate
                        }
                    } else if (candidate.syncType == SyncType.PLAIN && candidatePlain == null) {
                        candidatePlain = candidate
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Provider ${source.provider} search failed: ${e.message}")
            }
        }

        if (bestLineCandidate != null && bestLineCandidate.confidence >= 55) {
            Log.d(TAG, "[TIER 2 Selected] Using line-synced lyrics from ${bestLineCandidate.provider} (Confidence: ${bestLineCandidate.confidence}%)")
            logTimingSummary(cleanTitle, cleanArtist, t0, providerTimings, bestLineCandidate.provider.name, true)
            return@withContext bestLineCandidate.lyricsData
        }

        // ── TIER 3: PLAIN LYRICS FALLBACK (Genius, YouTube Music, Musixmatch Plain, etc.) ──
        if (candidatePlain != null) {
            Log.d(TAG, "[TIER 3] Using plain lyrics from ${candidatePlain.provider} (Confidence: ${candidatePlain.confidence}%)")
            logTimingSummary(cleanTitle, cleanArtist, t0, providerTimings, candidatePlain.provider.name, false)
            return@withContext candidatePlain.lyricsData
        }

        // Try Genius & YouTube Music
        try {
            val tStart = System.currentTimeMillis()
            val geniusCandidate = kotlinx.coroutines.withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                geniusSource.search(query)
            }
            val tSpent = System.currentTimeMillis() - tStart
            providerTimings.add("Genius: ${tSpent}ms")

            if (geniusCandidate != null && geniusCandidate.lyricsData.lines.isNotEmpty()) {
                Log.d(TAG, "[TIER 3] Found plain lyrics via Genius (Confidence: ${geniusCandidate.confidence}%)")
                logTimingSummary(cleanTitle, cleanArtist, t0, providerTimings, "Genius", false)
                return@withContext geniusCandidate.lyricsData
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 3 Genius search failed: ${e.message}")
        }

        try {
            val tStart = System.currentTimeMillis()
            val ytCandidate = kotlinx.coroutines.withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                ytMusicSource.search(query)
            }
            val tSpent = System.currentTimeMillis() - tStart
            providerTimings.add("YouTube Music: ${tSpent}ms")

            if (ytCandidate != null && ytCandidate.lyricsData.lines.isNotEmpty()) {
                Log.d(TAG, "[TIER 3] Found plain lyrics via YouTube Music InnerTube")
                logTimingSummary(cleanTitle, cleanArtist, t0, providerTimings, "YouTube Music", false)
                return@withContext ytCandidate.lyricsData
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 3 YouTube search failed: ${e.message}")
        }

        Log.d(TAG, "No lyrics found across any tier for '$cleanTitle' by '$cleanArtist'")
        logTimingSummary(cleanTitle, cleanArtist, t0, providerTimings, "None", false)
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
