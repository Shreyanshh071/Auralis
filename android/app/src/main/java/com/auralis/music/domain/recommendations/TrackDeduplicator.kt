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

    private val JUNK_ARTIST_SUBSTRINGS = listOf(
        "rain therapy", "rain sound", "rain sounds", "sleep therapy", "sleep sound",
        "sleep sounds", "sound therapy", "white noise", "nature sounds", "nature sound",
        "relaxing rain", "ambient rain", "binaural beat", "binaural beats", "deep sleep",
        "calming sound", "soothing sound", "noise therapy", "sleep music therapy",
        "meditation sounds", "relaxing sounds", "ambient sounds"
    )

    fun isInvalidArtistName(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return true
        val lower = artist.trim().lowercase()
        if (lower in INVALID_ARTISTS || lower.startsWith("user_") || lower.startsWith("yt_")) return true
        return JUNK_ARTIST_SUBSTRINGS.any { lower.contains(it) }
    }

    fun isJunkOrNoiseTrack(track: Track): Boolean {
        if (isInvalidArtistName(track.artist)) return true
        val lowerTitle = track.title.trim().lowercase()
        val junkTitlePhrases = listOf(
            "rain therapy", "rain sounds", "rain sound", "rain for sleeping", "rain to sleep",
            "rain and thunder", "rain & thunder", "sleep therapy", "sound therapy", "white noise",
            "binaural beats", "deep sleep", "nature sounds", "nature sound", "relaxing rain",
            "calming rain", "10 hours of rain", "8 hours of rain", "3 hours of rain",
            "hours of rain", "sleep sounds", "sleep sound", "noise for sleep"
        )
        return junkTitlePhrases.any { lowerTitle.contains(it) }
    }

    private val MOVIE_ATTRIBUTION_REGEX = Regex("""(?i)\s*(?:[\(\[\{/\-]\s*from\s+[^)\]\}]+[\)\]\}]?|-\s*from\s+.*$)""")

    private val VERSION_AND_REMIX_REGEX = Regex(
        """(?i)[\(\[\{][^)\]\}]*(?:remix|mix|edit|slowed|reverb|sped\s*up|speed\s*up|phonk|cover|acoustic|live|instrumental|vip|dub|extended|radio\s*edit|version|feat\.?|ft\.?)[^)\]\}]*[\)\]\}]"""
    )
    private val ALL_BRACKETS_REGEX = Regex("""[\(\[\{][^)\]\}]*[\)\]\}]""")

    /**
     * Extracts a bare base song title stripped of all versions, remix tags, slowed/reverb tags, and brackets
     * to eliminate all alternate cuts and duplicate versions of the same song from queues.
     */
    fun extractBaseSongTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return ""
        var t = TitleCleaner.cleanTitle(rawTitle).ifBlank { rawTitle.trim() }
        t = VERSION_AND_REMIX_REGEX.replace(t, " ")
        t = ALL_BRACKETS_REGEX.replace(t, " ")
        t = t.replace(MOVIE_ATTRIBUTION_REGEX, " ")
        return t.lowercase().replace(Regex("""[^\p{L}\p{M}0-9]"""), "")
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

        return false
    }

    fun isVideoOrBloatedTrack(track: Track): Boolean {
        val lower = track.title.lowercase()
        return lower.contains("official video") ||
                lower.contains("music video") ||
                lower.contains("official music video") ||
                lower.contains("(video)") ||
                lower.contains("[video]") ||
                lower.contains("official visualizer") ||
                lower.contains("lyric video")
    }

    /**
     * Determines whether candidate is a higher quality studio release than current (e.g. Studio Audio Track vs Music Video).
     */
    fun isBetterQualityTrack(candidate: Track, current: Track): Boolean {
        val candIsVideo = isVideoOrBloatedTrack(candidate)
        val currIsVideo = isVideoOrBloatedTrack(current)
        if (!candIsVideo && currIsVideo) return true
        if (candIsVideo && !currIsVideo) return false

        val candHasAlbum = !candidate.album.isNullOrBlank()
        val currHasAlbum = !current.album.isNullOrBlank()
        if (candHasAlbum && !currHasAlbum) return true
        if (!candHasAlbum && currHasAlbum) return false

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
     * When a duplicate is encountered, studio album audio tracks are strictly prioritized
     * over music video versions and extended video takes.
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
                val existing = uniqueTracks[existingIndex]
                if (isBetterQualityTrack(track, existing)) {
                    // Studio track replaces lower-quality music video
                    val cleanTitle = fp.cleanedTitle.ifBlank { track.title }
                    val updated = track.copy(
                        title = cleanTitle,
                        thumbnail = if (track.thumbnail.isNotBlank()) track.thumbnail else existing.thumbnail,
                        artist = if (!isInvalidArtistName(track.artist)) track.artist else existing.artist
                    )
                    uniqueTracks[existingIndex] = updated
                    fingerprints[existingIndex] = fp
                } else {
                    // Enrich existing track with better metadata if available
                    var updated = existing
                    if (updated.thumbnail.isBlank() && track.thumbnail.isNotBlank()) {
                        updated = updated.copy(thumbnail = track.thumbnail)
                    }
                    if (isInvalidArtistName(updated.artist) && !isInvalidArtistName(track.artist)) {
                        updated = updated.copy(artist = track.artist)
                    }
                    if (updated.album.isNullOrBlank() && !track.album.isNullOrBlank()) {
                        updated = updated.copy(album = track.album)
                    }
                    if (updated.duration <= 0 && track.duration > 0) {
                        updated = updated.copy(duration = track.duration)
                    }
                    if (updated !== existing) {
                        uniqueTracks[existingIndex] = updated
                    }
                }
            }
        }
        return uniqueTracks
    }
}
