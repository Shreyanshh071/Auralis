package com.auralis.music.data.network

import com.auralis.music.data.network.provider.LyricsSearchQuery
import kotlin.math.abs

/** Spotify metadata, independent of the service supplying playback. Duration is the recording's
 * length, never the last lyric timestamp (which can precede a long instrumental outro). */
data class SpicyRecording(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val album: String? = null,
    val isrc: String? = null
)

object SpicyRecordingMatcher {
    fun spotifyId(value: String?): String? {
        val raw = value?.trim().orEmpty()
        val id = when {
            raw.startsWith("spotify:track:") -> raw.removePrefix("spotify:track:")
            raw.startsWith("sp_") -> raw.removePrefix("sp_")
            raw.startsWith("https://open.spotify.com/track/") -> raw.substringAfter("/track/").substringBefore('?')
            else -> raw
        }
        return id.takeIf { Regex("[A-Za-z0-9]{22}").matches(it) }
    }

    fun matches(query: LyricsSearchQuery, candidate: SpicyRecording): Boolean {
        if (spotifyId(candidate.id) == null) return false
        val duration = query.durationMs?.takeIf { it > 0 } ?: query.durationSec?.times(1000)
        // Missing duration is insufficient evidence for transferring timestamps between services.
        if (duration == null || duration <= 0 || candidate.durationMs <= 0 ||
            abs(duration - candidate.durationMs) > 2500) return false
        return matchesIdentity(query, candidate)
    }

    /** A lookup hint only. Call matches() with measured metadata before accepting timestamps. */
    fun matchesIdentity(query: LyricsSearchQuery, candidate: SpicyRecording): Boolean {
        if (spotifyId(candidate.id) == null) return false
        val rawTitle = query.recordingTitle ?: query.title
        val targetVersion = versions(rawTitle)
        if (targetVersion != versions(candidate.title)) return false
        if (!query.isrc.isNullOrBlank() && !candidate.isrc.isNullOrBlank() &&
            !query.isrc.equals(candidate.isrc, ignoreCase = true)) return false
        val title = TitleCleaner.cleanCoreSongTitle(query.title)
        val otherTitle = TitleCleaner.cleanCoreSongTitle(candidate.title)
        val titleTokens = tokens(title)
        val otherTokens = tokens(otherTitle)
        if (titleTokens.isEmpty() || otherTokens.isEmpty() ||
            2.0 * titleTokens.intersect(otherTokens).size / (titleTokens.size + otherTokens.size) < 0.9) return false
        val artists = artists(query.artist)
        val otherArtists = artists(candidate.artist)
        return artists.isNotEmpty() && otherArtists.isNotEmpty() && artists.any { it in otherArtists }
    }

    // Token boundaries avoid classifying titles such as "Slow Dancing" or "Live Forever" by
    // substring. Version labels are retained separately from the cascade's cleaned search title.
    private fun tokens(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        .split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

    private fun artists(text: String) = TitleCleaner.cleanArtist(text)
        .split(Regex("(?i)[,&/|]|\\s+(?:feat\\.?|ft\\.?|and|with)\\s+"))
        .map { tokens(it).joinToString(" ") }.filter { it.isNotBlank() }.toSet()

    private fun versions(title: String): Set<String> {
        val version = Regex("(?i)\\b(live|concert|acoustic|unplugged|instrumental|karaoke|remix|slowed|reverb|nightcore|extended|radio\\s+(?:edit|mix)|sped\\s*up|speed\\s*up|clean|explicit|remaster(?:ed)?)\\b")
        return version.findAll(title).map { it.value.lowercase().replace(Regex("\\s+"), " ") }.toSet()
    }
}
