package com.auralis.music.data.network

import com.auralis.music.data.parser.IndicScriptNormalizer

object TitleCleaner {

    // Meaningful musical versions that MUST be preserved when matching lyrics
    private val VERSION_KEYWORDS = listOf(
        "remix", "mix", "club mix", "vip mix", "extended mix", "extended version", "extended",
        "acoustic", "acoustic version", "live", "live version", "live at", "live in",
        "instrumental", "unplugged", "orchestral", "piano version", "slowed + reverb",
        "slowed and reverb", "slowed", "sped up", "speed up", "radio edit",
        "cover", "re-recorded", "part 1", "part 2", "part 3", "vol 1", "vol 2"
    )

    // Extraneous YouTube video, label, resolution, and promotional bracket noise
    private val BRACKET_NOISE_REGEX = Regex(
        """(?i)[\(\[\{][^)\]\}]*(?:official\s*(?:music)?\s*(?:video|audio)|\b(?:video|audio)\b|music\s*video|lyric\s*video|lyrics|audio\s*song|video\s*song|full\s*video(?:\s*song)?|full\s*audio(?:\s*song)?|lyrical(?:\s*video)?|visualizer|remaster(?:ed)?|hd\s*video|4k|hq|prod\.?|ost|special\s*edition|exclusive|bhojpuri\s*video(?:\s*song)?|bhojpuri\s*song(?:\s*\d{4})?|new\s*song\s*\d{4}|hit\s*song)[^)\]\}]*[\)\]\}]"""
    )

    // Movie, soundtrack, and OST attribution noise (e.g. (From "Brahmastra"), - From "Movie", (OST))
    private val MOVIE_ATTRIBUTION_REGEX = Regex(
        """(?i)\s*[\(\[\{/\-]\s*(?:from\s+(?:the\s+)?(?:motion\s+picture\s+)?(?:soundtrack\s+)?["'“”‘’]?[^()\[\]{}]+["'“”‘’]?|original\s+motion\s+picture\s+soundtrack|soundtrack\s+version|ost)[\)\]\}]?"""
    )

    // Pipe separated label/artist channel spam (common in Indian and regional releases)
    private val PIPE_CHANNEL_SPAM_REGEX = Regex(
        """(?i)\|\s*(?:official\s*video|official\s*audio|t-series|zee\s*music\s*(?:company)?|speed\s*records|sony\s*music\s*(?:india)?|yrf|tips\s*official|bhojpuri\s*song|new\s*bhojpuri|pawan\s*singh|khesari\s*lal\s*yadav|shilpi\s*raj|wave\s*music|worldwide\s*records|dharmendra\s*chanchal|neha\s*raj)[^|]*"""
    )

