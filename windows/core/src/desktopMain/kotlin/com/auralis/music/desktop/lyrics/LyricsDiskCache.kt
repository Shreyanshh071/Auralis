package com.auralis.music.desktop.lyrics

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import com.auralis.music.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lyrics kept on disk, one JSON file per video, so a song's lyrics show instantly after a restart
 * (the phone keeps them in its Room database). Stores the provider's answer before playback
 * alignment; the repository aligns it to the stream when loading.
 */
class LyricsDiskCache(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    init {
        // Discard expired responses on startup, including tracks that are never played again.
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                if (json.optString("provider") == LyricsProvider.SPICY_LYRICS.name &&
                    com.auralis.music.data.network.LyricsCachePolicy.isSpicyTimestampExpired(
                        json.optLong("fetchedAtMs").takeIf { it > 0 }, clock())) file.delete()
            }
        }
    }

    fun read(videoId: String): LyricsData? {
        val file = fileFor(videoId)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            if (json.optInt("version") != VERSION) null else decode(json).takeUnless { data ->
                com.auralis.music.data.network.LyricsCachePolicy.isExpired(data, clock()).also { expired -> if (expired) file.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable lyrics cache ${file.name}: ${e.message}")
            null
        }
    }

    fun write(videoId: String, lyrics: LyricsData) {
        try {
            if (com.auralis.music.data.network.LyricsCachePolicy.isExpired(lyrics, clock())) {
                fileFor(videoId).delete()
                return
            }
            dir.mkdirs()
            val file = fileFor(videoId)
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(encode(lyrics).put("version", VERSION).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save lyrics for $videoId: ${e.message}")
        }
    }

    private fun fileFor(videoId: String): File =
        File(dir, videoId.lowercase().replace(Regex("[^a-z0-9_-]"), "_") + ".json")

    companion object {
        private const val TAG = "LyricsDiskCache"
        /** Bump when the stored shape or the meaning of a field changes. */
        private const val VERSION = 2

        private fun encodeWord(w: LyricWord): JSONObject = JSONObject().apply {
            put("word", w.word); put("time", w.time)
            putOpt("duration", w.duration); put("isBackground", w.isBackground)
            w.syllables?.let { parts -> put("syllables", JSONArray().apply { parts.forEach { put(encodeWord(it)) } }) }
        }

        private fun decodeWord(w: JSONObject): LyricWord = LyricWord(
            word = w.getString("word"), time = w.getLong("time"),
            duration = w.optLongOrNull("duration"), isBackground = w.optBoolean("isBackground"),
            syllables = w.optJSONArray("syllables")?.let { parts -> List(parts.length()) { decodeWord(parts.getJSONObject(it)) } }
        )

        fun encode(data: LyricsData): JSONObject = JSONObject().apply {
            put("syncType", data.syncType.name)
            put("provider", data.provider.name)
            put("credits", com.auralis.music.data.parser.SpicyLyricsParser.encodeCredits(data.credits))
            putOpt("spotifyTrackId", data.spotifyTrackId)
            putOpt("fetchedAtMs", data.fetchedAtMs)
            putOpt("plainLyrics", data.plainLyrics)
            putOpt("translatedPlainLyrics", data.translatedPlainLyrics)
            putOpt("translatedLanguage", data.translatedLanguage)
            putOpt("trackName", data.trackName)
            putOpt("artistName", data.artistName)
            putOpt("durationMs", data.durationMs)
            putOpt("leadingSilenceMs", data.leadingSilenceMs)
            put("isExactVideoMatch", data.isExactVideoMatch)
            putOpt("matchedVideoId", data.matchedVideoId)
            put("appliedOffsetMs", data.appliedOffsetMs)
            put("lines", JSONArray().apply {
                data.lines.forEach { line ->
                    put(JSONObject().apply {
                        put("time", line.time)
                        put("text", line.text)
                        putOpt("translatedText", line.translatedText)
                        put("isInstrumental", line.isInstrumental)
                        put("isBackground", line.isBackground)
                        putOpt("endTime", line.endTime)
                        putOpt("agent", line.agent)
                        line.words?.let { words ->
                            put("words", JSONArray().apply {
                                words.forEach { w ->
                                    put(encodeWord(w))
                                }
                            })
                        }
                    })
                }
            })
        }

        fun decode(json: JSONObject): LyricsData {
            val lines = json.getJSONArray("lines")
            return LyricsData(
                syncType = SyncType.valueOf(json.getString("syncType")),
                provider = LyricsProvider.valueOf(json.getString("provider")),
                credits = com.auralis.music.data.parser.SpicyLyricsParser.decodeCredits(json.optJSONArray("credits")),
                spotifyTrackId = json.optStringOrNull("spotifyTrackId"),
                fetchedAtMs = json.optLongOrNull("fetchedAtMs"),
                plainLyrics = json.optStringOrNull("plainLyrics"),
                translatedPlainLyrics = json.optStringOrNull("translatedPlainLyrics"),
                translatedLanguage = json.optStringOrNull("translatedLanguage"),
                trackName = json.optStringOrNull("trackName"),
                artistName = json.optStringOrNull("artistName"),
                durationMs = json.optLongOrNull("durationMs"),
                leadingSilenceMs = json.optLongOrNull("leadingSilenceMs"),
                isExactVideoMatch = json.optBoolean("isExactVideoMatch"),
                matchedVideoId = json.optStringOrNull("matchedVideoId"),
                appliedOffsetMs = json.optLong("appliedOffsetMs"),
                lines = List(lines.length()) { i ->
                    val l = lines.getJSONObject(i)
                    LyricLine(
                        time = l.getLong("time"),
                        text = l.getString("text"),
                        translatedText = l.optStringOrNull("translatedText"),
                        isInstrumental = l.optBoolean("isInstrumental"),
                        isBackground = l.optBoolean("isBackground"),
                        endTime = l.optLongOrNull("endTime"),
                        agent = l.optStringOrNull("agent"),
                        words = l.optJSONArray("words")?.let { words ->
                            List(words.length()) { j ->
                                val w = words.getJSONObject(j)
                                decodeWord(w)
                            }
                        }
                    )
                }
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
        private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    }
}
