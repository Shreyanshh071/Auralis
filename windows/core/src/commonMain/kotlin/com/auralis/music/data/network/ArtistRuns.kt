package com.auralis.music.data.network

import com.auralis.music.domain.model.TrackArtist
import com.auralis.music.domain.stats.ArtistCredits
import org.json.JSONArray

/**
 * Reads the individual artists out of a YouTube Music subtitle. YouTube sends the subtitle as
 * separate "runs": every artist is its own run linked to its channel, with plain ", " / " & "
 * runs between them and " • " between sections:
 *
 *   [Pritam →UC1] [", "] [Mohit Chauhan →UC2] [" & "] [Irshad Kamil →UC3] [" • "] [Rockstar →MPRE] ...
 *
 * so "Tyler, The Creator" arrives as one run and needs no guessing.
 */
object ArtistRuns {
    private val SEPARATOR_ONLY = Regex("""^[\s,;&•·/]*$|^\s*(?:feat\.?|ft\.?|featuring|x|and)\s*$""", RegexOption.IGNORE_CASE)
    private val EDGE_GLUE = Regex("""^[\s,;&/]+|[\s,;&/]+$""")

    private fun isArtistLink(run: org.json.JSONObject): Pair<Boolean, String?> {
        val nav = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return false to null
        val browseId = nav.optString("browseId").takeIf { it.isNotBlank() }
        val pageType = nav.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
        val artist = pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" ||
            (browseId != null && browseId.startsWith("UC"))
        return artist to browseId
    }

    /**
     * The artists in [runs]: the first " • " section that links an artist, piece by piece. Plain
     * text inside that section (an artist YouTube didn't link) is split on separators. Empty when
     * no section links an artist.
     */
    fun fromRuns(runs: JSONArray?): List<TrackArtist> {
        if (runs == null || runs.length() == 0) return emptyList()
        val sections = mutableListOf<MutableList<org.json.JSONObject>>(mutableListOf())
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            if (run.optString("text").trim() == "•") sections.add(mutableListOf()) else sections.last().add(run)
        }
        val section = sections.firstOrNull { s -> s.any { isArtistLink(it).first } } ?: return emptyList()
        val artists = mutableListOf<TrackArtist>()
        for (run in section) {
            val text = run.optString("text")
            val (linked, browseId) = isArtistLink(run)
            if (linked) {
                val name = ArtistCredits.display(text)
                if (name.isBlank()) continue
                // YouTube linked this whole name as one artist: remember it if it holds a separator.
                ArtistCredits.learn(name)
                artists += TrackArtist(name, browseId)
            } else {
                if (SEPARATOR_ONLY.matches(text)) continue
                val plain = text.replace(EDGE_GLUE, "")
                if (plain.isBlank()) continue
                ArtistCredits.split(plain).forEach { artists += TrackArtist(it) }
            }
        }
        return artists.distinctBy { ArtistCredits.key(it.name) ?: it.name }
    }

    /**
     * [fromRuns], and for a label upload (only the label is linked, or nothing is) the singers in
     * the raw, uncleaned [rawTitle] ("Song | Movie | Singer | T-Series"), which cleaning removes.
     */
    fun forItem(runs: JSONArray?, rawTitle: String): List<TrackArtist> {
        val linked = fromRuns(runs)
        val real = linked.filterNot { ArtistCredits.isLabel(it.name) }
        if (real.isNotEmpty()) return linked
        val fromTitle = ArtistCredits.fromTitle(rawTitle)
        return if (fromTitle.isNotEmpty()) fromTitle.map { TrackArtist(it) } else linked
    }
}
