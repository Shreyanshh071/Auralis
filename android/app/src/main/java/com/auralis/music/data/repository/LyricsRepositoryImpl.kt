package com.auralis.music.data.repository

import com.auralis.music.data.local.dao.LyricsDao
import com.auralis.music.data.local.dao.NegativeLyricsDao
import com.auralis.music.data.local.entity.LyricsEntity
import com.auralis.music.data.local.entity.NegativeLyricsEntity
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.LyricsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class LyricsRepositoryImpl(
    private val lyricsClient: LyricsClient,
    private val lyricsDao: LyricsDao? = null,
    private val negativeLyricsDao: NegativeLyricsDao? = null
) : LyricsRepository {

    private val memoryCache = ConcurrentHashMap<String, LyricsData>()

    companion object {
        private const val NEGATIVE_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours TTL
    }

    override suspend fun getCachedLyrics(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?
    ): LyricsData? {
        val trackKey = (videoId?.takeIf { it.isNotBlank() } ?: "$title::$artist::${durationSec ?: 0}").lowercase()
        // 1. Check in-memory cache
        memoryCache[trackKey]?.let {
            return com.auralis.music.data.parser.LyricsMatcher.autoAlignLyrics(it, durationSec, null)
        }

        // 2. Check local SQLite Room DB cache (0ms instant display)
        if (lyricsDao != null) {
            try {
                val entity = lyricsDao.getLyrics(trackKey)
                if (entity != null) {
                    val domainLyrics = entityToDomain(entity, title, artist)
                    if (domainLyrics != null) {
                        val aligned = com.auralis.music.data.parser.LyricsMatcher.autoAlignLyrics(domainLyrics, durationSec, null)
                        val firstTime = aligned.lines.firstOrNull()?.time ?: 0L
                        // If cached is plain/empty or unverified LRCLIB starting at 0ms, skip cache to query studio sources
                        if (!(aligned.provider == LyricsProvider.LRCLIB && firstTime == 0L)) {
                            memoryCache[trackKey] = aligned
                            return aligned
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
        forceRefresh: Boolean
    ): LyricsData? {
        val trackKey = (videoId?.takeIf { it.isNotBlank() } ?: "$title::$artist::${durationSec ?: 0}").lowercase()

        // 1. Check in-memory cache
        if (!forceRefresh) {
            memoryCache[trackKey]?.let { cached ->
                val aligned = com.auralis.music.data.parser.LyricsMatcher.autoAlignLyrics(cached, durationSec, null)
                val firstTime = aligned.lines.firstOrNull()?.time ?: 0L
                if (!(aligned.provider == LyricsProvider.LRCLIB && firstTime == 0L)) {
                    return aligned
                }
            }
        }

        // 2. Check local SQLite Room DB cache (0ms instant display)
        if (!forceRefresh && lyricsDao != null) {
            try {
                val entity = lyricsDao.getLyrics(trackKey)
                if (entity != null) {
                    val domainLyrics = entityToDomain(entity, title, artist)
                    if (domainLyrics != null) {
                        val aligned = com.auralis.music.data.parser.LyricsMatcher.autoAlignLyrics(domainLyrics, durationSec, null)
                        val firstTime = aligned.lines.firstOrNull()?.time ?: 0L
                        if (!(aligned.provider == LyricsProvider.LRCLIB && firstTime == 0L)) {
                            memoryCache[trackKey] = aligned
                            return aligned
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Check Negative Cache (Skip recent failed lookups to prevent provider rate-limits)
        if (!forceRefresh && negativeLyricsDao != null) {
            try {
                val negEntry = negativeLyricsDao.getNegativeEntry(trackKey)
                if (negEntry != null && (System.currentTimeMillis() - negEntry.cachedAt) < NEGATIVE_CACHE_TTL_MS) {
                    return null
                }
            } catch (_: Exception) {}
        }

        // 4. Multi-Provider Cascade (AMLL RichSync -> LRCLIB, JioSaavn, NetEase, KuGou, Musixmatch -> Genius, YouTube)
        val networkResult = lyricsClient.getLyrics(title, artist, durationSec, videoId)
        if (networkResult != null && networkResult.lines.isNotEmpty()) {
            val alignedNetwork = com.auralis.music.data.parser.LyricsMatcher.autoAlignLyrics(networkResult, durationSec, null)
            memoryCache[trackKey] = alignedNetwork

            // Save to SQLite Room database for persistent 0ms instant retrieval
            if (lyricsDao != null) {
                try {
                    val entity = domainToEntity(trackKey, alignedNetwork)
                    lyricsDao.insertLyrics(entity)
                    negativeLyricsDao?.removeNegativeEntry(trackKey)
                } catch (_: Exception) {}
            }
            return alignedNetwork
        } else {
            // Record negative entry with 12-hour TTL to prevent repeated slow missed lookups
            if (negativeLyricsDao != null) {
                try {
                    negativeLyricsDao.insertNegativeEntry(
                        NegativeLyricsEntity(trackKey = trackKey, cachedAt = System.currentTimeMillis())
                    )
                } catch (_: Exception) {}
            }
        }

        return networkResult
    }

    private fun domainToEntity(trackKey: String, domain: LyricsData): LyricsEntity {
        val linesArray = JSONArray()
        for (line in domain.lines) {
            val lineObj = JSONObject()
            lineObj.put("time", line.time)
            lineObj.put("text", line.text)
            lineObj.put("isInstrumental", line.isInstrumental)
            if (!line.words.isNullOrEmpty()) {
                val wordsArray = JSONArray()
                for (w in line.words) {
                    val wObj = JSONObject()
                    wObj.put("word", w.word)
                    wObj.put("time", w.time)
                    if (w.duration != null) wObj.put("duration", w.duration)
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
            provider = domain.provider.name
        )
    }

    private fun entityToDomain(entity: LyricsEntity, fallbackTitle: String, fallbackArtist: String): LyricsData? {
        try {
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
                                duration = if (wObj.has("duration")) wObj.getLong("duration") else null
                            )
                        )
                    }
                    wList
                } else null

                lines.add(LyricLine(time = time, text = text, words = words, isInstrumental = isInst))
            }

            val cleanedLines = lines.filterNot { com.auralis.music.data.parser.LrcParser.isMetadataOrCreditLine(it.text) }
            val resolvedLines = if (cleanedLines.size >= 4 && cleanedLines.none { !it.words.isNullOrEmpty() }) {
                com.auralis.music.data.parser.LrcParser.mergeMicroFragments(cleanedLines)
            } else {
                cleanedLines
            }

            val hasDerivedWordSync = resolvedLines.any { it.words != null && it.words.isNotEmpty() }
            val resolvedSyncType = if (syncType == SyncType.LINE_SYNC && hasDerivedWordSync) {
                SyncType.RICHSYNC
            } else {
                syncType
            }

            return LyricsData(
                syncType = resolvedSyncType,
                lines = resolvedLines,
                plainLyrics = entity.plainLyrics,
                provider = provider,
                trackName = fallbackTitle,
                artistName = fallbackArtist
            )
        } catch (_: Exception) {
            return null
        }
    }
}
