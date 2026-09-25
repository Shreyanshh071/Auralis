package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord

/**
 * The single place that answers "does this lyric actually carry word timing?".
 *
 * Every parser, the provider race and the Room cache used to answer it their own
 * way, and the loosest of those answers — "any words present at all" — let
 * fabricated timing be labelled word-synced. These two predicates replace all of
 * them.
 *
 * The distinction between them matters because sources differ in *how much* they
 * state:
 *  - TTML / YRC / QRC / Musixmatch state a start **and** an end per token, so a
 *    karaoke sweep can be drawn. [hasGenuineWordTiming] is that case.
 *  - Enhanced LRC (`<00:12.50>word`) states starts only. That is still real
 *    per-word timing — it just cannot be swept, only stepped.
 *    [hasGenuineWordStarts] is that case.
 *  - A line whose "words" all share one timestamp states nothing: it is a token
 *    split of the line text with the line's own time stamped onto each piece.
 *    Both predicates reject it, which is the point.
 */
object WordTiming {

    /**
     * True when some non-instrumental line can drive a **sweep** — at least two
     * of its words carry a provider-supplied duration.
     *
     * Two, not one: a lone timed word among untimed ones is a parser artefact
     * (a stray `end` attribute, a single `d` field), not word sync.
     *
     * Mirrors braccato's `parts?.some(p => p.durationMs > 0)` and the common
     * TTML rule of recording a span only when both `begin` and `end` are present.
     */
    fun hasGenuineWordTiming(lines: List<LyricLine>): Boolean =
        lines.any { line ->
            !line.isInstrumental && (line.words?.count { it.duration != null } ?: 0) >= 2
        }

    /**
     * True when some non-instrumental line can drive a **step** — at least two of
     * its words start at distinct, provider-supplied times.
     *
     * The distinct-time requirement is what rejects fabricated word lists: a
     * splitter that stamps `line.time` onto every token produces N words with one
     * timestamp, which carries no more information than the line already did.
     */
    fun hasGenuineWordStarts(lines: List<LyricLine>): Boolean =
        lines.any { line ->
            !line.isInstrumental &&
                (line.words?.size ?: 0) >= 2 &&
                (line.words?.distinctBy { it.time }?.size ?: 0) >= 2
        }

    /**
     * The keep-or-strip question: does the word list say anything the line
     * timestamps do not?
     *
     * Deliberately more permissive than the two predicates above, because both of
     * those ask "can this drive an animation across a line", and this one asks
     * only "is there information here worth keeping". A `<p>` whose single span
     * carries a real `end` states where the vocal stops — the line time alone
     * never does — so it survives, while a token split sharing one timestamp does
     * not.
     */
    fun statesMoreThanLineTime(lines: List<LyricLine>): Boolean =
        lines.any { line ->
            !line.isInstrumental &&
                (
                    line.words?.any { it.duration != null } == true ||
                        (line.words?.distinctBy { it.time }?.size ?: 0) >= 2
                    )
        }

    /**
     * The only derivation this codebase permits: split a span that **already has
     * a genuine duration** into its whitespace-separated words, proportionally by
     * character count, strictly inside `[time, time + duration]`.
     *
     * Legitimate because it invents no new interval — it subdivides one the
     * provider measured. Apple Music and AMLL TTML exports do emit multi-word
     * spans (`<span begin="16s" end="17.5s">can try</span>`), and braccato
     * subdivides them the same way (`inject.ts:231-244`).
     *
     * Returns the input unchanged when there is nothing to split or no genuine
     * duration to split — never widens the span, never emits a zero-length piece.
     */
    fun subdivideByCharCount(span: LyricWord): List<LyricWord> {
        val duration = span.duration ?: return listOf(span)
        if (duration <= 0L) return listOf(span)

        val tokens = splitKeepingTrailingSpace(span.word)
        if (tokens.size < 2) return listOf(span)

        val weights = tokens.map { it.trim().length.coerceAtLeast(1) }
        val totalWeight = weights.sum()
        if (totalWeight <= 0) return listOf(span)

        val out = ArrayList<LyricWord>(tokens.size)
        var consumedWeight = 0
        var cursor = span.time
        for (i in tokens.indices) {
            consumedWeight += weights[i]
            // Accumulate against the span end rather than summing per-piece
            // lengths, so rounding can never push the last piece past it.
            val pieceEnd = if (i == tokens.lastIndex) {
                span.time + duration
            } else {
                span.time + (duration * consumedWeight) / totalWeight
            }
            val pieceDur = pieceEnd - cursor
            if (pieceDur <= 0L) return listOf(span)
            out.add(
                LyricWord(
                    word = tokens[i],
                    time = cursor,
                    duration = pieceDur,
                    isBackground = span.isBackground
                )
            )
            cursor = pieceEnd
        }
        return out
    }

