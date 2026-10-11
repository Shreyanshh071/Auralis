package com.auralis.music.data.network

import com.auralis.music.domain.model.Track
import java.util.Locale

/** A search image is usable only when its result identifies the requested recording. */
object ArtworkIdentity {
    private val decoration = Regex("(?i)\\b(?:official (?:music )?video|official audio|lyrics?|visualizer|hd|hq|4k)\\b")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val creditSplit = Regex("(?i)\\s*(?:,|&|/|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b)\\s*")

    fun normalized(value: String): String = punctuation.replace(
        decoration.replace(value, "").lowercase(Locale.ROOT), " "
    ).trim().replace(Regex("\\s+"), " ")

    fun cacheKey(track: Track): String = listOf(
        track.source.name, track.id, normalized(track.title), normalized(track.artist),
        normalized(track.album.orEmpty()), track.duration.toString()
    ).joinToString("::")

    fun matches(requested: Track, candidate: Track, requireAlbum: Boolean = false): Boolean {
        val title = normalized(requested.title)
        val candidateTitle = normalized(candidate.title)
        if (title.isBlank() || title != candidateTitle) return false
        val credits = creditSplit.split(requested.artist).map(::normalized).filter { it.isNotBlank() }
        val candidateCredits = " ${normalized(candidate.artist + " " + candidate.title)} "
        if (credits.isEmpty() || credits.any { !candidateCredits.contains(" $it ") }) return false
        if (requested.duration > 0 && candidate.duration > 0 &&
            kotlin.math.abs(requested.duration - candidate.duration) > 12L) return false
        if (requireAlbum && !requested.album.isNullOrBlank() &&
            (candidate.album.isNullOrBlank() ||
                normalized(requested.album!!) != normalized(candidate.album!!))) return false
        return candidate.thumbnail.isNotBlank()
    }
}
