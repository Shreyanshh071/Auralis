package com.auralis.music.domain.recommendations

import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.domain.model.Track

data class SongFingerprint(
    val id: String,
    val normalizedTitle: String,
    val normalizedCoreTitle: String,
    val normalizedArtist: String,
    val cleanedTitle: String,
    val thumbnail: String
)

/**
 * Intelligent song deduplication engine preventing identical tracks
 * (with different YouTube IDs, bracket noise, or artist variations)
 * from being recommended multiple times on Speed Dial and Home feeds.
 */
object TrackDeduplicator {

    private val INVALID_ARTISTS = hashSetOf(
        "shreyanshh", "shreyansh", "youtube music", "youtube", "artist",
        "unknown artist", "various artists", "various", "topic", "guest listener", "admin"
    )

    private val MOVIE_ATTRIBUTION_REGEX = Regex("""(?i)\s*(?:[\(\[\{/\-]\s*from\s+[^)\]\}]+[\)\]\}]?|-\s*from\s+.*$)""")

    fun isInvalidArtistName(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return true
        val lower = artist.trim().lowercase()
        return lower in INVALID_ARTISTS || lower.startsWith("user_") || lower.startsWith("yt_")
    }

    /**
     * Extracts a normalized fingerprint for a track using TitleCleaner.
     */
    fun getSongFingerprint(track: Track): SongFingerprint {
        val (rawArtist, rawTitle) = TitleCleaner.splitArtistAndTitle(track.title, track.artist)
        val cleaned = TitleCleaner.cleanTitle(rawTitle).ifBlank { rawTitle.trim() }
        val normTitle = cleaned.lowercase().replace(Regex("""[^\p{L}\p{M}0-9]"""), "")

        val coreTitle = cleaned.replace(MOVIE_ATTRIBUTION_REGEX, "").trim().ifBlank { cleaned }
        val normCoreTitle = coreTitle.lowercase().replace(Regex("""[^\p{L}\p{M}0-9]"""), "")

        val cleanedArt = TitleCleaner.cleanArtist(rawArtist).ifBlank { rawArtist.trim() }
        val normArtist = if (isInvalidArtistName(cleanedArt)) "" else {
            cleanedArt.lowercase().replace(Regex("""[^\p{L}\p{M}0-9]"""), "")
        }

        return SongFingerprint(
            id = track.id.trim(),
            normalizedTitle = normTitle,
            normalizedCoreTitle = normCoreTitle,
            normalizedArtist = normArtist,
            cleanedTitle = cleaned,
            thumbnail = track.thumbnail.trim()
        )
    }

    /**
     * Determines whether two tracks represent the same song.
     */
    fun isDuplicateSong(a: SongFingerprint, b: SongFingerprint): Boolean {
        // 1. Direct ID match
        if (a.id.isNotBlank() && b.id.isNotBlank() && a.id == b.id) {
            return true
        }

        // 2. Exact thumbnail match (YouTube audio & video uploads often share exact thumb url)
        if (a.thumbnail.isNotBlank() && b.thumbnail.isNotBlank() && a.thumbnail == b.thumbnail) {
            if (a.normalizedTitle == b.normalizedTitle ||
                a.normalizedCoreTitle == b.normalizedCoreTitle ||
                a.normalizedTitle.contains(b.normalizedTitle) ||
                b.normalizedTitle.contains(a.normalizedTitle)
            ) {
                return true
            }
        }

        // Check title match: exact normalized title, core title match, or cleaned title match
        val titlesMatch = (a.normalizedTitle.isNotBlank() && a.normalizedTitle == b.normalizedTitle) ||
                (a.normalizedCoreTitle.isNotBlank() && a.normalizedCoreTitle == b.normalizedCoreTitle) ||
                a.cleanedTitle.equals(b.cleanedTitle, ignoreCase = true)

        if (!titlesMatch) {
            return false
        }

        // 3. If normalized titles match:
        // If either artist is blank or generic (unknown artist, topic, etc.)
        if (a.normalizedArtist.isBlank() || b.normalizedArtist.isBlank()) {
            return true
        }

        // Exact artist match
        if (a.normalizedArtist == b.normalizedArtist) {
            return true
        }

        // Substring / featuring artist match (e.g. "TV Girl" vs "TV Girl, Maddie Acid")
        if (a.normalizedArtist.contains(b.normalizedArtist) || b.normalizedArtist.contains(a.normalizedArtist)) {
            return true
        }

        // 4. Tiles on Speed Dial only display the song title without artist name.
        // If two cards have the exact same cleaned title (e.g. "Lovers Rock"), displaying
        // both on Speed Dial looks like a duplicate bug to the user.
        if (a.cleanedTitle.equals(b.cleanedTitle, ignoreCase = true)) {
            return true
        }

        return false
    }

    /**
     * Convenience method comparing two Track objects directly.
     */
    fun isDuplicateTrack(a: Track, b: Track): Boolean {
        return isDuplicateSong(getSongFingerprint(a), getSongFingerprint(b))
    }

    /**
     * Deduplicates a list of tracks so that no two tracks represent the same song.
     * When a duplicate is encountered, the earlier track is preserved and enriched
     * with better metadata (such as non-blank thumbnail, valid artist, or duration).
     */
    fun deduplicateTracks(tracks: List<Track>): List<Track> {
        val uniqueTracks = mutableListOf<Track>()
        val fingerprints = mutableListOf<SongFingerprint>()

        for (track in tracks) {
            if (track.title.isBlank() && track.id.isBlank()) continue

            val fp = getSongFingerprint(track)
            val existingIndex = fingerprints.indexOfFirst { isDuplicateSong(it, fp) }

            if (existingIndex == -1) {
                val cleanTitle = fp.cleanedTitle.ifBlank { track.title }
                uniqueTracks.add(track.copy(title = cleanTitle))
                fingerprints.add(fp)
            } else {
                // Enrich existing track with better metadata if available
                val existing = uniqueTracks[existingIndex]
                var updated = existing
                if (updated.thumbnail.isBlank() && track.thumbnail.isNotBlank()) {
                    updated = updated.copy(thumbnail = track.thumbnail)
                }
                if (isInvalidArtistName(updated.artist) && !isInvalidArtistName(track.artist)) {
                    updated = updated.copy(artist = track.artist)
                }
                if (updated.duration <= 0 && track.duration > 0) {
                    updated = updated.copy(duration = track.duration)
                }
                if (updated !== existing) {
                    uniqueTracks[existingIndex] = updated
                }
            }
        }
        return uniqueTracks
    }
}
