package com.auralis.music.desktop.playback

import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlin.math.abs

/**
 * When a row from another catalog (JioSaavn, SoundCloud) is the same recording as the song in the
 * queue, so it can stand in for YouTube's stream at the same position. Only the exact recording
 * qualifies: a remix, live take or a different cut of the same title never does.
 */
internal object RecordingMatch {
    /** The same master on two services is 0-2 s apart (rounding). */
    const val LENGTH_TOLERANCE_SEC = 2L

    /**
     * Lengths some YouTube pages fill in when they show none (a top-result card's 200 s, an album
     * or playlist page's 210 s). A song that really is that long still matches on the length path.
     */
    val PLACEHOLDER_LENGTHS = setOf(200L, 210L)

    private val ARTIST_SEPARATOR = Regex("""(?i)[,&/+]|\b(?:feat\.?|ft\.?|featuring|with|and|x|vs\.?)\s""")

    /**
     * Everyone the song credits, as normalized names: "Vishal Bhatt & Doli Rani" and
     * "Vishal Bhatt, Doli Rani" are the same two people. Features named in the title count too.
     */
    fun artistNames(track: Track): Set<String> {
        val artist = track.artist.takeUnless { it.equals("Spotify Artist", ignoreCase = true) }.orEmpty()
        return (listOf(artist) + SearchQueryMatcher.featuredArtists(track))
            .flatMap { it.split(ARTIST_SEPARATOR) }
            .map { SearchQueryMatcher.normalize(TitleCleaner.cleanArtist(it)) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** The first of [candidates] that is [target]'s recording, or null. */
    fun <T> pick(target: Track, candidates: List<T>, trackOf: (T) -> Track): T? = matches(target, candidates, trackOf).firstOrNull()

    /** Every one of [candidates] that is [target]'s recording, in their order. */
    fun <T> matches(target: Track, candidates: List<T>, trackOf: (T) -> Track): List<T> {
        if (target.duration > 0) {
            candidates.filter { sameRecordingOfKnownLength(target, trackOf(it)) }.takeIf { it.isNotEmpty() }?.let { return it }
            if (target.duration !in PLACEHOLDER_LENGTHS) return emptyList()
        }
        return matchesOfUnknownLength(target, candidates, trackOf)
    }

    /** Same title (versions included), a related artist, and the same length within 2 s. */
    fun sameRecordingOfKnownLength(target: Track, candidate: Track): Boolean {
        if (target.duration <= 0 || candidate.duration <= 0 || candidate.artist.isBlank()) return false
        if (abs(candidate.duration - target.duration) > LENGTH_TOLERANCE_SEC) return false
        val title = SearchQueryMatcher.baseTitle(TitleCleaner.cleanTitle(target.title))
        if (title.isBlank() || SearchQueryMatcher.baseTitle(TitleCleaner.cleanTitle(candidate.title)) != title) return false
        return SearchQueryMatcher.scoreTrackCandidate(target, candidate) >= 40.0
    }

    /**
     * Without a length to compare: the exact title by exactly the credited artists, and every such
     * row must agree on one runtime (the same release under several catalog IDs). Rows from the
     * song's own album (exactly, then ignoring edition labels) are preferred when there are any.
     */
    fun <T> matchesOfUnknownLength(target: Track, candidates: List<T>, trackOf: (T) -> Track): List<T> {
        val unknown = target.copy(duration = 0L)
        val title = exactTitle(target.title)
        val artists = artistNames(target)
        if (title.isBlank() || artists.isEmpty()) return emptyList()
        val rows = candidates.filter { row ->
            val match = trackOf(row)
            match.duration > 0 && exactTitle(match.title) == title && artistNames(match) == artists &&
                SearchQueryMatcher.recordingMismatch(unknown, match) == null
        }
        if (rows.isEmpty()) return emptyList()
        val album = SearchQueryMatcher.normalize(target.album.orEmpty())
        val edition = SearchQueryMatcher.albumIdentity(target.album)
        fun rank(row: T): Int {
            val other = trackOf(row).album
            return when {
                album.isNotBlank() && SearchQueryMatcher.normalize(other.orEmpty()) == album -> 0
                edition.isNotBlank() && SearchQueryMatcher.albumIdentity(other) == edition -> 1
                else -> 2
            }
        }
        val best = rows.minOf(::rank)
        val pool = rows.filter { rank(it) == best }
        val lengths = pool.map { trackOf(it).duration }
        return if (lengths.max() - lengths.min() > 1L) emptyList() else pool
    }

    private fun exactTitle(title: String) = SearchQueryMatcher.normalize(TitleCleaner.cleanTitle(title))
}
