package com.auralis.music.domain.stats

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackArtist
import com.auralis.music.domain.model.TrackSource

/**
 * Who a song credits, for Stats and Wrapped: a collab ("Pritam, Mohit Chauhan, Irshad Kamil")
 * counts in full for each artist instead of as one artist named after the whole line, while one
 * artist whose name holds a separator ("Tyler, The Creator") stays whole.
 *
 * Sources, best first (see [forTrack]):
 *  1. A label upload's real artists, looked up from the official song (OfficialArtistResolver).
 *  2. YouTube Music's own per-artist list ([Track.artists]), each artist a separate linked piece.
 *  3. The singers named in a label-style title ("Song | Movie | Singer | T-Series").
 *  4. The credit line split on separators, keeping known single artists whole: a built-in list
 *     plus every name YouTube has linked as one artist on this device ([learn]).
 */
object ArtistCredits {
    @Volatile private var store: com.auralis.music.util.KeyValueStore? = null

    private const val STORE_KEY = "artist_credits_learned_v1"
    private const val MAX_LEARNED = 2_000

    // Commas, semicolons, "&", " x ", "/" between spaces and feat./ft./featuring. Not "and" or
    // "+", which are too often part of one name ("Florence + The Machine").
    private val SEPARATORS = Regex(
        """\s*(?:[,;，、&]|\s+x\s+|\s+/\s+|\s+(?:feat\.?|ft\.?|featuring)\s+)\s*""",
        RegexOption.IGNORE_CASE
    )
    private val HAS_SEPARATOR = Regex("""[,;，、&]|\s(?:x|/|feat\.?|ft\.?|featuring)\s""", RegexOption.IGNORE_CASE)
    private val CHANNEL_SUFFIX = Regex("""(?i)\s*-\s*topic$""")
    private val VEVO_SUFFIX = Regex("""(?i)vevo$""")
    private val PARENS = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^)\]]*)[)\]]""", RegexOption.IGNORE_CASE)
    private val NON_NAME = Regex("""[^\p{L}\p{M}0-9]""")
    private val SHIELD = Regex("(\\d+)")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Single artists (bands, duos billed as one act) whose names contain a separator. Matched
     * case-insensitively, with "&" also matching "and" and flexible spacing around punctuation.
     */
    private val SINGLE_ARTISTS = listOf(
        // Comma or " x " in the name
        "Tyler, The Creator", "Earth, Wind & Fire", "Crosby, Stills, Nash & Young", "Crosby, Stills & Nash",
        "Emerson, Lake & Palmer", "Blood, Sweat & Tears", "Peter, Paul & Mary", "Shankar, Ehsaan & Loy",
        "Now, Now", "Me, Myself & I", "Lil Nas X", "Malcolm X",
        // "&" bands and duos billed as one act
        "Simon & Garfunkel", "Hall & Oates", "Daryl Hall & John Oates", "Mumford & Sons", "Of Mice & Men",
        "Kool & the Gang", "Florence & the Machine", "Marina & the Diamonds", "Bob Marley & the Wailers",
        "Tom Petty & the Heartbreakers", "Echo & the Bunnymen", "Hootie & the Blowfish",
        "Martha & the Vandellas", "Sly & the Family Stone", "Prince & the Revolution",
        "Huey Lewis & the News", "Bruce Springsteen & the E Street Band", "Iron & Wine",
        "Belle & Sebastian", "Chase & Status", "Above & Beyond", "Years & Years", "Brooks & Dunn",
        "Big & Rich", "Sam & Dave", "Maddie & Tae", "Ike & Tina Turner", "Captain & Tennille",
        "Peaches & Herb", "Matt & Kim", "Nico & Vinz", "Macklemore & Ryan Lewis", "Angus & Julia Stone",
        "Dimitri Vegas & Like Mike", "Sonny & Cher", "Ashford & Simpson", "Salt & Pepa", "Jack & Jack",
        "Dan & Shay",
        // Hindi film composer duos when billed with "&"
        "Vishal & Shekhar", "Salim & Sulaiman", "Sajid & Wajid", "Sachin & Jigar", "Jatin & Lalit",
        "Ajay & Atul", "Nadeem & Shravan", "Laxmikant & Pyarelal", "Kalyanji & Anandji", "Anand & Milind",
        "Shankar & Jaikishan", "Shiv & Hari",
    )

    /**
     * Record labels and label-run channels that upload songs under their own name instead of the
     * artists'. Compared by [key], so spacing, case and punctuation don't matter.
     */
    private val LABELS: Set<String> = listOf(
        "T-Series", "T-Series Bhakti Sagar", "T-Series Apna Punjab", "T-Series Tamil", "T-Series Telugu",
        "Zee Music Company", "Zee Music South", "Sony Music India", "Sony Music South", "Sony Music",
        "Saregama", "Saregama Music", "Saregama Tamil", "Saregama Telugu", "Saregama Ghazal", "Tips Official",
        "Tips Music", "Tips Films", "YRF", "Yash Raj Films", "Speed Records", "White Hill Music",
        "Desi Melodies", "Times Music", "Venus", "Venus Movies", "Shemaroo", "Shemaroo Filmi Gaane",
        "Eros Now", "Eros Now Music", "Aditya Music", "Lahari Music", "Think Music India", "Junglee Music",
        "Universal Music India", "Warner Music India", "Saga Music", "Geet MP3", "Jass Records",
        "Humble Music", "Panj-aab Records", "VYRL Originals", "VYRL Punjabi", "Unisys Music", "Mango Music",
        "Muzik247", "Goldmines", "Goldmines Gaane Sune Ansune", "Rajshri", "Ultra Bollywood",
        "Wave Music", "Saavn", "Believe Music", "Divo Music", "Anand Audio", "Sun Music", "Sun TV",
        "Speed Punjabi", "Sony Music Entertainment", "Universal Music", "Warner Records",
        "Atlantic Records", "Columbia Records", "Interscope Records", "Republic Records",
        "Def Jam Recordings", "Capitol Records", "RCA Records", "Epic Records", "Island Records",
        "Ultra Music", "Spinnin' Records", "Monstercat", "NCS", "NoCopyrightSounds", "Trap Nation",
        "Chill Nation", "Proximity", "MrSuicideSheep", "7clouds", "Taz Network", "Lyrical Lemonade",
    ).mapNotNull(::key).toSet()

    // Endings that mark a label rather than an artist ("Saregama Music", "Mango Records").
    private val LABEL_SUFFIX = Regex(
        """(?i)\b(?:records|recordings|music company|music india|music south|music official|official music|music label|films|entertainment|gaane)$"""
    )

    // Pieces of a title that are never an artist: "Official Video", "Lyrical", "4K", "2024", "Full Song"...
    private val TITLE_NOISE = Regex(
        """(?i)\b(?:official|video|audio|lyric(?:s|al)?|full\s+song|song|songs|visuali[sz]er|4k|hd|hq|1080p|new|latest|trending|teaser|trailer|jukebox|mp3|status|reels?|shorts?|remix|reprise|lofi|lo-fi|slowed|reverb|cover|unplugged|movie|film|album|ost|soundtrack|from|starring|dir\.?|director|\d{4})\b"""
    )
    private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val TITLE_DASH = Regex("""\s[-–—]\s""")

    /** One regex matching any of [names], flexible about case, spacing and "&" vs "and". */
    private fun patternFor(names: Collection<String>): Regex? {
        if (names.isEmpty()) return null
        val alternatives = names.sortedByDescending { it.length }.joinToString("|") { name ->
            val words = name.trim().split(WHITESPACE)
            buildString {
                words.forEachIndexed { i, word ->
                    if (word == "&") {
                        append("""\s*(?:&|and)\s*""")
                        return@forEachIndexed
                    }
                    append(Regex.escape(word.removeSuffix(",")))
                    val next = words.getOrNull(i + 1)
                    when {
                        word.endsWith(",") -> append("""\s*,\s*""")
                        next != null && next != "&" -> append("""\s+""")
                    }
                }
            }
        }
        return Regex("""(?<![\p{L}\p{N}])(?:$alternatives)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    }

    private val BUILT_IN_PATTERN: Regex = patternFor(SINGLE_ARTISTS)!!

    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>() // key -> name
    @Volatile private var learnedPattern: Regex? = null
    @Volatile private var learnedPatternSize = -1

    /** Loads names learned on earlier launches and saves new ones to [kv]. Call once at startup. */
    fun persistTo(kv: com.auralis.music.util.KeyValueStore) {
        store = kv
        try {
            val saved = org.json.JSONArray(kv.getString(STORE_KEY, "[]").orEmpty().ifBlank { "[]" })
            for (i in 0 until saved.length()) {
                val name = saved.optString(i)
                if (name.isNotBlank()) key(name)?.let { learned.putIfAbsent(it, name) }
            }
        } catch (_: Exception) {
            // An unreadable list is relearned as songs load.
        }
    }

    /**
     * Records [name] as one artist when YouTube linked it as a single artist but it contains a
     * separator ("Tyler, The Creator"), so plain credit lines elsewhere keep it whole too.
     */
    fun learn(name: String) {
        val clean = display(name)
        if (clean.length !in 3..80 || !HAS_SEPARATOR.containsMatchIn(clean) || isLabel(clean)) return
        val k = key(clean) ?: return
        if (learned.containsKey(k) || learned.size >= MAX_LEARNED) return
        if (BUILT_IN_PATTERN.matches(clean)) return
        learned[k] = clean
        val kv = store ?: return
        runCatching { kv.putStrings(mapOf(STORE_KEY to org.json.JSONArray(learned.values.toList()).toString())) }
    }

    /** Names learned so far (for tests and diagnostics). */
    fun learnedNames(): Set<String> = learned.values.toSet()

    private fun currentLearnedPattern(): Regex? {
        if (learnedPatternSize != learned.size) {
            learnedPattern = patternFor(learned.values.toList())
            learnedPatternSize = learned.size
        }
        return learnedPattern
    }

    /** The credit without YouTube's " - Topic" / "VEVO" channel decoration. */
    fun display(raw: String): String =
        raw.trim().replace(CHANNEL_SUFFIX, "").replace(VEVO_SUFFIX, "").trim()

    /** "Arpit Bala, A.O.D. & Karan Kanchan" gives three names, in credit order. */
    fun split(raw: String): List<String> {
        // "A (feat. B)" becomes "A, B" so the featured artist is credited too.
        val credit = display(raw).replace(PARENS) { ", " + it.groupValues[1] }
        // Shield names like "Tyler, The Creator" from the separators, then put them back.
        val shielded = mutableListOf<String>()
        fun shield(text: String, pattern: Regex?): String = pattern?.replace(text) {
            shielded += it.value
            "${shielded.lastIndex}"
        } ?: text
        val masked = shield(shield(credit, currentLearnedPattern()), BUILT_IN_PATTERN)
        val names = masked.split(SEPARATORS)
            .map { part -> display(SHIELD.replace(part) { m -> restore(shielded, m.groupValues[1].toInt()) }) }
            .filter { it.isNotBlank() }
            .distinctBy { key(it) ?: it.lowercase() }
        return names.ifEmpty { listOfNotNull(display(raw).takeIf { it.isNotBlank() }) }
    }

    // A shielded name can itself hold an earlier shield; unwrap fully.
    private fun restore(shielded: List<String>, index: Int): String =
        SHIELD.replace(shielded[index]) { m -> restore(shielded, m.groupValues[1].toInt()) }

    /** Grouping key for one artist name: case, punctuation and channel suffixes ignored. */
    fun key(name: String): String? =
        display(name).lowercase().replace(NON_NAME, "").takeIf { it.isNotBlank() }

    /** Whether [name] is a record label or label channel rather than an artist. */
    fun isLabel(name: String): Boolean {
        val clean = display(name)
        val k = key(clean) ?: return false
        return k in LABELS || LABEL_SUFFIX.containsMatchIn(clean)
    }

    /**
     * Whether [track]'s credit names its uploader rather than its artists: a label channel
     * ("T-Series") or a placeholder. Those tracks get their artists from the official song or the
     * title instead.
     */
    fun isUploaderCredit(track: Track): Boolean {
        if (track.source != TrackSource.YOUTUBE) return false
        val credit = display(track.artist)
        if (credit.isBlank() || credit.equals("YouTube Artist", true) || credit.equals("YouTube Music", true) ||
            credit.equals("Unknown Artist", true) || credit.equals("Artist", true)) return true
        return split(credit).all(::isLabel)
    }

    /**
     * The singers named in a label-style title, before it was cleaned:
     *  - "Kesariya | Brahmastra | Arijit Singh | Pritam | T-Series" gives Arijit Singh, Pritam;
     *  - "Arijit Singh - Kesariya (Official Video)" gives Arijit Singh.
     * Empty when the title names nobody recognisable.
     */
    fun fromTitle(rawTitle: String): List<String> {
        val title = rawTitle.trim()
        if (title.contains("|")) {
            val segments = title.split("|").map { it.replace(BRACKETS, "").trim() }.filter { it.isNotBlank() }
            if (segments.size < 2) return emptyList()
            val candidates = segments.drop(1).filter { seg ->
                !isLabel(seg) && !TITLE_NOISE.containsMatchIn(seg) && seg.length <= 80 && seg.any(Char::isLetter)
            }
            // "Song | Movie | Singers | Label": with four or more pieces the first after the song
            // is usually the film or album, not a person.
            val people = if (segments.size >= 4 && candidates.size >= 2 && candidates.first() == segments[1]) candidates.drop(1) else candidates
            return people.flatMap(::split).filterNot(::isLabel).distinctBy { key(it) }.take(8)
        }
        val dash = TITLE_DASH.find(title) ?: return emptyList()
        val left = title.substring(0, dash.range.first).replace(BRACKETS, "").trim()
        val right = title.substring(dash.range.last + 1).trim()
        if (left.isBlank() || right.isBlank() || TITLE_NOISE.containsMatchIn(left) || isLabel(left) || left.length > 80) return emptyList()
        return split(left).filterNot(::isLabel).take(8)
    }

    /**
     * The artists [track] credits, best source first (see the class comment). [resolved] is the
     * official song's artist list for a label upload, when one was found.
     */
    fun forTrack(track: Track, resolved: List<TrackArtist>? = null): List<String> {
        if (!resolved.isNullOrEmpty()) {
            val names = resolved.map { display(it.name) }.filter { it.isNotBlank() }.distinctBy { key(it) }
            if (names.isNotEmpty()) return names
        }
        val listed = track.artists.map { display(it.name) }.filter { it.isNotBlank() && !isLabel(it) }.distinctBy { key(it) }
        if (listed.isNotEmpty()) return listed
        if (isUploaderCredit(track)) {
            val fromTitle = fromTitle(track.title)
            if (fromTitle.isNotEmpty()) return fromTitle
        }
        return split(track.artist)
    }
}
