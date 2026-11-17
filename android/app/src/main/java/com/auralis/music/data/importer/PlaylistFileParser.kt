package com.auralis.music.data.importer

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import org.json.JSONArray
import org.json.JSONObject

/** One playlist read from a file, before its songs are matched to playable audio. */
data class ParsedPlaylist(val title: String, val tracks: List<Track>, val description: String? = null)

/**
 * Reads playlist files from other apps and services: CSV and TSV (Exportify, TuneMyMusic, Soundiiz,
 * Auralis' own export...), M3U and M3U8, JSON (Auralis backups, Spotify data export, generic track
 * arrays) and plain text with one "Artist - Title" per line.
 *
 * Songs that carry a YouTube video id or link keep it and play as is. Everything else gets an
 * "sp_" placeholder id, the same convention as Spotify imports, so the importer's matcher finds the
 * official audio for it afterwards.
 */
object PlaylistFileParser {

    enum class Format { CSV, TSV, M3U, JSON, TEXT }

    fun detectFormat(fileName: String, text: String): Format {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "csv" -> Format.CSV
            "tsv", "tab" -> Format.TSV
            "m3u", "m3u8" -> Format.M3U
            "json" -> Format.JSON
            "txt" -> sniff(text)
            else -> sniff(text)
        }
    }

    private fun sniff(text: String): Format {
        val head = text.trimStart('\uFEFF').trimStart()
        val firstLine = head.lineSequence().firstOrNull().orEmpty()
        return when {
            head.startsWith("{") || head.startsWith("[") -> Format.JSON
            head.startsWith("#EXTM3U") -> Format.M3U
            firstLine.count { it == '\t' } >= 1 -> Format.TSV
            firstLine.count { it == ',' } >= 1 && headerIndex(splitDelimited(firstLine, ',')) != null -> Format.CSV
            else -> Format.TEXT
        }
    }

    /** All playlists in the file (JSON backups can hold many; other formats hold one). */
    fun parse(fileName: String, raw: String): List<ParsedPlaylist> {
        val text = raw.trimStart('\uFEFF')
        val fallbackTitle = fileName.substringBeforeLast('.').ifBlank { "Imported playlist" }
            .replace('_', ' ').trim()
        val playlists = when (detectFormat(fileName, text)) {
            Format.CSV -> listOf(parseDelimited(text, ',', fallbackTitle))
            Format.TSV -> listOf(parseDelimited(text, '\t', fallbackTitle))
            Format.M3U -> listOf(parseM3u(text, fallbackTitle))
            Format.JSON -> parseJson(text, fallbackTitle)
            Format.TEXT -> listOf(parseText(text, fallbackTitle))
        }
        return playlists.filter { it.tracks.isNotEmpty() }
    }

    // ── CSV / TSV ──────────────────────────────────────────────────────────────

    private data class Columns(
        val title: Int, val artist: Int?, val album: Int?, val duration: Int?, val durationMs: Boolean,
        val id: Int?, val url: Int?, val playlist: Int?
    )

    private fun norm(header: String) = header.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun headerIndex(cells: List<String>): Columns? {
        val keys = cells.map(::norm)
        fun find(vararg names: String): Int? = keys.indexOfFirst { it in names }.takeIf { it >= 0 }
        val title = find("title", "trackname", "track", "song", "songname", "name", "tracktitle", "songtitle") ?: return null
        val durationMsCol = find("durationms", "trackdurationms", "lengthms")
        return Columns(
            title = title,
            artist = find("artist", "artists", "artistname", "artistnames", "artistnamess", "creator", "performer", "albumartist"),
            album = find("album", "albumname", "albumtitle", "release"),
            duration = durationMsCol ?: find("duration", "length", "time", "durations", "durationseconds"),
            durationMs = durationMsCol != null,
            id = find("videoid", "youtubeid", "ytid", "id"),
            url = find("url", "link", "youtubeurl", "youtubelink", "uri", "trackurl"),
            playlist = find("playlist", "playlistname", "playlisttitle")
        )
    }

    private fun parseDelimited(text: String, delimiter: Char, fallbackTitle: String): ParsedPlaylist {
        val rows = readRows(text, delimiter).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return ParsedPlaylist(fallbackTitle, emptyList())
        val header = headerIndex(rows.first())
        val columns = header ?: Columns(0, rows.first().size.takeIf { it > 1 }?.let { 1 }, null, null, false, null, null, null)
        val body = if (header != null) rows.drop(1) else rows
        var playlistTitle: String? = null
        val tracks = body.mapIndexedNotNull { index, row ->
            fun cell(i: Int?) = i?.let { row.getOrNull(it)?.trim() }.orEmpty()
            val title = cell(columns.title)
            if (title.isBlank()) return@mapIndexedNotNull null
            if (playlistTitle == null) playlistTitle = cell(columns.playlist).ifBlank { null }
            val durationRaw = cell(columns.duration)
            buildTrack(
                title = title,
                artist = cell(columns.artist),
                album = cell(columns.album).ifBlank { null },
                durationSec = parseDuration(durationRaw, columns.durationMs),
                idHint = cell(columns.id).ifBlank { null } ?: cell(columns.url).ifBlank { null },
                index = index
            )
        }
        return ParsedPlaylist(playlistTitle ?: fallbackTitle, tracks)
    }

    /** RFC 4180-style reader: quoted fields, doubled quotes, and line breaks inside quotes. */
    private fun readRows(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' && (cell.isEmpty() || quoted) -> quoted = !quoted
                !quoted && c == delimiter -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    rows.add(row); row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }

    private fun splitDelimited(line: String, delimiter: Char) = readRows(line, delimiter).firstOrNull().orEmpty()

    // ── M3U / M3U8 ─────────────────────────────────────────────────────────────

    private fun parseM3u(text: String, fallbackTitle: String): ParsedPlaylist {
        var title = fallbackTitle
        var pendingInfo: Pair<Long, String>? = null
        val tracks = ArrayList<Track>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.isEmpty() || line == "#EXTM3U" -> Unit
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> title = line.substringAfter(':').trim().ifBlank { title }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val body = line.substringAfter(':')
                    val seconds = body.substringBefore(',').trim().substringBefore(' ').toLongOrNull() ?: 0L
                    pendingInfo = seconds.coerceAtLeast(0) to body.substringAfter(',', "").trim()
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    pendingInfo = null
                    val display = info?.second?.takeIf { it.isNotBlank() }
                        ?: line.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                            .let { java.net.URLDecoder.decode(it, "UTF-8") }
                    val (artist, songTitle) = splitArtistTitle(display)
                    if (songTitle.isNotBlank()) {
                        tracks += buildTrack(songTitle, artist, null, info?.first ?: 0L, line, tracks.size)
                    }
                }
            }
        }
        return ParsedPlaylist(title, tracks)
    }

    // ── JSON ───────────────────────────────────────────────────────────────────

    private val TRACK_LIST_KEYS = listOf("tracks", "items", "songs", "entries", "data", "trackList")

    private fun parseJson(text: String, fallbackTitle: String): List<ParsedPlaylist> {
        val trimmed = text.trim()
        return if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            // An array of playlists, or one playlist's tracks.
            val asPlaylists = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { o -> playlistFrom(o, null) } }
            if (asPlaylists.isNotEmpty() && asPlaylists.size == array.length()) asPlaylists
            else listOf(ParsedPlaylist(fallbackTitle, tracksFrom(array)))
        } else {
            val root = JSONObject(trimmed)
            val many = root.optJSONArray("playlists")
            if (many != null) {
                (0 until many.length()).mapNotNull { many.optJSONObject(it)?.let { o -> playlistFrom(o, null) } }
            } else {
                listOfNotNull(playlistFrom(root, fallbackTitle))
            }
        }
    }

    private fun playlistFrom(obj: JSONObject, fallbackTitle: String?): ParsedPlaylist? {
        val list = TRACK_LIST_KEYS.firstNotNullOfOrNull { key ->
            obj.optJSONArray(key) ?: obj.optJSONObject(key)?.let { inner -> TRACK_LIST_KEYS.firstNotNullOfOrNull { inner.optJSONArray(it) } }
        } ?: return null
        val title = listOf("title", "name", "playlistName").firstNotNullOfOrNull { obj.optString(it).takeIf(String::isNotBlank) }
            ?: fallbackTitle ?: return null
        return ParsedPlaylist(title, tracksFrom(list), obj.optString("description").takeIf { it.isNotBlank() })
    }

    private fun tracksFrom(array: JSONArray): List<Track> = (0 until array.length()).mapNotNull { i ->
        val outer = array.optJSONObject(i) ?: return@mapNotNull array.optString(i).takeIf { it.isNotBlank() }?.let { line ->
            val (artist, title) = splitArtistTitle(line)
            buildTrack(title, artist, null, 0L, null, i)
        }
        // Spotify's data export nests the song as { "track": { trackName, artistName, ... } }.
        val o = outer.optJSONObject("track") ?: outer
        val title = firstString(o, "title", "trackName", "name", "track", "song", "songName") ?: return@mapNotNull null
        val artist = firstString(o, "artist", "artistName", "artists", "creator", "author", "performer")
            ?: o.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") ?: arr.optString(it) }
                    .filter { it.isNotBlank() }.joinToString(", ")
            }.orEmpty()
        val album = firstString(o, "album", "albumName")
            ?: o.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() }
        val durationMs = o.optLong("duration_ms", o.optLong("durationMs", 0L))
        val duration = if (durationMs > 0) durationMs / 1000 else o.optLong("duration", 0L).let { if (it > 36_000) it / 1000 else it }
        val idHint = firstString(o, "videoId", "youtubeId", "id", "url", "link", "trackUri", "uri")
        buildTrack(title, artist, album, duration, idHint, i, o.optString("thumbnail").takeIf { it.startsWith("http") })
    }

    private fun firstString(o: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> o.opt(key)?.let { it as? String }?.trim()?.takeIf { it.isNotBlank() } }

    // ── Plain text ─────────────────────────────────────────────────────────────

    private fun parseText(text: String, fallbackTitle: String): ParsedPlaylist {
        val tracks = text.lineSequence()
            .map { it.trim().removePrefix("\u2022").trim().replace(Regex("^\\d+[.)]\\s*"), "") }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexedNotNull { i, line ->
                val (artist, title) = splitArtistTitle(line)
                if (title.isBlank()) null else buildTrack(title, artist, null, 0L, line, i)
            }
            .toList()
        return ParsedPlaylist(fallbackTitle, tracks)
    }

    // ── Shared ─────────────────────────────────────────────────────────────────

    /** "Artist - Title" (also en/em dashes); a line without a dash is just a title. */
    fun splitArtistTitle(display: String): Pair<String, String> {
        val match = Regex("\\s+[-\u2013\u2014]\\s+").find(display) ?: return "" to display.trim()
        return display.substring(0, match.range.first).trim() to display.substring(match.range.last + 1).trim()
    }

    private val YOUTUBE_ID = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})""")

    /** A YouTube video id from a raw id or any YouTube link, else null. */
    fun youtubeId(hint: String?): String? {
        if (hint.isNullOrBlank()) return null
        val h = hint.trim()
        YOUTUBE_ID.find(h)?.let { return it.groupValues[1] }
        return h.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
    }

    private fun parseDuration(raw: String, isMs: Boolean): Long {
        if (raw.isBlank()) return 0L
        if (':' in raw) {
            val parts = raw.split(':').mapNotNull { it.trim().toLongOrNull() }
            return parts.fold(0L) { acc, p -> acc * 60 + p }
        }
        val n = raw.toDoubleOrNull() ?: return 0L
        return if (isMs || n > 36_000) (n / 1000).toLong() else n.toLong()
    }

    private fun buildTrack(
        title: String,
        artist: String,
        album: String?,
        durationSec: Long,
        idHint: String?,
        index: Int,
        thumbnail: String? = null
    ): Track {
        val videoId = youtubeId(idHint)
        val id = videoId ?: "sp_file_" + Integer.toHexString("${artist.lowercase()}|${title.lowercase()}|$index".hashCode())
        return Track(
            id = id,
            title = title.trim(),
            artist = artist.trim().ifBlank { "Unknown artist" },
            album = album,
            duration = durationSec,
            thumbnail = thumbnail.orEmpty(),
            source = TrackSource.YOUTUBE
        )
    }
}