    // Strips featuring artists inside brackets (e.g. (feat. Bruno Mars), [ft. DaBaby], (with Bruno Mars))
    private val FEAT_BRACKET_REGEX = Regex("""(?i)\s*[\(\[\{]\s*(?:feat\.?|ft\.?|featuring)\s+[^)\]\}]+[\)\]\}]""")
    private val WITH_ARTIST_BRACKET_REGEX = Regex("""(?i)\s*[\(\[\{]\s*with\s+[^)\]\}]+[\)\]\}]""")
    private val FEAT_INSIDE_BRACKET_REGEX = Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+[^)\]\}]+(?=[\)\]\}]|$)""")
    private val FEAT_TRAILING_REGEX = Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.*$""")
    private val EMPTY_BRACKETS_REGEX = Regex("""\(\s*\)|\[\s*\]|\{\s*\}""")
    private val MULTI_SPACE = Regex("""\s+""")

    /**
     * Strips movie attribution and soundtrack tags from a title specifically for lyrics provider search.
     * (e.g. "Deva Deva (From \"Brahmastra\")" -> "Deva Deva").
     */
    fun cleanCoreSongTitle(rawTitle: String): String {
        val cleaned = cleanTitle(rawTitle)
        val core = MOVIE_ATTRIBUTION_REGEX.replace(cleaned, "").trim(' ', '-', '|', ':', '_')
        return core.ifBlank { cleaned }
    }

    /**
     * Extracts version information (e.g. "Remix", "Acoustic", "Live") from a raw title string.
     */
    fun extractVersion(rawTitle: String): String? {
        val lower = rawTitle.lowercase()
        for (kw in VERSION_KEYWORDS) {
            val regex = Regex("""(?i)(?:^|[\(\[\{/\-\s])(${Regex.escape(kw)})(?:[\)\]\}/\-\s]|$)""")
            val match = regex.find(lower)
            if (match != null) {
                val canonical = when {
                    kw.startsWith("live") -> "Live"
                    kw.startsWith("acoustic") -> "Acoustic"
                    kw.startsWith("remix") || kw.endsWith("remix") -> "Remix"
                    kw.startsWith("instrumental") -> "Instrumental"
                    kw.startsWith("part 1") -> "Part 1"
                    kw.startsWith("part 2") -> "Part 2"
                    kw.startsWith("part 3") -> "Part 3"
                    else -> kw.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                }
                return canonical
            }
        }
        return null
    }

    /**
     * Cleans extraneous YouTube title noise while preserving real track names, versions, and scripts.
     * Note: Never discards the pre-hyphen song title for legitimate tracks (e.g. "Song - From Movie").
     */
    fun cleanTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return ""

        var title = IndicScriptNormalizer.normalizeIndicText(rawTitle).trim()

        // Extract version info before cleaning if present
        val preservedVersion = extractVersion(title)

        // 1. Remove pipe-separated label / channel noise
        title = PIPE_CHANNEL_SPAM_REGEX.replace(title, "")
        if (title.contains(" | ")) {
            title = title.substringBefore(" | ").trim()
        }

        // 2. Strip bracketed noise phrases (e.g. [Official Video], (Lyrics))
        title = BRACKET_NOISE_REGEX.replace(title, "")

        // 3. Strip featuring artists (e.g. "(feat. Bruno Mars)", "(with Bruno Mars)", "feat. DaBaby")
        // NOTE: NEVER strip plain "with" outside of explicit artist credit brackets to protect titles like "Die With A Smile"
        title = FEAT_BRACKET_REGEX.replace(title, "")
        title = WITH_ARTIST_BRACKET_REGEX.replace(title, "")
        title = FEAT_INSIDE_BRACKET_REGEX.replace(title, "")
        title = FEAT_TRAILING_REGEX.replace(title, "")
        title = EMPTY_BRACKETS_REGEX.replace(title, "")

        // 4. Handle "Song Title - Video Noise" or "Video Noise - Song Title"
        if (title.contains(" - ")) {
            val parts = title.split(" - ", limit = 2)
            if (parts.size == 2) {
                val part0 = parts[0].trim()
                val part1 = parts[1].trim()
                val p0Lower = part0.lowercase()
                val p1Lower = part1.lowercase()

                val isPart1PureNoise = p1Lower.matches(
                    Regex("""(?i)^(?:official\s*(?:video|audio|music\s*video)|music\s*video|lyric\s*video|video\s*song|audio\s*song|full\s*(?:video|audio|song)|4k|hd|hq|visualizer)$""")
                )
                val isPart0PureNoise = p0Lower.matches(
                    Regex("""(?i)^(?:official\s*(?:video|audio|music\s*video)|new\s*(?:bhojpuri|hindi|punjabi)?\s*song(?:\s*\d{4})?|new\s*song\s*\d{4})$""")
                )

                if (isPart1PureNoise) {
                    title = part0
                } else if (isPart0PureNoise) {
                    title = part1
                }
                // When neither part is pure video noise, DO NOT discard either part!
                // "Sorry Sorry - From \"Bhojpuriya Raja\"" remains intact.
                // "Song - Remix", "Song - Acoustic", "Song - Live" remain intact.
            }
        }

        // 5. Clean live/version bracket expansions if simplified version is captured
        if (preservedVersion != null) {
            title = title.replace(Regex("""(?i)\s*[\(\[]\s*live\s*(?:in|at)\s*[^)\]]+[\)\]]"""), " ($preservedVersion)")
            title = title.replace(Regex("""(?i)\s*[\(\[]\s*acoustic\s*version\s*[\)\]]"""), " ($preservedVersion)")
        }

        // 6. Clean dangling edge punctuation without stripping legitimate quotes or balanced brackets
        title = title.trim(' ', '-', '|', ':', '_', '/')
        if (title.startsWith("\"") && title.endsWith("\"") && title.count { it == '"' } == 2) {
            title = title.removeSurrounding("\"").trim()
        }
        if (title.startsWith("'") && title.endsWith("'") && title.count { it == '\'' } == 2) {
            title = title.removeSurrounding("'").trim()
        }
        title = EMPTY_BRACKETS_REGEX.replace(title, "")
        // Strip unmatched orphan brackets at edges
        if (title.endsWith(")") && !title.contains("(")) title = title.removeSuffix(")").trim()
        if (title.endsWith("]") && !title.contains("[")) title = title.removeSuffix("]").trim()
        if (title.endsWith("}") && !title.contains("{")) title = title.removeSuffix("}").trim()
        if (title.startsWith("(") && !title.contains(")")) title = title.removePrefix("(").trim()
        if (title.startsWith("[") && !title.contains("]")) title = title.removePrefix("[").trim()
        if (title.startsWith("{") && !title.contains("}")) title = title.removePrefix("{").trim()
        title = MULTI_SPACE.replace(title, " ").trim(' ', '-', '|', ':', '_', '/')

        // Re-append version tag if it was in the original title but stripped by bracket cleaning
        if (preservedVersion != null && !title.contains(preservedVersion, ignoreCase = true)) {
            title = "$title ($preservedVersion)"
        }

        return if (title.isBlank()) rawTitle.trim() else title
    }

    /**
     * Cleans artist names, stripping YouTube - Topic, VEVO, and Official suffixes.
     */
    fun cleanArtist(rawArtist: String): String {
        var artist = IndicScriptNormalizer.normalizeIndicText(rawArtist).trim()
        artist = artist
            .replace(Regex("""(?i)\s*-\s*Topic$"""), "")
            .replace(Regex("""(?i)\s*Official$"""), "")
            .replace(Regex("""(?i)\s*VEVO$"""), "")
            .replace(Regex("""(?i)\s*Music$"""), "")
            .trim(' ', '-', '|', ':', '_')

        if (artist.startsWith("\"") && artist.endsWith("\"")) artist = artist.removeSurrounding("\"").trim()
        if (artist.startsWith("'") && artist.endsWith("'")) artist = artist.removeSurrounding("'").trim()

        return if (artist.isBlank()) rawArtist.trim() else artist
    }

    /**
     * Splits a raw "Artist - Song Title" YouTube title into pair of (artist, title).
     * Prevents false splits on movie titles (e.g. "Song - From Movie") and musical version suffixes.
     */
    fun splitArtistAndTitle(rawTitle: String, fallbackArtist: String? = null): Pair<String, String> {
        val cleaned = cleanTitle(rawTitle)
        val cleanFallback = fallbackArtist?.let { cleanArtist(it) }?.ifBlank { null }

        // Check for common artist - title separator (" - ")
        if (rawTitle.contains(" - ")) {
            val parts = rawTitle.split(" - ", limit = 2)
            val part0 = parts[0].trim()
            val part1 = parts[1].trim()
            val p1Lower = part1.lowercase()

            // If part1 is a movie attribution ("From ..."), version, or subtitle, part0 is NOT an artist!
            val isPart1SubtitleOrMovie = p1Lower.startsWith("from \"") ||
                p1Lower.startsWith("from '") ||
                p1Lower.startsWith("from ") ||
                p1Lower.startsWith("ost") ||
                p1Lower.startsWith("original motion picture") ||
                extractVersion(part1) != null

            if (!isPart1SubtitleOrMovie) {
                val isFallbackGeneric = cleanFallback.isNullOrBlank() ||
                    cleanFallback.equals("Unknown Artist", ignoreCase = true) ||
                    cleanFallback.equals("YouTube Music", ignoreCase = true)

                if (isFallbackGeneric || cleanFallback.equals(part0, ignoreCase = true)) {
                    val left = cleanArtist(part0)
                    val right = cleanTitle(part1)
                    if (left.isNotBlank() && right.isNotBlank()) {
                        return Pair(left, right)
                    }
                }
            }
        }

        val artist = cleanFallback ?: "Unknown Artist"
        return Pair(artist, cleaned)
    }
}
