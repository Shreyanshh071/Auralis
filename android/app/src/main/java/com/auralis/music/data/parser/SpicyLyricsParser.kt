package com.auralis.music.data.parser

import com.auralis.music.data.network.SpicyRecording
import com.auralis.music.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** The documented Spicy Lyrics v1 envelope. All times are provider seconds; no timing is guessed. */
object SpicyLyricsParser {
    fun parse(json: JSONObject, recording: SpicyRecording): LyricsData? {
        if (json.optInt("Status") != 200) return null
        val body = json.optJSONObject("Body") ?: return null
        if (body.optString("id") != recording.id) return null
        val credits = mutableListOf<LyricsCredit>()
        when (body.optString("source")) {
            "spicy_lyrics" -> {
                credits += LyricsCredit("Spicy Lyrics", "https://spicylyrics.org")
                val attribution = body.optJSONObject("UploadAttribution") ?: return null
                fun contributor(role: String): LyricsCredit? {
                    val person = attribution.optJSONObject(role) ?: return null
                    val name = person.optString("username").takeIf { it.isNotBlank() } ?: return null
                    val suppliedUrl = person.optString("url").trim()
                    val url = suppliedUrl.takeIf { value -> runCatching {
                        val parsed = java.net.URI(value)
                        parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null
                    }.getOrDefault(false) }.orEmpty()
                    return LyricsCredit("${if (role == "Uploader") "Uploaded" else "Made"} by $name", url)
                }
                credits += contributor("Uploader") ?: return null
                contributor("Maker")?.let(credits::add)
            }
            "apple_music" -> credits += LyricsCredit("Apple Music", "https://music.apple.com")
            "spotify" -> credits += LyricsCredit("Spotify", "https://open.spotify.com/track/${recording.id}")
            else -> credits += LyricsCredit("Unknown lyrics source", "")
        }
        val songwriters = body.optJSONArray("SongWriters")
        if (songwriters != null) {
            val names = (0 until songwriters.length()).mapNotNull { index ->
                (songwriters.opt(index) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            }.distinct()
            if (names.isNotEmpty()) credits += LyricsCredit("Written by ${names.joinToString(", ")}", "")
        }
        val lines = mutableListOf<LyricLine>()
        val type = body.optString("Type")
        val content = body.optJSONArray(if (type == "Static") "Lines" else "Content") ?: return null
        for (i in 0 until content.length()) {
            val line = content.optJSONObject(i) ?: return null
            if (type != "Static" && line.optString("Type") != "Vocal") continue
            val agent = if (line.optBoolean("OppositeAligned")) "v2" else "v1"
            when (type) {
                "Syllable" -> {
                    lines += group(line.optJSONObject("Lead") ?: return null, false, agent) ?: return null
                    val background = line.optJSONArray("Background")
                    if (background != null) for (j in 0 until background.length()) {
                        lines += group(background.optJSONObject(j) ?: return null, true, agent) ?: return null
                    }
                }
                "Line", "Static" -> {
                    val text = line.optString("Text").takeIf { it.isNotBlank() } ?: continue
                    val start = if (type == "Static") 0L else time(line, "StartTime") ?: return null
                    val end = time(line, "EndTime")
                    if (end != null && end < start) return null
                    lines += LyricLine(start, text, translatedText = line.textOrNull("TranslatedText"),
                        endTime = end, agent = if (type == "Static") null else agent)
                }
                else -> return null
            }
        }
        if (lines.isEmpty()) return null
        val sync = when (type) { "Syllable" -> SyncType.RICHSYNC; "Line" -> SyncType.LINE_SYNC; else -> SyncType.PLAIN }
        return LyricsData(syncType = sync, lines = lines.sortedBy { it.time },
            plainLyrics = if (sync == SyncType.PLAIN) lines.joinToString("\n") { it.text } else null,
            provider = LyricsProvider.SPICY_LYRICS, trackName = recording.title, artistName = recording.artist,
            durationMs = recording.durationMs.takeIf { it > 0 }, credits = credits, spotifyTrackId = recording.id)
    }

    private fun group(group: JSONObject, background: Boolean, agent: String): LyricLine? {
        val syllables = group.optJSONArray("Syllables") ?: return null
        val words = mutableListOf<LyricWord>()
        var continuesWrittenWord = false
        var previousSyllableStart: Long? = null
        for (i in 0 until syllables.length()) {
            val syllable = syllables.optJSONObject(i) ?: return null
            val text = syllable.optString("Text").takeIf { it.isNotEmpty() } ?: return null
            val start = time(syllable, "StartTime") ?: return null
            val end = time(syllable, "EndTime")
            if (end != null && end < start) return null
            if (previousSyllableStart?.let { start < it } == true) return null
            previousSyllableStart = start
            val space = if (i < syllables.length() - 1 && !syllable.optBoolean("IsPartOfWord")) " " else ""
            val piece = LyricWord(text + space, start, end?.minus(start), background)
            if (continuesWrittenWord && words.isNotEmpty()) {
                // IsPartOfWord is explicit source metadata. Keep it through text
                // repair instead of trying to recognize the word in a dictionary.
                val previous = words.removeAt(words.lastIndex)
                words += previous.copy(word = previous.word + piece.word,
                    duration = if (previous.endTime != null && piece.endTime != null)
                        maxOf(previous.endTime!!, piece.endTime!!) - previous.time else null,
                    syllables = (previous.syllables ?: listOf(previous)) + piece)
            } else words += piece
            continuesWrittenWord = syllable.optBoolean("IsPartOfWord")
        }
        if (words.isEmpty()) return null
        val start = time(group, "StartTime") ?: words.first().time
        val end = time(group, "EndTime")
        if (start > words.first().time || (end != null && end < (words.last().endTime ?: words.last().time))) return null
        return LyricLine(start, words.joinToString("") { it.word }, translatedText = group.textOrNull("TranslatedText"),
            words = words, isBackground = background, endTime = end, agent = agent)
    }

    private fun time(json: JSONObject, key: String): Long? {
        val seconds = (json.opt(key) as? Number)?.toDouble() ?: return null
        return seconds.takeIf { it.isFinite() && it >= 0 && it < 86400 }?.times(1000)?.roundToLong()
    }
    private fun JSONObject.textOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    fun encodeCredits(credits: List<LyricsCredit>): JSONArray = JSONArray().apply {
        credits.forEach { put(JSONObject().put("label", it.label).put("url", it.url)) }
    }
    fun decodeCredits(json: JSONArray?): List<LyricsCredit> = if (json == null) emptyList() else
        (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val label = item.textOrNull("label") ?: return@mapNotNull null
            val url = item.optString("url")
            if (url.isNotEmpty() && !url.startsWith("https://")) null else LyricsCredit(label, url)
        }
}
