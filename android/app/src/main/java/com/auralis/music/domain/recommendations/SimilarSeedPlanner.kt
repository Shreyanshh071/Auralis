package com.auralis.music.domain.recommendations

import com.auralis.music.domain.model.Track

/**
 * Chooses the seeds for the Home "Similar to …" shelves.
 *
 * A track's `artist` is its full credit line ("Pritam, Arijit Singh & Antara Mitra"), so using it
 * directly produced shelves such as "Similar to Atif Aslam, Shreya Ghoshal, Sachin Gupta, Sameer
 * Anjaan" and never one for the artist the user actually listens to most.
 */
object SimilarSeedPlanner {

    private val CREDIT_SEPARATORS = Regex(
        """\s*(?:,|;|/|&|\+|\s+x\s+|\s+(?:feat\.?|ft\.?|featuring|with|and)\s+)\s*""",
        RegexOption.IGNORE_CASE
    )

    /** Individual artist names from a credit line, original casing kept, order preserved. */
    fun splitArtistCredit(credit: String?): List<String> {
        if (credit.isNullOrBlank()) return emptyList()
        return credit.split(CREDIT_SEPARATORS)
            .map { it.trim().trim('.', '-', '(', ')', '[', ']') }
            .filter { it.isNotBlank() && !TrackDeduplicator.isInvalidArtistName(it) }
            .distinctBy { it.lowercase() }
    }

    /**
     * Individual artists ranked by how many of the user's tracks credit them (history counts once
     * per play entry, top-played adds weight); ties keep the most recent first.
     */
    fun rankArtistSeeds(history: List<Track>, topPlayed: List<Track> = emptyList(), limit: Int = 4): List<String> {
        val score = LinkedHashMap<String, Int>()
        val display = HashMap<String, String>()
        fun add(tracks: List<Track>, weight: Int) {
            for (track in tracks) {
                for (name in splitArtistCredit(track.artist)) {
                    val key = name.lowercase()
                    display.putIfAbsent(key, name)
                    score[key] = (score[key] ?: 0) + weight
                }
            }
        }
        add(history, 1)
        add(topPlayed, 1)
        val firstSeen = score.keys.withIndex().associate { (i, k) -> k to i }
        return score.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { firstSeen.getValue(it.key) })
            .take(limit)
            .map { display.getValue(it.key) }
    }

    /** Same artist regardless of case, spacing and punctuation ("Shankar-Ehsaan-Loy" = "Shankar Ehsaan Loy"). */
    fun isSameArtist(a: String?, b: String?): Boolean {
        fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        val na = norm(a)
        return na.isNotEmpty() && na == norm(b)
    }

    /** Alternates the two shelf kinds (a, b, a, b, …) so neither is buried under the other. */
    fun <T> interleave(first: List<T>, second: List<T>): List<T> {
        val out = ArrayList<T>(first.size + second.size)
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.let(out::add)
            second.getOrNull(i)?.let(out::add)
        }
        return out
    }
}
