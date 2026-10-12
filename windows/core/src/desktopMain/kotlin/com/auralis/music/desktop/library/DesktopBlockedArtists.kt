package com.auralis.music.desktop.library

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One blocked artist: the channel id when the blocked row linked one, and the name. */
data class BlockedArtist(val id: String?, val name: String) {
    /** The handle for unblocking: the channel id when there is one, else the name. */
    val key: String get() = id ?: name
}

/**
 * Artists the listener never wants recommended (Li Music's "Block artist"). They are left out of
 * Home, Familiar Artists and radio; albums and playlists opened by hand still play everything.
 *
 * Matched by channel id or by exact (trimmed, lowercased) name, never fuzzily: a loose match would
 * hide a real artist, which is worse than one leaking through.
 */
object DesktopBlockedArtists {
    private val file by lazy { File(com.auralis.music.util.desktopAppDataDir(), "blocked-artists.tsv") }
    private val mutable = MutableStateFlow(load())
    val artists: StateFlow<List<BlockedArtist>> = mutable.asStateFlow()

    private fun norm(value: String) = value.trim().lowercase()

    private fun load(): List<BlockedArtist> = runCatching {
        file.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2 || parts[1].isBlank()) null else BlockedArtist(parts[0].ifBlank { null }, parts[1])
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<BlockedArtist>) {
        mutable.value = list
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(list.joinToString("") { "${it.id.orEmpty()}\t${it.name.replace('\t', ' ').replace('\n', ' ')}\n" })
        }
    }

    /** Blocks an artist; an entry with a channel id replaces a name-only one for the same name. */
    fun block(id: String?, name: String) {
        val cleanId = id?.takeIf { it.isNotBlank() }
        val current = mutable.value
        if (current.any { (cleanId != null && it.id == cleanId) || (it.id == null && cleanId == null && norm(it.name) == norm(name)) }) return
        save(current.filterNot { cleanId != null && it.id == null && norm(it.name) == norm(name) } + BlockedArtist(cleanId, name.trim()))
    }

    fun unblock(key: String) = save(mutable.value.filterNot { it.key == key || (it.id == null && norm(it.name) == norm(key)) })

    /** Whether this artist (by channel id or exact name) is blocked. */
    fun blocksArtist(id: String?, name: String, list: List<BlockedArtist> = mutable.value): Boolean =
        list.isNotEmpty() && list.any { (id != null && it.id == id) || norm(it.name) == norm(name) }

    /** Whether any credited artist on the track is blocked (byline split at ", ", " & " and "feat."). */
    fun blocksTrack(track: Track, list: List<BlockedArtist> = mutable.value): Boolean {
        if (list.isEmpty()) return false
        val names = list.map { norm(it.name) }.toSet()
        if (norm(track.artist) in names) return true
        return track.artist.split(Regex("""\s*(?:,|&| feat\.? | ft\.? )\s*""", RegexOption.IGNORE_CASE))
            .any { norm(it.removeSuffix(" - Topic")) in names }
    }
}
