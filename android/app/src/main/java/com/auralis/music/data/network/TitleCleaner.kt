package com.auralis.music.data.network

object TitleCleaner {

    private val BRACKET_NOISE_REGEX = Regex(
        """(?i)[\(\[\{][^)\]\}]*(?:official|music\s+video|lyric\s+video|lyrics|audio|video|visualizer|remaster|hd|4k|hq|prod\.)[^)\]\}]*[\)\]\}]"""
    )

    private val MULTI_SPACE = Regex("""\s+""")

    /**
     * Cleans extraneous YouTube title noise while preserving real track names and artists.
     */
    fun cleanTitle(rawTitle: String): String {
        var title = rawTitle.trim()

        // Strip bracketed noise phrases
        title = BRACKET_NOISE_REGEX.replace(title, "")

        // Clean double quotes / single quotes at edges
        title = title.trim(' ', '"', '\'', '-', '|')
        title = MULTI_SPACE.replace(title, " ").trim()

        return if (title.isBlank()) rawTitle.trim() else title
    }

    /**
     * Splits a raw "Artist - Song Title" YouTube title into pair of (artist, title).
     */
    fun splitArtistAndTitle(rawTitle: String, fallbackArtist: String? = null): Pair<String, String> {
        val cleaned = cleanTitle(rawTitle)

        // Check for common artist - title separator (" - ")
        if (cleaned.contains(" - ")) {
            val parts = cleaned.split(" - ", limit = 2)
            val artist = parts[0].trim()
            val song = parts[1].trim()
            if (artist.isNotBlank() && song.isNotBlank()) {
                return Pair(artist, song)
            }
        }

        // Check for " : "
        if (cleaned.contains(" : ")) {
            val parts = cleaned.split(" : ", limit = 2)
            val artist = parts[0].trim()
            val song = parts[1].trim()
            if (artist.isNotBlank() && song.isNotBlank()) {
                return Pair(artist, song)
            }
        }

        val artist = fallbackArtist?.trim()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist"
        return Pair(artist, cleaned)
    }
}
