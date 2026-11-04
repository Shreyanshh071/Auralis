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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class LyricsRepositoryImpl(
    private val lyricsClient: LyricsClient,
    private val lyricsDao: LyricsDao? = null,
    private val negativeLyricsDao: NegativeLyricsDao? = null,
    private val spicyEnabled: () -> Boolean = { LyricsClient.isProviderEnabled(LyricsProvider.SPICY_LYRICS) },
    private val spicyLookup: suspend (com.auralis.music.data.network.provider.LyricsSearchQuery) -> LyricsData? = { q ->
        lyricsClient.searchProvider(LyricsProvider.SPICY_LYRICS, q.title, q.artist, q.durationSec,
            q.videoId, q.album, q.channelTitle, q.durationMs, q.audioLeadingSilenceMs, q.spotifyId)
    },
    private val clock: () -> Long = System::currentTimeMillis
) : LyricsRepository {

    private val memoryCache = com.auralis.music.data.network.LyricsResponseCache()
    private val spicyAttempts = mutableMapOf<String, Long>()

    init {
        // Purge only line-sync rows written by an older parser pipeline to allow
        // genuine word-sync upgrade, while preserving all genuine word-sync entries intact.
        if (lyricsDao != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    lyricsDao.purgeStalePipeline(LYRICS_PIPELINE_VERSION)
                    val now = System.currentTimeMillis()
                    lyricsDao.purgeExpiredSpicy(now - com.auralis.music.data.network.LyricsCachePolicy.SPICY_MAX_AGE_MS, now)
                    negativeLyricsDao?.cleanExpired(System.currentTimeMillis() + 86400000L)
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        kotlinx.coroutines.delay(60_000L)
                        val purgeAt = System.currentTimeMillis()
                        lyricsDao.purgeExpiredSpicy(purgeAt - com.auralis.music.data.network.LyricsCachePolicy.SPICY_MAX_AGE_MS, purgeAt)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        internal fun chooseSpicy(current: LyricsData?, candidate: LyricsData?): LyricsData? {
            if (candidate == null || candidate.lines.isEmpty() || candidate.provider != LyricsProvider.SPICY_LYRICS) return current
            if (LyricsClient.tierOf(candidate) < LyricsClient.TIER_LINE) return current
            return if (current != null && LyricsClient.tierOf(current) > LyricsClient.tierOf(candidate)) current else candidate
        }
        private const val NEGATIVE_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours TTL

        /**
         * Bump when parser timing semantics change, so rows written under the old
         * meaning are dropped once instead of being trusted forever.
         *
         * 1 = Initial baseline
         * 2 = Phase 4B: BetterLyrics Boidu/Binimum integration.
         * 3 = Phase 4C: concurrent BetterLyrics (Boidu/Binimum race) and inline stale line-sync purge.
         * 4 = Phase 4C final: purge stale line-sync cache entries to allow genuine word-sync upgrade.
         * 5 = Phase 4D: recovered Binimum Apple Music word-sync, subtitle matching, space preservation, and intro alignment.
         * 6 = Phase 5: Paxsenix Apple Music syllable-synced lyrics provider integration.
         * 7 = Phase 5.1: strict cache revalidation, downgrade protection, and duration alignment fix.
         * 8 = Phase 5.2: comprehensive syllable-merging engine and split-word healing.
         * 9 = Phase 5.3: automatic accidental merged-word splitting ("Theless" -> "The less") and cache invalidation.
         * 10 = Phase 5.4: real-device sync accuracy, master matching, and gap-filler duplicate protection.
         * 11 = Phase 5.5: master-aware audio leading silence alignment & resilient NetEase DNS.
         * 12 = Phase 5.6: Float PCM leading silence processor & studio audio duration mapping.
         */
        const val LYRICS_PIPELINE_VERSION = 19 // 19: reacquire rich-sync copies after fixing the validator's rejection of overlapping vocal lines; 18: lyrics kept in their source script again
        // 13: same-timestamp LRC translations paired; cache keeps translatedText

        /**
         * Speaker enrichment acceptance: the network result may replace a cached entry only
         * when it keeps the timing tier (genuine word sync), matches the playback master and
         * actually carries line-level speaker metadata. Otherwise null — nothing changes.
         */
        internal fun acceptSpeakerEnrichment(
            networkResult: LyricsData,
            title: String,
            artist: String,
            channelTitle: String?,
            videoId: String?,
            playbackMs: Long,
            audioLeadingSilenceMs: Long?
        ): LyricsData? {
            if (networkResult.lines.isEmpty()) return null
            if (!LyricsClient.hasSpeakerMetadata(networkResult)) return null
            if (!com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(networkResult.lines)) return null
            val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = networkResult,
                playbackDurationMs = playbackMs,
                playbackTitle = title,
                candidateTitle = networkResult.trackName ?: title,
                playbackChannelTitle = channelTitle,
                playbackVideoId = videoId,
                audioLeadingSilenceMs = audioLeadingSilenceMs,
                playbackArtist = artist,
                candidateArtist = networkResult.artistName ?: artist
            )
            if (!isAcceptable) return null
            return if (playbackMs > 0L) {
                com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(networkResult, playbackMs, audioLeadingSilenceMs)
            } else networkResult
        }

        private fun encodeWord(word: LyricWord): JSONObject = JSONObject().apply {
            put("word", word.word)
            put("time", word.time)
            word.duration?.let { put("duration", it) }
            if (word.isBackground) put("isBackground", true)
            word.syllables?.let { parts -> put("syllables", JSONArray().apply { parts.forEach { put(encodeWord(it)) } }) }
        }

        private fun decodeWord(json: JSONObject, depth: Int = 0): LyricWord {
            require(depth < 16) { "Invalid nested lyric timing" }
            val parts = json.optJSONArray("syllables")
            return LyricWord(json.getString("word"), json.getLong("time"),
                if (json.has("duration") && !json.isNull("duration")) json.getLong("duration") else null,
                json.optBoolean("isBackground", false),
                parts?.let { (0 until it.length()).map { index -> decodeWord(it.getJSONObject(index), depth + 1) } })
        }

        internal fun domainToEntity(trackKey: String, domain: LyricsData, title: String, artist: String): LyricsEntity {
            val linesArray = JSONArray()
            val rawOffset = -domain.appliedOffsetMs
            val cleanedLines = domain.lines.map { line ->
                val unshifted = if (rawOffset != 0L) {
                    val shiftedWords = line.words?.map { w ->
                        w.shiftedBy(rawOffset)
                    }
                    line.copy(
                        time = (line.time + rawOffset).coerceAtLeast(0L),
                        words = shiftedWords,
                        endTime = line.endTime?.let { (it + rawOffset).coerceAtLeast(0L) }
                    )
                } else line
                if (domain.provider == LyricsProvider.SPICY_LYRICS) unshifted else com.auralis.music.data.parser.WordTiming.splitMergedWordsInLine(unshifted)
            }
            for (line in cleanedLines) {
                val lineObj = JSONObject()
                lineObj.put("time", line.time)
                lineObj.put("text", line.text)
                lineObj.put("isInstrumental", line.isInstrumental)
                if (line.isBackground) lineObj.put("isBackground", true)
                if (line.endTime != null) lineObj.put("endTime", line.endTime)
                if (line.agent != null) lineObj.put("agent", line.agent)
                // Persist translations; they were silently dropped on every cache round-trip.
                if (!line.translatedText.isNullOrBlank()) lineObj.put("translatedText", line.translatedText)
                if (!line.words.isNullOrEmpty()) {
                    val wordsArray = JSONArray()
                    for (w in line.words) {
                        wordsArray.put(encodeWord(w))
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
                leadingSilenceMs = domain.leadingSilenceMs,
                creditsJson = com.auralis.music.data.parser.SpicyLyricsParser.encodeCredits(domain.credits).toString(),
                spotifyTrackId = domain.spotifyTrackId,
                fetchedAtMs = domain.fetchedAtMs
            )
        }

        internal fun entityToDomain(entity: LyricsEntity, queryTitle: String, queryArtist: String): LyricsData? {
            try {
                if (entity.provider == "SPICY_LYRICS" && com.auralis.music.data.network.LyricsCachePolicy.isSpicyTimestampExpired(entity.fetchedAtMs, System.currentTimeMillis())) return null
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
                    val endTime = if (lineObj.has("endTime")) lineObj.getLong("endTime") else null
                    val agent = if (lineObj.has("agent")) lineObj.getString("agent") else null

                    val wordsArray = lineObj.optJSONArray("words")
                    val rawWords = if (wordsArray != null && wordsArray.length() > 0) {
                        val wList = mutableListOf<LyricWord>()
                        for (j in 0 until wordsArray.length()) {
                            val wObj = wordsArray.getJSONObject(j)
                            wList.add(decodeWord(wObj))
                        }
                        wList
                    } else null

                    val mergedWords = if (provider == LyricsProvider.SPICY_LYRICS) rawWords else if (!rawWords.isNullOrEmpty()) {
                        com.auralis.music.data.parser.WordTiming.mergeContiguousSyllables(rawWords) ?: rawWords
                    } else null

                    val resolvedText = if (!mergedWords.isNullOrEmpty() && rawWords != null && mergedWords.size < rawWords.size) {
                        mergedWords.joinToString("") { it.word }.trim()
                    } else {
                        if (provider == LyricsProvider.SPICY_LYRICS) text else com.auralis.music.data.parser.WordTiming.healSplitWordsInText(text)
                    }

                    val baseLine = LyricLine(
                        time = time,
                        text = resolvedText,
                        translatedText = lineObj.optString("translatedText").takeIf { it.isNotBlank() },
                        words = mergedWords,
                        isInstrumental = isInst,
                        isBackground = lineObj.optBoolean("isBackground", false),
                        endTime = endTime,
                        agent = agent
                    )
                    lines.add(if (provider == LyricsProvider.SPICY_LYRICS) baseLine else com.auralis.music.data.parser.WordTiming.splitMergedWordsInLine(baseLine))
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

                val isGenuineVideoKey = !entity.trackId.contains("::") &&
                    entity.trackId.isNotBlank() &&
                    !entity.trackId.startsWith("sp_") &&
                    !entity.trackId.startsWith("spotify:")
                val isExactVideo = isGenuineVideoKey &&
                    provider == LyricsProvider.UNISON &&
                    entity.hasWordTiming

                // Rows cached before the display cleanup existed still carry credits, "🎵" marker
                // lines and "Artists - Title" headers; clean them the same way as fresh results.
                return com.auralis.music.data.parser.LyricsContentFilter.cleanForDisplay(
                    LyricsData(
                        syncType = resolvedSyncType,
                        lines = resolvedLines,
                        plainLyrics = entity.plainLyrics,
                        provider = provider,
                        trackName = candTitle,
                        artistName = candArtist,
                        durationMs = entity.durationMs,
                        leadingSilenceMs = entity.leadingSilenceMs,
                        credits = com.auralis.music.data.parser.SpicyLyricsParser.decodeCredits(entity.creditsJson?.let { JSONArray(it) }),
                        spotifyTrackId = entity.spotifyTrackId,
                        fetchedAtMs = entity.fetchedAtMs,
                        isExactVideoMatch = isExactVideo,
                        matchedVideoId = if (isGenuineVideoKey) entity.trackId else null
                    ),
                    title = com.auralis.music.data.network.TitleCleaner.cleanCoreSongTitle(queryTitle)
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
        durationMs: Long?,
        audioLeadingSilenceMs: Long?
    ): LyricsData? {
        com.auralis.music.data.local.ImportedLyricsStore.read(videoId)?.let { return it }
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
            val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = cached,
                playbackDurationMs = playbackMs,
                playbackTitle = title,
                candidateTitle = candTitle,
                playbackChannelTitle = channelTitle,
                playbackVideoId = videoId,
                audioLeadingSilenceMs = audioLeadingSilenceMs,
                playbackArtist = artist,
                candidateArtist = candArtist
            )
            val isIntroCorrupt = com.auralis.music.data.parser.LyricsValidator.hasCorruptIntroTiming(cached, durationSec)
            if (confidence >= 50 && isAcceptable && !isIntroCorrupt && !com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(cached)) {
                val aligned = if (playbackMs > 0L) {
                    com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(cached, playbackMs, audioLeadingSilenceMs)
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
                    if (entity.pipelineVersion < LYRICS_PIPELINE_VERSION) {
                        android.util.Log.d("AuralisLyrics", "[getCachedLyrics] Purging stale cache entry for '$trackKey' (pipelineVersion=${entity.pipelineVersion} < $LYRICS_PIPELINE_VERSION)")
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
                            val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                                lyrics = domainLyrics,
                                playbackDurationMs = playbackMs,
                                playbackTitle = title,
                                candidateTitle = candTitle,
                                playbackChannelTitle = channelTitle,
                                playbackVideoId = videoId,
                                audioLeadingSilenceMs = audioLeadingSilenceMs,
                                playbackArtist = artist,
                                candidateArtist = candArtist
                            )
                            val isMasterMismatch = playbackMs > 0L && com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                                lyrics = domainLyrics,
                                playbackDurationMs = playbackMs,
                                playbackTitle = title,
                                candidateTitle = candTitle,
                                playbackChannelTitle = channelTitle,
                                playbackVideoId = videoId,
                                playbackArtist = artist,
                                candidateArtist = candArtist
                            ) == com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH

                            val isDomainIntroCorrupt = com.auralis.music.data.parser.LyricsValidator.hasCorruptIntroTiming(domainLyrics, durationSec)

                            if (confidence >= 50 && isAcceptable && !isDomainIntroCorrupt && !com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(domainLyrics)) {
                                val aligned = if (playbackMs > 0L) {
                                    com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(domainLyrics, playbackMs, audioLeadingSilenceMs)
                                } else {
                                    domainLyrics
                                }
                                if (aligned.syncType != SyncType.PLAIN && aligned.lines.isNotEmpty()) {
                                    memoryCache[trackKey] = aligned
                                    return aligned
                                }
                            } else if (isMasterMismatch || isDomainIntroCorrupt || com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(domainLyrics)) {
                                lyricsDao.deleteLyrics(trackKey)
                                memoryCache.remove(trackKey)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // The same recording can enter the app through different catalog IDs
            // (for example Spotify and YouTube). Do not throw away a known synced
            // result merely because the currently playing duplicate has another ID.
            try {
                val metadataEntity = lyricsDao.getBestLyricsByMetadata(
                    title = title,
                    artist = artist,
                    durationMs = playbackMs,
                    pipelineVersion = LYRICS_PIPELINE_VERSION
                )
                if (metadataEntity != null && metadataEntity.trackId != trackKey) {
                    val metadataLyrics = entityToDomain(metadataEntity, title, artist)?.copy(
                        // A match from another catalog ID is metadata-matched, not
                        // an exact-video match, even when its original provider was.
                        isExactVideoMatch = false,
                        matchedVideoId = null
                    )
                    if (metadataLyrics != null && metadataLyrics.syncType != SyncType.PLAIN) {
                        val candTitle = metadataLyrics.trackName ?: title
                        val candArtist = metadataLyrics.artistName ?: artist
                        val confidence = com.auralis.music.data.parser.LyricsMatcher.calculateConfidence(
                            queryTitle = title,
                            queryArtist = artist,
                            candidateTitle = candTitle,
                            candidateArtist = candArtist,
                            queryDurationSec = durationSec,
                            queryAlbum = album
                        )
                        val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                            lyrics = metadataLyrics,
                            playbackDurationMs = playbackMs,
                            playbackTitle = title,
                            candidateTitle = candTitle,
                            playbackChannelTitle = channelTitle,
                            playbackVideoId = videoId,
                            audioLeadingSilenceMs = audioLeadingSilenceMs,
                            playbackArtist = artist,
                            candidateArtist = candArtist
                        )
                        val isInvalid = com.auralis.music.data.parser.LyricsValidator.hasCorruptIntroTiming(metadataLyrics, durationSec) ||
                            com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(metadataLyrics)
                        if (confidence >= 50 && isAcceptable && !isInvalid) {
                            val aligned = if (playbackMs > 0L) {
                                com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(
                                    metadataLyrics,
                                    playbackMs,
                                    audioLeadingSilenceMs
                                )
                            } else {
                                metadataLyrics
                            }
                            if (aligned.syncType != SyncType.PLAIN && aligned.lines.isNotEmpty()) {
                                memoryCache[trackKey] = aligned
                                lyricsDao.insertLyrics(domainToEntity(trackKey, aligned, title, artist))
                                android.util.Log.d(
                                    "AuralisLyrics",
                                    "[getCachedLyrics] Reused ${aligned.syncType} cache by metadata for '$title' " +
                                        "(${metadataEntity.trackId} -> $trackKey)"
                                )
                                return aligned
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    override fun selectableProviders(): List<com.auralis.music.domain.model.LyricsProvider> =
        lyricsClient.selectableProviders()

    override suspend fun fetchFromProvider(
        provider: com.auralis.music.domain.model.LyricsProvider,
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?,
        album: String?,
        channelTitle: String?,
        durationMs: Long?,
        audioLeadingSilenceMs: Long?
    ): LyricsData? {
        val playbackMs = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)
        val result = lyricsClient.searchProvider(
            provider = provider,
            title = title,
            artist = artist,
            durationSec = durationSec,
            videoId = videoId,
            album = album,
            channelTitle = channelTitle,
            durationMs = playbackMs,
            audioLeadingSilenceMs = audioLeadingSilenceMs,
            reportFailures = true
        ) ?: return null
        return if (playbackMs > 0L && result.syncType != SyncType.PLAIN) {
            com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(result, playbackMs, audioLeadingSilenceMs)
        } else result
    }

    override suspend fun useLyrics(
        lyrics: LyricsData,
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?
    ) {
        val trackKey = (videoId?.takeIf { it.isNotBlank() } ?: "$title::$artist::${durationSec ?: 0}").lowercase()
        memoryCache[trackKey] = lyrics
        try {
            lyricsDao?.insertLyrics(domainToEntity(trackKey, lyrics, title, artist))
            negativeLyricsDao?.removeNegativeEntry(trackKey)
        } catch (e: Exception) {
            android.util.Log.w("AuralisLyrics", "[useLyrics] Error writing to DB: ${e.message}")
        }
    }

    override suspend fun upgradeSpicy(
        title: String, artist: String, durationSec: Long?, videoId: String?, album: String?,
        channelTitle: String?, durationMs: Long?, audioLeadingSilenceMs: Long?, spotifyId: String?
    ): LyricsData? {
        com.auralis.music.data.local.ImportedLyricsStore.read(videoId)?.let { return it }
        if (!spicyEnabled()) return null
        val existing = getCachedLyrics(title, artist, durationSec, videoId, album, channelTitle, durationMs, audioLeadingSilenceMs)
        if (existing?.provider == LyricsProvider.SPICY_LYRICS && existing.fetchedAtMs?.let {
                clock() - it in 0 until 24 * 60 * 60_000L
            } == true) return existing
        val attemptKey = listOf(videoId, spotifyId, title, artist, durationMs).joinToString("|")
        synchronized(spicyAttempts) {
            val last = spicyAttempts[attemptKey]
            if (last != null && clock() - last < 15_000L) return null
            if (spicyAttempts.size >= 512) spicyAttempts.clear()
            spicyAttempts[attemptKey] = clock()
        }
        val result = try {
            kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                spicyLookup(com.auralis.music.data.network.provider.LyricsSearchQuery(title, artist, durationSec,
                    videoId, album, channelTitle, durationMs, spotifyId = spotifyId, audioLeadingSilenceMs = audioLeadingSilenceMs))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            synchronized(spicyAttempts) { spicyAttempts.remove(attemptKey) }
            throw e
        } catch (_: Exception) { null } ?: return null
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val playbackMs = durationMs?.takeIf { it > 0 } ?: (durationSec ?: 0) * 1000L
        if (!com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(result, playbackMs,
                title, result.trackName, channelTitle, videoId, audioLeadingSilenceMs, artist, result.artistName)) return null
        val current = getCachedLyrics(title, artist, durationSec, videoId, album, channelTitle, durationMs, audioLeadingSilenceMs)
        val selected = chooseSpicy(current, result)
        if (selected !== result) return null
        val aligned = if (playbackMs > 0) com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(result, playbackMs, audioLeadingSilenceMs) else result
        useLyrics(aligned, title, artist, durationSec, videoId)
        return aligned
    }

    override suspend fun enrichSpeakerMetadata(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?,
        album: String?,
        channelTitle: String?,
        durationMs: Long?,
        audioLeadingSilenceMs: Long?
    ): LyricsData? {
        com.auralis.music.data.local.ImportedLyricsStore.read(videoId)?.let { return it }
        val trackKey = (videoId?.takeIf { it.isNotBlank() } ?: "$title::$artist::${durationSec ?: 0}").lowercase()
        val playbackMs = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)
        val networkResult = lyricsClient.getLyrics(
            title = title,
            artist = artist,
            durationSec = durationSec,
            videoId = videoId,
            album = album,
            channelTitle = channelTitle,
            durationMs = playbackMs,
            audioLeadingSilenceMs = audioLeadingSilenceMs
        ) ?: return null
        return acceptSpeakerEnrichment(networkResult, title, artist, channelTitle, videoId, playbackMs, audioLeadingSilenceMs)
            ?.also { enriched ->
                android.util.Log.d("AuralisLyrics", "[enrichSpeakerMetadata] Speaker-tagged ${enriched.provider} replaces cached entry for '$trackKey'")
                memoryCache[trackKey] = enriched
                try {
                    lyricsDao?.insertLyrics(domainToEntity(trackKey, enriched, title, artist))
                } catch (e: Exception) {
                    android.util.Log.w("AuralisLyrics", "[enrichSpeakerMetadata] Error writing to DB: ${e.message}")
                }
            }
    }

    override suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?,
        forceRefresh: Boolean,
        album: String?,
        channelTitle: String?,
        durationMs: Long?,
        audioLeadingSilenceMs: Long?
    ): LyricsData? = getLyricsInternal(title, artist, durationSec, videoId, forceRefresh, album, channelTitle, durationMs, audioLeadingSilenceMs, null)

    override suspend fun getLyricsWithInterim(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?,
        forceRefresh: Boolean,
        album: String?,
        channelTitle: String?,
        durationMs: Long?,
        audioLeadingSilenceMs: Long?,
        onInterim: (LyricsData) -> Unit
    ): LyricsData? = getLyricsInternal(title, artist, durationSec, videoId, forceRefresh, album, channelTitle, durationMs, audioLeadingSilenceMs, onInterim)

    private suspend fun getLyricsInternal(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?,
        forceRefresh: Boolean,
        album: String?,
        channelTitle: String?,
        durationMs: Long?,
        audioLeadingSilenceMs: Long?,
        onInterim: ((LyricsData) -> Unit)?
    ): LyricsData? {
        com.auralis.music.data.local.ImportedLyricsStore.read(videoId)?.let { return it }
        val trackKey =(videoId?.takeIf { it.isNotBlank() } ?: "$title::$artist::${durationSec ?: 0}").lowercase()
        val playbackMs = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)
        android.util.Log.d("AuralisLyrics", "[getLyrics] Request for '$title' by '$artist' (key=$trackKey, forceRefresh=$forceRefresh, album=$album, durMs=$playbackMs)")
        var cachedMusixmatchWordFallback: LyricsData? = null

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
                val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                    lyrics = cached,
                    playbackDurationMs = playbackMs,
                    playbackTitle = title,
                    candidateTitle = candTitle,
                    playbackChannelTitle = channelTitle,
                    playbackVideoId = videoId,
                    audioLeadingSilenceMs = audioLeadingSilenceMs,
                    playbackArtist = artist,
                    candidateArtist = candArtist
                )
                val isIntroCorrupt = com.auralis.music.data.parser.LyricsValidator.hasCorruptIntroTiming(cached, durationSec)
                if (confidence >= 50 && isAcceptable && !isIntroCorrupt && !com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(cached)) {
                    val aligned = if (playbackMs > 0L) {
                        com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(cached, playbackMs, audioLeadingSilenceMs)
                    } else cached
                    if (aligned.syncType != SyncType.PLAIN && aligned.lines.isNotEmpty()) {
                        if (com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(aligned.lines)) {
                            android.util.Log.d("AuralisLyrics", "[getLyrics] Memory cache HIT (Word sync, provider=${aligned.provider})")
                            if (aligned.provider == com.auralis.music.domain.model.LyricsProvider.MUSIXMATCH) {
                                cachedMusixmatchWordFallback = aligned
                            } else return aligned
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
                            val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                                lyrics = domainLyrics,
                                playbackDurationMs = playbackMs,
                                playbackTitle = title,
                                candidateTitle = candTitle,
                                playbackChannelTitle = channelTitle,
                                playbackVideoId = videoId,
                                audioLeadingSilenceMs = audioLeadingSilenceMs,
                                playbackArtist = artist,
                                candidateArtist = candArtist
                            )
                            val isMasterMismatch = playbackMs > 0L && com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                                lyrics = domainLyrics,
                                playbackDurationMs = playbackMs,
                                playbackTitle = title,
                                candidateTitle = candTitle,
                                playbackChannelTitle = channelTitle,
                                playbackVideoId = videoId,
                                playbackArtist = artist,
                                candidateArtist = candArtist
                            ) == com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH

                            val isDomainIntroCorrupt = com.auralis.music.data.parser.LyricsValidator.hasCorruptIntroTiming(domainLyrics, durationSec)

                            if (confidence >= 50 && isAcceptable && !isDomainIntroCorrupt && !com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(domainLyrics)) {
                                val aligned = if (playbackMs > 0L) {
                                    com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(domainLyrics, playbackMs, audioLeadingSilenceMs)
                                } else domainLyrics
                                if (aligned.syncType != SyncType.PLAIN && aligned.lines.isNotEmpty()) {
                                    if (entity.hasWordTiming && com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(aligned.lines)) {
                                        android.util.Log.d("AuralisLyrics", "[getLyrics] Room DB cache HIT (Word sync, provider=${aligned.provider})")
                                        if (aligned.provider == com.auralis.music.domain.model.LyricsProvider.MUSIXMATCH) {
                                            cachedMusixmatchWordFallback = aligned
                                        } else {
                                            memoryCache[trackKey] = aligned
                                            return aligned
                                        }
                                    } else {
                                        // Line-sync cached from earlier; hold as fallback and allow cascade to seek a word-sync upgrade
                                        cachedLineSyncFallback = aligned
                                    }
                                }
                            } else if (isMasterMismatch || isDomainIntroCorrupt || com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(domainLyrics)) {
                                lyricsDao.deleteLyrics(trackKey)
                                memoryCache.remove(trackKey)
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
            durationMs = playbackMs,
            audioLeadingSilenceMs = audioLeadingSilenceMs,
            // Interim results get the same playback alignment as the final one; they're shown
            // only, never cached — the final answer is what's written below.
            onInterim = onInterim?.let { deliver ->
                { interim: LyricsData ->
                    deliver(
                        if (playbackMs > 0L) {
                            com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(interim, playbackMs, audioLeadingSilenceMs)
                        } else interim
                    )
                }
            }
        )
        // A cached Musixmatch word result remains a fallback, but it must not stop
        // other word sources from being tried. It still outranks line-only results.
        if (cachedMusixmatchWordFallback != null &&
            (networkResult == null || LyricsClient.tierOf(networkResult) < LyricsClient.TIER_WORD)) {
            memoryCache[trackKey] = cachedMusixmatchWordFallback
            return cachedMusixmatchWordFallback
        }
        if (networkResult != null && networkResult.lines.isNotEmpty()) {
            val alignedNetwork = if (playbackMs > 0L) {
                com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(networkResult, playbackMs, audioLeadingSilenceMs)
            } else {
                networkResult
            }

            // Check if existing Room cache or memory cache holds a valid aligned RichSync result
            val existingEntity = lyricsDao?.getLyrics(trackKey)
            val existingDomain = existingEntity?.let { entityToDomain(it, title, artist) }
            val existingIsAlignedRichSync = if (existingDomain != null && existingEntity.hasWordTiming) {
                com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                    lyrics = existingDomain,
                    playbackDurationMs = playbackMs,
                    playbackTitle = title,
                    candidateTitle = existingDomain.trackName ?: title,
                    playbackChannelTitle = channelTitle,
                    playbackVideoId = videoId,
                    audioLeadingSilenceMs = audioLeadingSilenceMs,
                    playbackArtist = artist,
                    candidateArtist = existingDomain.artistName ?: artist
                ) &&
                    !com.auralis.music.data.parser.LyricsValidator.hasCorruptIntroTiming(existingDomain, durationSec) &&
                    com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(existingDomain.lines)
            } else false

            // INVARIANT: If existing cache has valid aligned RICHSYNC, and network returned lower-tier LINE_SYNC,
            // never downgrade memory cache or return value to LINE_SYNC!
            if (existingIsAlignedRichSync && alignedNetwork.syncType != SyncType.RICHSYNC) {
                android.util.Log.d("AuralisLyrics", "[getLyrics] Preserving existing valid RICHSYNC cache entry for '$trackKey' against lower-tier network result (${alignedNetwork.provider} ${alignedNetwork.syncType})")
                val alignedExisting = if (playbackMs > 0L && existingDomain != null) {
                    com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(existingDomain, playbackMs, audioLeadingSilenceMs)
                } else existingDomain
                if (alignedExisting != null) {
                    memoryCache[trackKey] = alignedExisting
                    return alignedExisting
                }
            }

            memoryCache[trackKey] = alignedNetwork
            android.util.Log.d("AuralisLyrics", "[getLyrics] Network HIT: provider=${alignedNetwork.provider}, syncType=${alignedNetwork.syncType}, lines=${alignedNetwork.lines.size}")

            // Save to SQLite Room database for persistent 0ms instant retrieval
            if (lyricsDao != null) {
                try {
                    val existing = existingEntity ?: lyricsDao.getLyrics(trackKey)
                    val existingIsMismatch = if (existing != null) {
                        val dom = entityToDomain(existing, title, artist)
                        if (dom != null) {
                            com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                                lyrics = dom,
                                playbackDurationMs = playbackMs,
                                playbackTitle = title,
                                candidateTitle = dom.trackName ?: title,
                                playbackChannelTitle = channelTitle,
                                playbackVideoId = videoId
                            ) == com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH
                        } else false
                    } else false

                    // Guard: Never downgrade a genuine word-sync cache entry to line-sync UNLESS existing is a verified MASTER_MISMATCH with playback
                    if (existing == null || !existing.hasWordTiming || alignedNetwork.syncType == SyncType.RICHSYNC || existingIsMismatch) {
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

        // If network cascade returned no lyrics, check if we have a valid aligned RichSync row in Room DB
        val existingEntity = lyricsDao?.getLyrics(trackKey)
        val existingDomain = existingEntity?.let { entityToDomain(it, title, artist) }
        if (existingDomain != null && existingEntity.hasWordTiming && com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(existingDomain.lines)) {
            val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = existingDomain,
                playbackDurationMs = playbackMs,
                playbackTitle = title,
                candidateTitle = existingDomain.trackName ?: title,
                playbackChannelTitle = channelTitle,
                playbackVideoId = videoId,
                audioLeadingSilenceMs = audioLeadingSilenceMs
            )
            if (isAcceptable) {
                val alignedExisting = if (playbackMs > 0L) {
                    com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(existingDomain, playbackMs, audioLeadingSilenceMs)
                } else existingDomain
                memoryCache[trackKey] = alignedExisting
                return alignedExisting
            }
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