    /** Applies [subdivideByCharCount] to every span of every line. */
    fun subdivideLines(lines: List<LyricLine>): List<LyricLine> = lines.map { line ->
        val words = line.words ?: return@map line
        if (words.none { it.word.trim().contains(' ') }) return@map line
        line.copy(words = words.flatMap { subdivideByCharCount(it) })
    }

    /**
     * Splits on whitespace but keeps each separator attached to the token before
     * it, so the renderer can still print the pieces verbatim and reassemble the
     * original text exactly.
     */
    private fun splitKeepingTrailingSpace(text: String): List<String> {
        if (text.isBlank()) return listOf(text)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var tokenHasLetters = false
        for (ch in text) {
            if (!ch.isWhitespace() && tokenHasLetters && sb.isNotEmpty() && sb.last().isWhitespace()) {
                // A non-space after a space run closes the previous token.
                out.add(sb.toString())
                sb.setLength(0)
                tokenHasLetters = false
            }
            sb.append(ch)
            if (!ch.isWhitespace()) tokenHasLetters = true
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return if (out.isEmpty()) listOf(text) else out
    }

    private val STANDALONE_WORDS = setOf(
        // Pronouns, determiners, articles
        "a", "an", "the", "i", "me", "my", "mine", "myself",
        "you", "your", "yours", "yourself", "yourselves",
        "he", "him", "his", "himself",
        "she", "her", "hers", "herself",
        "it", "its", "itself",
        "we", "us", "our", "ours", "ourselves",
        "they", "them", "their", "theirs", "themselves",
        "this", "that", "these", "those",
        "all", "any", "some", "no", "every", "each", "both", "few", "more", "most", "other", "such",
        // Prepositions & Conjunctions
        "in", "on", "at", "to", "for", "with", "by", "from", "of", "as", "about", "after",
        "along", "around", "before", "behind", "below", "beneath", "beside", "between", "beyond",
        "down", "during", "except", "inside", "into", "like", "near", "off", "onto", "out",
        "over", "past", "since", "through", "till", "toward", "under", "until", "up", "upon",
        "within", "without", "and", "but", "or", "nor", "yet", "so", "than", "though", "unless",
        "while", "because", "if",
        // Verbs & Auxiliaries
        "is", "am", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "having",
        "do", "does", "did", "done", "doing",
        "can", "could", "will", "would", "shall", "should", "may", "might", "must",
        "go", "goes", "went", "gone", "going",
        "come", "comes", "came", "coming",
        "see", "sees", "saw", "seen", "seeing",
        "know", "knows", "knew", "known", "knowing",
        "say", "says", "said", "saying",
        "tell", "tells", "told", "telling",
        "make", "makes", "made", "making",
        "take", "takes", "took", "taken", "taking",
        "get", "gets", "got", "getting",
        "give", "gives", "gave", "given", "giving",
        "find", "finds", "found", "finding",
        "think", "thinks", "thought", "thinking",
        "look", "looks", "looked", "looking",
        "want", "wants", "wanted", "wanting",
        "feel", "feels", "felt", "feeling",
        "put", "puts", "putting",
        "let", "lets", "letting",
        "run", "runs", "ran", "running",
        "hear", "hears", "heard", "hearing",
        "call", "calls", "called", "calling",
        "keep", "keeps", "kept", "keeping",
        "need", "needs", "needed", "needing",
        "leave", "leaves", "left", "leaving",
        "help", "play", "talk", "turn", "start", "show", "move", "live", "hold",
        "bring", "write", "read", "stand", "lose", "lost", "pay", "meet", "met",
        "set", "learn", "stop", "walk", "grow", "wait", "send", "stay", "fall",
        "cut", "reach", "kill", "raise", "pass", "sell", "sold", "hope", "break",
        "hit", "eat", "catch", "draw", "choose", "fight", "throw", "die", "care",
        "love", "hate", "wish", "fear", "trust", "tease", "touch", "sing", "cry", "dance", "pray",
        // Adjectives, Adverbs, Numbers
        "not", "yes", "now", "then", "here", "there", "when", "where", "why", "how",
        "what", "who", "whom", "which", "whose",
        "very", "too", "much", "less", "better", "best", "worse", "worst",
        "good", "bad", "well", "new", "old", "big", "small", "high", "low",
        "great", "little", "own", "right", "left", "long", "short", "real",
        "true", "false", "easy", "hard", "fast", "slow", "clear", "cold", "hot", "warm",
        "dark", "light", "soft", "loud", "late", "early", "far", "close", "free",
        "full", "pure", "fine", "sure", "sweet", "dead", "alive", "alone", "ready",
        "sad", "sick", "deep", "rich", "poor", "fair", "pale", "wild", "calm",
        "glad", "cool", "dry", "wet", "clean", "dirty", "strong", "weak", "safe",
        "just", "only", "also", "even", "back", "still", "already", "always", "never",
        "ever", "often", "again", "away", "together", "enough", "almost", "quite", "soon", "maybe",
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        // Contractions (without apostrophe or with)
        "dont", "im", "ive", "ill", "id", "youre", "youve", "youll", "youd",
        "hes", "hed", "hell", "shes", "shed", "shell", "its", "were", "weve",
        "well", "wed", "theyre", "theyve", "theyll", "theyd", "thats", "whats",
        "whos", "theres", "heres", "wheres", "cant", "wont", "aint", "didnt",
        "doesnt", "isnt", "arent", "wasnt", "werent", "havent", "hasnt", "hadnt",
        "wouldnt", "couldnt", "shouldnt",
        // Common Nouns
        "man", "men", "woman", "women", "boy", "girl", "child", "baby", "people",
        "friend", "world", "life", "time", "year", "day", "night", "way", "thing",
        "eye", "hand", "head", "heart", "body", "soul", "mind", "face", "door",
        "house", "home", "room", "road", "street", "car", "city", "town", "water",
        "sky", "sun", "moon", "star", "fire", "earth", "air", "tree", "plant",
        "sound", "word", "song", "music", "voice", "name", "place", "part", "side",
        "end", "line", "shadow", "dream", "truth", "lie", "peace", "war", "pain",
        "tear", "smile", "kiss", "bed", "blood", "bone", "breath", "rain", "wind",
        "sea", "ocean", "river", "hill", "rock", "stone", "gold", "glass", "can",
        "bar", "sage", "bird", "dog", "cat", "fish", "hello",
        "theme", "theory", "theatre", "theater", "thesis",
        "forget", "forgot", "forgive", "forgave", "forgiven", "format", "fortune", "forest", "forward",
        "cannot", "superman", "superstar", "weekend"
    )

    private val NON_STANDALONE_SUFFIXES = setOf(
        "ers", "est", "tion", "tions", "sion", "sions", "ment", "ments", "ness",
        "ible", "ity", "ities", "ous", "ious", "ize", "ise",
        "ized", "ised", "izing", "ising", "ings", "pise", "lize", "ther", "vor",
        "delic", "tastic", "lessly"
    )

    private val NON_STANDALONE_PREFIXES = setOf(
        "des", "toge", "beauti", "delic", "fantas", "unbreak", "rea", "vi", "psyche"
    )

    private val KNOWN_COMPOUND_WORDS = setOf(
        "starlight", "sunflower", "forever", "into", "inside", "without", "someone",
        "something", "somewhere", "everyday", "rainbow", "butterfly", "moonlight",
        "sunlight", "heartbeat", "everywhere", "anywhere", "myself", "yourself",
        "himself", "herself", "itself", "themselves", "tonight", "today", "tomorrow",
        "wunderbar", "together", "trevor", "heather", "better", "weather", "feather",
        "leather", "whatever", "whenever", "wherever", "whoever", "however", "another", "never",
        "prayers", "despise", "realize", "visage", "beautiful", "deceive", "release", "psychedelic",
        "unbreakable", "connection", "easy", "weirdo", "running", "happy", "blinded", "withdrawals", "overdrive",
        "nevertheless", "nonetheless", "cannot", "superman", "superstar", "weekend", "weekday",
        "anyone", "anybody", "anything", "anyway", "anymore", "anyhow",
        "somebody", "someday", "somehow", "sometime", "sometimes",
        "everyone", "everybody", "everything",
        "nobody", "noone", "nothing", "nowhere",
        "outside", "outdoors", "outcome", "outlook", "outline", "outfit", "output", "outlet",
        "within", "withhold", "withdraw", "withdrawal",
        "instead", "insight", "intact", "income", "input",
        "upon", "upset", "upward", "upstairs", "downstairs", "downtown", "uptown",
        "underline", "understand", "understood", "undertake", "underground", "underwater",
        "overcome", "overflow", "overlook", "overnight", "overtake",
        "already", "almost", "always", "although", "altogether", "also",
        "maybe", "meanwhile", "meantime", "ourselves", "yourselves",
        "sunshine", "sunset", "sunrise", "daylight", "nightfall", "midnight",
        "heartbreak", "heartbroken", "background", "foreground", "playground",
        "theme", "theology",
        "hopeless", "careless", "endless", "fearless", "heartless", "painless", "restless", "sleepless",
        "flawless", "clueless", "helpless", "relentless", "reckless", "ruthless", "senseless", "shameless",
        "useless", "worthless", "limitless", "breathless", "boundless", "speechless", "aimless", "effortless",
        "timeless", "tireless", "wireless",
        "brother", "mother", "father", "sister", "gather", "rather", "neither", "either", "whether",
        "bother", "smother", "further", "furthermore", "petsmart", "youtube"
    )

    private val GLUED_PREFIX_WORDS = listOf(
        "the", "and", "in", "to", "of", "on", "with", "for", "at", "by", "from",
        "my", "your", "our", "all", "so", "no", "we", "you", "they", "he", "she",
        "it", "i", "that", "what", "who", "when", "where", "why", "how", "oh"
    )

    fun isStandalone(token: String): Boolean {
        val c = token.trim()
            .trimEnd(',', '.', '!', '?', ';', ':', '"', '\'', ')', ']', '}')
            .trimStart('(', '[', '{', '"', '\'')
            .replace("'", "")
            .replace("’", "")
            .lowercase()
        return c.isNotEmpty() && STANDALONE_WORDS.contains(c)
    }

    /**
     * Splits accidental glued/merged words (e.g. "Theless" -> ["The ", "less"], "andthe" -> ["and ", "the"],
     * "TheLess" -> ["The ", "Less"], "inmy" -> ["in ", "my"]) that commonly occur in lyrics metadata or
     * provider markup without splitting legitimate English compound words ("together", "without", "forever", etc.).
     */
    fun splitAccidentalMergedWord(token: String): List<String> {
        if (token.length < 4 || isCjk(token)) return listOf(token)

        var lIdx = 0
        while (lIdx < token.length && !token[lIdx].isLetterOrDigit()) {
            lIdx++
        }
        var rIdx = token.length
        while (rIdx > lIdx && !token[rIdx - 1].isLetterOrDigit()) {
            rIdx--
        }

        val leading = token.substring(0, lIdx)
        val core = token.substring(lIdx, rIdx)
        val trailing = token.substring(rIdx)

        if (core.length < 4) return listOf(token)

        val coreLower = core.lowercase()

        // 1. Never split known compounds or standalone dictionary words
        if (KNOWN_COMPOUND_WORDS.contains(coreLower) || isStandalone(coreLower)) {
            return listOf(token)
        }

        // 2. Check camelCase / PascalCase boundaries (e.g. "TheLess" -> "The", "Less")
        val camelBoundary = Regex("([a-z0-9])([A-Z])")
        if (camelBoundary.containsMatchIn(core)) {
            val parts = core.split(Regex("(?<=[a-z0-9])(?=[A-Z])"))
            if (parts.size >= 2 && parts.all { isStandalone(it) || KNOWN_COMPOUND_WORDS.contains(it.lowercase()) }) {
                val res = mutableListOf<String>()
                for (i in parts.indices) {
                    val p = parts[i]
                    if (i == 0) {
                        res.add(leading + p + if (parts.size > 1 && !trailing.startsWith(" ")) " " else "")
                    } else if (i == parts.lastIndex) {
                        res.add(p + trailing)
                    } else {
                        res.add("$p ")
                    }
                }
                return res
            }
        }

        // 3. Check Glued Prefix Words (e.g. "Theless" -> "The ", "less")
        for (pref in GLUED_PREFIX_WORDS) {
            if (coreLower.startsWith(pref) && coreLower.length > pref.length) {
                val rem = coreLower.substring(pref.length)
                if (isStandalone(rem) || KNOWN_COMPOUND_WORDS.contains(rem)) {
                    val part1Str = core.substring(0, pref.length)
                    val part2Str = core.substring(pref.length)
                    val res1 = leading + part1Str + " "
                    val res2 = part2Str + trailing
                    return listOf(res1, res2)
                }
            }
        }

        return listOf(token)
    }

    /**
     * Splits any accidentally merged words within a plaintext string while maintaining
     * natural spacing and punctuation.
     */
    fun splitMergedWordsInText(text: String): String {
        if (text.isBlank()) return text
        val rawTokens = text.split(" ")
        val out = mutableListOf<String>()
        for (rawToken in rawTokens) {
            if (rawToken.isEmpty()) {
                out.add("")
                continue
            }
            val split = splitAccidentalMergedWord(rawToken)
            if (split.size > 1) {
                for (s in split) {
                    val trimmed = s.trim()
                    if (trimmed.isNotEmpty()) out.add(trimmed)
                }
            } else {
                out.add(rawToken)
            }
        }
        return out.joinToString(" ")
    }

    /**
     * Splits a [LyricWord] that contains an accidental merge into multiple proportional [LyricWord]s.
     * Divides word duration proportionally across the split parts to keep karaoke sweeping/highlighting natural.
     */
    fun splitMergedLyricWord(word: LyricWord): List<LyricWord> {
        val parts = splitAccidentalMergedWord(word.word)
        if (parts.size <= 1) return listOf(word)

        val letterCounts = parts.map { part ->
            part.count { it.isLetterOrDigit() }.coerceAtLeast(1)
        }
        val totalLetters = letterCounts.sum()
        val totalDuration = word.duration

        var currentStart = word.time
        var durationRemaining = totalDuration
        val result = mutableListOf<LyricWord>()

        for (i in parts.indices) {
            val partText = parts[i]
            val count = letterCounts[i]
            val partDuration = if (totalDuration != null) {
                if (i == parts.lastIndex) {
                    durationRemaining
                } else {
                    val dur = ((totalDuration * count.toLong()) / totalLetters.toLong()).coerceAtLeast(1L)
                    durationRemaining = durationRemaining?.minus(dur)?.coerceAtLeast(1L)
                    dur
                }
            } else null

            result.add(
                word.copy(
                    word = partText,
                    time = currentStart,
                    duration = partDuration
                )
            )

            if (partDuration != null) {
                currentStart += partDuration
            }
        }
        return result
    }

    /**
     * Splits accidental merged words across both text and word-synced timestamps of a [LyricLine].
     */
    fun splitMergedWordsInLine(line: LyricLine): LyricLine {
        val newWords = if (!line.words.isNullOrEmpty()) {
            line.words.flatMap { splitMergedLyricWord(it) }
        } else null

        val newText = splitMergedWordsInText(line.text)

        return if (newWords != line.words || newText != line.text) {
            line.copy(text = newText, words = newWords)
        } else {
            line
        }
    }

    /**
     * Determines whether two words that had whitespace between them in provider markup
     * or legacy cache are actually an unintended split of a single word.
     */
    fun isUnintendedSpaceSplit(prevWord: String, currWord: String): Boolean {
        val f = prevWord.trim().trimEnd('-', '\'', '’').lowercase()
        val s = currWord.trim()
            .trimEnd(',', '.', '!', '?', ';', ':', '"', '\'', ')', ']', '}')
            .trimStart('(', '[', '{', '"', '\'')
            .trimEnd('-', '\'', '’')
            .lowercase()
        if (f.isEmpty() || s.isEmpty()) return false
        if (prevWord.trim().endsWith("-")) return true
        if (prevWord.trim().last() in ",.!?;:\"'") return false
        val combined = f + s
        if (KNOWN_COMPOUND_WORDS.contains(combined)) return true
        // If both tokens are standalone English words, whitespace between them is deliberate and must never be stripped
        if (isStandalone(f) && isStandalone(s)) return false
        if (s in NON_STANDALONE_SUFFIXES) return true
        if (f in NON_STANDALONE_PREFIXES) return true
        return false
    }

    /**
     * Heals any split words inside a plaintext lyric line (e.g. "Say your pray ers" -> "Say your prayers"),
     * and splits any accidentally merged words (e.g. "Oh, theless I know" -> "Oh, the less I know").
     */
    fun healSplitWordsInText(text: String): String {
        val unglued = splitMergedWordsInText(text)
        if (unglued.isBlank() || !unglued.contains(' ')) return unglued
        val tokens = unglued.split(" ")
        if (tokens.size < 2) return unglued
        val out = ArrayList<String>(tokens.size)
        out.add(tokens[0])
        for (i in 1 until tokens.size) {
            val curr = tokens[i]
            val prev = out[out.lastIndex]
            if (isUnintendedSpaceSplit(prev, curr)) {
                out[out.lastIndex] = prev + curr
            } else {
                out.add(curr)
            }
        }
        return out.joinToString(" ")
    }

    /**
     * Determines whether two adjacent unspaced spans are genuine syllables belonging to
     * the same word (e.g. "beauti" + "ful", "well-" + "known") vs distinct independent words
     * that merely lacked whitespace in provider markup (e.g. "plastic" + "watering").
     */
    fun shouldMergeSyllables(firstWord: String, secondWord: String): Boolean {
        val f = firstWord.trim()
        val s = secondWord.trim()
            .trimEnd(',', '.', '!', '?', ';', ':', '"', '\'', ')', ']', '}')
            .trimStart('(', '[', '{', '"', '\'')
        if (f.isEmpty() || s.isEmpty()) return false

        // Hyphenated compounds/syllables always merge (e.g. "well-", "re-")
        if (f.endsWith("-")) return true

        // Trailing punctuation on first word marks a clause or word boundary — never merge
        if (f.last() in ",.!?;:\"'") return false

        val fLower = f.lowercase().trimEnd('-', '\'', '’')
        val sLower = s.lowercase().trimStart('-', '\'', '’')
        val combined = fLower + sLower

        if (KNOWN_COMPOUND_WORDS.contains(combined)) return true

        // Two valid standalone English words NEVER merge unless they form a known compound word
        if (isStandalone(fLower) && isStandalone(sLower)) return false

        // Recognized sub-word prefixes or suffixes that cannot stand alone
        if (fLower in NON_STANDALONE_PREFIXES || sLower in NON_STANDALONE_SUFFIXES) return true

        // Unspaced adjacent spans where at least one token is a non-standalone syllable fragment (<= 4 characters)
        // are syllables of the same visual word (e.g. "ea" + "sy", "vi" + "sage", "de" + "ceive", "beauti" + "ful").
        if (!isStandalone(fLower) && fLower.length <= 4) return true
        if (!isStandalone(sLower) && sLower.length <= 4) return true

        return false
    }

    /**
     * Merges contiguous syllable spans belonging to the same visual word into a single
     * [LyricWord].
     *
     * In TTML and similar providers, multi-syllable words (such as "beauti" + "ful") are
     * emitted as separate spans without whitespace between them.
     *
     * Contiguous spans are merged when:
     * 1. Spans are unspaced syllables, OR unintended space splits of the same word.
     * 2. Neither span contains CJK characters.
     * 3. Both spans share the same background-vocal status.
     *
     * When two spans are independent words lacking whitespace (e.g. "plastic" + "watering"),
     * merging is rejected and a proper word-separating space is preserved.
     */
    fun mergeContiguousSyllables(words: List<LyricWord>?): List<LyricWord>? {
        if (words.isNullOrEmpty() || words.size < 2) return words

        val merged = ArrayList<LyricWord>(words.size)
        var acc = words[0]

        for (i in 1 until words.size) {
            val curr = words[i]
            val prevWord = acc.word
            val hasTrailingSpace = prevWord.isNotEmpty() && prevWord.last().isWhitespace()
            val canMerge = !isCjk(prevWord) &&
                !isCjk(curr.word) &&
                acc.isBackground == curr.isBackground &&
                ((!hasTrailingSpace && shouldMergeSyllables(prevWord, curr.word)) ||
                 (hasTrailingSpace && isUnintendedSpaceSplit(prevWord, curr.word)))

            if (canMerge) {
                val cleanPrev = if (hasTrailingSpace) prevWord.trimEnd() else prevWord
                val combinedText = cleanPrev + curr.word
                val startTime = acc.time
                val currEnd = curr.duration?.let { curr.time + it }
                val accEnd = acc.duration?.let { acc.time + it }
                val effectiveEnd = currEnd ?: accEnd
                val combinedDuration = if (effectiveEnd != null && effectiveEnd > startTime) {
                    effectiveEnd - startTime
                } else null

                acc = acc.copy(
                    word = combinedText,
                    time = startTime,
                    duration = combinedDuration
                )
            } else {
                // If previous word had no trailing space and current word has no leading space,
                // preserve normal word boundary space between distinct words.
                val needsSpace = !hasTrailingSpace &&
                    !prevWord.endsWith("-") &&
                    !curr.word.startsWith(" ") &&
                    !isCjk(prevWord) &&
                    !isCjk(curr.word)

                merged.add(if (needsSpace) acc.copy(word = "$prevWord ") else acc)
                acc = curr
            }
        }
        merged.add(acc)
        return merged
    }

    /**
     * Identifies characters belonging to CJK (Chinese, Japanese, Korean) Unicode blocks,
     * where each character typically represents an independent syllable/morpheme without
     * word-separating spaces. Syllable merging is bypassed for CJK to preserve natural
     * character-by-character karaoke progression.
     */
    fun isCjk(text: String): Boolean = text.any { c ->
        Character.UnicodeBlock.of(c) in CJK_UNICODE_BLOCKS
    }

    private val CJK_UNICODE_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    )
}
