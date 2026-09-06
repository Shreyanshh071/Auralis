package com.auralis.music.data.repository

import com.auralis.music.data.local.dao.LyricsDao
import com.auralis.music.data.local.dao.NegativeLyricsDao
import com.auralis.music.data.local.entity.LyricsEntity
import com.auralis.music.data.local.entity.NegativeLyricsEntity
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.LyricsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class LyricsRepositoryImpl(
    private val lyricsClient: LyricsClient,
    private val lyricsDao: LyricsDao? = null,
    private val negativeLyricsDao: NegativeLyricsDao? = null
) : LyricsRepository {

    private val memoryCache = ConcurrentHashMap<String, LyricsData>()

    init {
        // Purge only line-sync rows written by an older parser pipeline to allow
        // genuine word-sync upgrade, while preserving all genuine word-sync entries intact.
        if (lyricsDao != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    lyricsDao.purgeStaleLineSync(LYRICS_PIPELINE_VERSION)
                    negativeLyricsDao?.cleanExpired(System.currentTimeMillis() + 86400000L)
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val NEGATIVE_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours TTL

        /**
         * Bump when parser timing semantics change, so rows written under the old
         * meaning are dropped once instead of being trusted forever.
         *
         * 1 = the `duration == null` contract is enforced in every parser.
         * 2 = Phase 4B-C: selective purge of stale line-sync rows to allow genuine word-sync upgrade.
         * 3 = Phase 4C: concurrent BetterLyrics (Boidu/Binimum race) and inline stale line-sync purge.
         * 4 = Phase 4C final: purge stale line-sync cache entries to allow genuine word-sync upgrade.
         * 5 = Phase 4D: recovered Binimum Apple Music word-sync, subtitle matching, space preservation, and intro alignment.
         */
        const val LYRICS_PIPELINE_VERSION = 5

        internal fun domainToEntity(trackKey: String, domain: LyricsData, title: String, artist: String): LyricsEntity {
            val linesArray = JSONArray()
            for (line in domain.lines) {
                val lineObj = JSONObject()
                lineObj.put("time", line.time)
                lineObj.put("text", line.text)
                lineObj.put("isInstrumental", line.isInstrumental)
                if (line.isBackground) lineObj.put("isBackground", true)
                if (!line.words.isNullOrEmpty()) {
                    val wordsArray = JSONArray()
                    for (w in line.words) {
                        val wObj = JSONObject()
                        wObj.put("word", w.word)
                        wObj.put("time", w.time)
                        if (w.duration != null) wObj.put("duration", w.duration)
                        if (w.isBackground) wObj.put("isBackground", true)
                        wordsArray.put(wObj)
                    }
                    lineObj.put("words", wordsArray)
                }
                linesArray.put(lineObj)
            }

            return LyricsEntity(
                trackId = trackKey,
                syncType = domain.syncType.name,
                linesJson = linesArray.toString(),
                plainLyrics = domain.plainLyrics,
                provider = domain.provider.name,
                trackName = domain.trackName ?: title,
                artistName = domain.artistName ?: artist,
                hasWordTiming = com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(domain.lines),
                pipelineVersion = LYRICS_PIPELINE_VERSION,
                durationMs = domain.durationMs,
                leadingSilenceMs = domain.leadingSilenceMs
            )
        }

        internal fun entityToDomain(entity: LyricsEntity, queryTitle: String, queryArtist: String): LyricsData? {
            try {
                val candTitle = entity.trackName
                if (candTitle.isNullOrBlank()) {
                    return null
                }
                val candArtist = entity.artistName.orEmpty()

                val syncType = SyncType.valueOf(entity.syncType)
                val provider = try { LyricsProvider.valueOf(entity.provider) } catch (_: Exception) { LyricsProvider.LRCLIB }
                val lines = mutableListOf<LyricLine>()

                val linesArray = JSONArray(entity.linesJson)
                for (i in 0 until linesArray.length()) {
                    val lineObj = linesArray.getJSONObject(i)
                    val time = lineObj.getLong("time")
                    val text = lineObj.getString("text")
                    val isInst = lineObj.optBoolean("isInstrumental", false)

                    val wordsArray = lineObj.optJSONArray("words")
                    val words = if (wordsArray != null && wordsArray.length() > 0) {
                        val wList = mutableListOf<LyricWord>()
                        for (j in 0 until wordsArray.length()) {
                            val wObj = wordsArray.getJSONObject(j)
                            wList.add(
                                LyricWord(
                                    word = wObj.getString("word"),
                                    time = wObj.getLong("time"),
                                    duration = if (wObj.has("duration")) wObj.getLong("duration") else null,
                                    isBackground = wObj.optBoolean("isBackground", false)
                                )
                            )
                        }
                        wList
                    } else null

                    lines.add(
                        LyricLine(
                            time = time,
                            text = text,
                            words = words,
                            isInstrumental = isInst,
                            isBackground = lineObj.optBoolean("isBackground", false)
                        )
                    )
                }

                val cleanedLines = lines.filterNot { com.auralis.music.data.parser.LrcParser.isMetadataOrCreditLine(it.text) }
                val resolvedLines = if (cleanedLines.size >= 4 && cleanedLines.none { !it.words.isNullOrEmpty() }) {
                    com.auralis.music.data.parser.LrcParser.mergeMicroFragments(cleanedLines)
                } else {
                    cleanedLines
                }

                val resolvedSyncType = when {
                    com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(resolvedLines) -> SyncType.RICHSYNC
                    resolvedLines.any { it.time > 0L } -> SyncType.LINE_SYNC
                    resolvedLines.isNotEmpty() -> if (syncType == SyncType.RICHSYNC) SyncType.LINE_SYNC else syncType
                    else -> SyncType.PLAIN
                }

                return LyricsData(
                    syncType = resolvedSyncType,
                    lines = resolvedLines,
                    plainLyrics = entity.plainLyrics,
                    provider = provider,
                    trackName = candTitle,
                    artistName = candArtist,
                    durationMs = entity.durationMs,
                    leadingSilenceMs = entity.leadingSilenceMs
                )
            } catch (_: Exception) {
                return null
            }
        }
    }

    override suspend fun getCachedLyrics(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?,
        album: String?,
        channelTitle: String?,
        durationMs: Long?
    ): LyricsData? {
        val trackKey = (videoId?.takeIf { it.isNotBlank() } ?: "$title::$artist::${durationSec ?: 0}").lowercase()
        val playbackMs = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)

        // 1. Check in-memory cache
        memoryCache[trackKey]?.let { cached ->
            val candTitle = cached.trackName ?: title
            val candArtist = cached.artistName ?: artist
            val confidence = com.auralis.music.data.parser.LyricsMatcher.calculateConfidence(
                queryTitle = title,
                queryArtist = artist,
                candidateTitle = candTitle,
                candidateArtist = candArtist,
                queryDurationSec = durationSec,
                queryAlbum = album
            )
            val masterMatch = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                lyrics = cached,
                playbackDurationMs = playbackMs,
                playbackTitle = title,
                candidateTitle = candTitle,
                playbackChannelTitle = channelTitle
            )
            if (confidence >= 50 && masterMatch != com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH && !com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(cached)) {
                val aligned = if (playbackMs > 0L) {
                    com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(cached, playbackMs)
                } else cached
                if (aligned.syncType != SyncType.PLAIN && aligned.lines.isNotEmpty()) {
                    return aligned
                }
            } else {
                memoryCache.remove(trackKey)
            }
        }

        // 2. Check local SQLite Room DB cache (0ms instant display)
        if (lyricsDao != null) {
            try {
                val entity = lyricsDao.getLyrics(trackKey)
                if (entity != null) {
                    if (!entity.hasWordTiming && entity.pipelineVersion < LYRICS_PIPELINE_VERSION) {
                        android.util.Log.d("AuralisLyrics", "[getCachedLyrics] Purging stale line-sync cache entry for '$trackKey' (pipelineVersion=${entity.pipelineVersion} < $LYRICS_PIPELINE_VERSION)")
                        lyricsDao.deleteLyrics(trackKey)
                        memoryCache.remove(trackKey)
                        return null
                    }
                    val domainLyrics = entityToDomain(entity, title, artist)
                    if (domainLyrics != null) {
                        val candTitle = domainLyrics.trackName ?: title
                        val candArtist = domainLyrics.artistName ?: artist
                        val confidence = com.auralis.music.data.parser.LyricsMatcher.calculateConfidence(
                            queryTitle = title,
                            queryArtist = artist,
                            candidateTitle = candTitle,
                            candidateArtist = candArtist,
                            queryDurationSec = durationSec,
                            queryAlbum = album
                        )
                        val masterMatch = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                            lyrics = domainLyrics,
                            playbackDurationMs = playbackMs,
                            playbackTitle = title,
                            candidateTitle = candTitle,
                            playbackChannelTitle = channelTitle
                        )
                        if (confidence >= 50 && masterMatch != com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH && !com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(domainLyrics)) {
                            val aligned = if (playbackMs > 0L) {
                                com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(domainLyrics, playbackMs)
                            } else {
                                domainLyrics
                            }
                            if (aligned.syncType != SyncType.PLAIN && aligned.lines.isNotEmpty()) {
                                memoryCache[trackKey] = aligned
                                return aligned
                            }
                        } else {
                            lyricsDao.deleteLyrics(trackKey)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    override suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?,
        forceRefresh: Boolean,
        album: String?,
        channelTitle: String?,
        durationMs: Long?
    ): LyricsData? {
        val trackKey = (videoId?.takeIf { it.isNotBlank() } ?: "$title::$artist::${durationSec ?: 0}").lowercase()
        val playbackMs = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)
        android.util.Log.d("AuralisLyrics", "[getLyrics] Request for '$title' by '$artist' (key=$trackKey, forceRefresh=$forceRefresh, album=$album, durMs=$playbackMs)")

        // 1. Check in-memory cache
        if (!forceRefresh) {
            memoryCache[trackKey]?.let { cached ->
                val candTitle = cached.trackName ?: title
                val candArtist = cached.artistName ?: artist
                val confidence = com.auralis.music.data.parser.LyricsMatcher.calculateConfidence(
                    queryTitle = title,
                    queryArtist = artist,
                    candidateTitle = candTitle,
                    candidateArtist = candArtist,
                    queryDurationSec = durationSec,
                    queryAlbum = album
                )
                val masterMatch = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                    lyrics = cached,
                    playbackDurationMs = playbackMs,
                    playbackTitle = title,
                    candidateTitle = candTitle,
                    playbackChannelTitle = channelTitle
                )
                if (confidence >= 50 && masterMatch != com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH) {
                    val aligned = if (playbackMs > 0L) {
                        com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(cached, playbackMs)
                    } else cached
                    if (aligned.syncType != SyncType.PLAIN && aligned.lines.isNotEmpty()) {
                        if (com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(aligned.lines)) {
                            android.util.Log.d("AuralisLyrics", "[getLyrics] Memory cache HIT (Word sync, provider=${aligned.provider})")
                            return aligned
                        }
                    }
                } else {
                    memoryCache.remove(trackKey)
                }
            }
        }

        // 2. Check local SQLite Room DB cache (0ms instant display)
        var cachedLineSyncFallback: LyricsData? = null
        if (!forceRefresh && lyricsDao != null) {
            try {
                val entity = lyricsDao.getLyrics(trackKey)
                if (entity != null) {
                    if (!entity.hasWordTiming && entity.pipelineVersion < LYRICS_PIPELINE_VERSION) {
                        android.util.Log.d("AuralisLyrics", "[getLyrics] Purging stale line-sync cache entry for '$trackKey' (pipelineVersion=${entity.pipelineVersion} < $LYRICS_PIPELINE_VERSION)")
                        lyricsDao.deleteLyrics(trackKey)
                        memoryCache.remove(trackKey)
                    } else {
                        val domainLyrics = entityToDomain(entity, title, artist)
                        if (domainLyrics != null) {
                            val candTitle = domainLyrics.trackName ?: title
                            val candArtist = domainLyrics.artistName ?: artist
                            val confidence = com.auralis.music.data.parser.LyricsMatcher.calculateConfidence(
                                queryTitle = title,
                                queryArtist = artist,
                                candidateTitle = candTitle,
                                candidateArtist = candArtist,
                                queryDurationSec = durationSec,
                                queryAlbum = album
                            )
                            val masterMatch = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                                lyrics = domainLyrics,
                                playbackDurationMs = playbackMs,
                                playbackTitle = title,
                                candidateTitle = candTitle,
                                playbackChannelTitle = channelTitle
                            )
                            if (confidence >= 50 && masterMatch != com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH) {
                                val aligned = if (playbackMs > 0L) {
                                    com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(domainLyrics, playbackMs)
                                } else domainLyrics
                                if (aligned.syncType != SyncType.PLAIN && aligned.lines.isNotEmpty()) {
                                    if (entity.hasWordTiming && com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(aligned.lines)) {
                                        android.util.Log.d("AuralisLyrics", "[getLyrics] Room DB cache HIT (Word sync, provider=${aligned.provider})")
                                        memoryCache[trackKey] = aligned
                                        return aligned
                                    } else {
                                        // Line-sync cached from earlier; hold as fallback and allow cascade to seek a word-sync upgrade
                                        cachedLineSyncFallback = aligned
                                    }
                                }
                            } else {
                                lyricsDao.deleteLyrics(trackKey)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AuralisLyrics", "[getLyrics] Error reading DB cache: ${e.message}")
            }
        }

        // 3. Multi-Provider Cascade (LRCLIB, JioSaavn, NetEase, KuGou, Musixmatch, BetterLyrics, AMLL, Genius, YouTube)
        android.util.Log.d("AuralisLyrics", "[getLyrics] Calling multi-provider cascade for '$title' by '$artist'")
        val networkResult = lyricsClient.getLyrics(
            title = title,
            artist = artist,
            durationSec = durationSec,
            videoId = videoId,
            album = album,
            channelTitle = channelTitle,
            durationMs = playbackMs
        )
        if (networkResult != null && networkResult.lines.isNotEmpty()) {
            val alignedNetwork = if (playbackMs > 0L) {
                com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(networkResult, playbackMs)
            } else {
                networkResult
            }
            memoryCache[trackKey] = alignedNetwork
            android.util.Log.d("AuralisLyrics", "[getLyrics] Network HIT: provider=${alignedNetwork.provider}, syncType=${alignedNetwork.syncType}, lines=${alignedNetwork.lines.size}")

            // Save to SQLite Room database for persistent 0ms instant retrieval
            if (lyricsDao != null) {
                try {
                    val existing = lyricsDao.getLyrics(trackKey)
                    // Guard: Never downgrade a genuine word-sync cache entry to line-sync
                    if (existing == null || !existing.hasWordTiming || alignedNetwork.syncType == SyncType.RICHSYNC) {
                        val entity = domainToEntity(trackKey, alignedNetwork, title, artist)
                        lyricsDao.insertLyrics(entity)
                        negativeLyricsDao?.removeNegativeEntry(trackKey)
                    } else {
                        android.util.Log.d("AuralisLyrics", "[getLyrics] Preserving existing RICHSYNC DB entry for '$trackKey' against lower-tier network result (${alignedNetwork.provider} ${alignedNetwork.syncType})")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AuralisLyrics", "[getLyrics] Error writing to DB: ${e.message}")
                }
            }
            return alignedNetwork
        }

        if (cachedLineSyncFallback != null) {
            android.util.Log.d("AuralisLyrics", "[getLyrics] Preserving cached line-sync fallback for '$title' (${cachedLineSyncFallback.provider})")
            memoryCache[trackKey] = cachedLineSyncFallback
            return cachedLineSyncFallback
        }

        android.util.Log.w("AuralisLyrics", "[getLyrics] Multi-provider cascade returned no lyrics for '$title'")
        return networkResult
    }
}
