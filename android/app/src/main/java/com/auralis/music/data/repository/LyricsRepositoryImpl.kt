package com.auralis.music.data.repository

import com.auralis.music.data.local.dao.LyricsDao
import com.auralis.music.data.local.entity.LyricsEntity
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.LyricsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class LyricsRepositoryImpl(
    private val lyricsClient: LyricsClient,
    private val lyricsDao: LyricsDao? = null
) : LyricsRepository {

    private val memoryCache = ConcurrentHashMap<String, LyricsData>()

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
            memoryCache[trackKey]?.let { return it }
        }

        // 2. Check local SQLite Room DB cache
        if (!forceRefresh && lyricsDao != null) {
            try {
                val entity = lyricsDao.getLyrics(trackKey)
                if (entity != null) {
                    val domainLyrics = entityToDomain(entity, title, artist)
                    if (domainLyrics != null) {
                        memoryCache[trackKey] = domainLyrics
                        return domainLyrics
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fetch from Metrolist Multi-Provider Cascade (LRCLIB + KuGou + AMLL)
        val networkResult = lyricsClient.getLyrics(title, artist, durationSec, videoId)
        if (networkResult != null) {
            memoryCache[trackKey] = networkResult

            // 4. Persist into SQLite Room database
            if (lyricsDao != null) {
                try {
                    val entity = domainToEntity(trackKey, networkResult)
                    lyricsDao.insertLyrics(entity)
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

            return LyricsData(
                syncType = syncType,
                lines = lines,
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
