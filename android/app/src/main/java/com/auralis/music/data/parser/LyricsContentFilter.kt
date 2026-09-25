package com.auralis.music.data.parser

/**
 * Existing LRC/cache credit policy. Do not broaden this to standalone words or
 * other formats: timed vocal text can itself say "Credits" or "Personnel".
 */
object LyricsContentFilter {
    private val metadataPrefix = Regex(
        """^(作词|作曲|编曲|制作人|制作|监制|混音|母带|吉他|贝斯|鼓|键盘|录音|和音|合音|企划|统筹|出品|发行|封面|弦乐|长笛|萨克斯|演唱|原唱|人声|和声|伴奏|演奏|歌手|原曲|翻唱|后期|词|曲|OP|SP|Written\s+by|Composed\s+by|Produced\s+by|Lyrics\s+by|Music\s+by|Arranged\s+by|Mixed\s+by|Mastered\s+by|Recorded\s+by|Vocals\s+by|Vocal\s+by|Performed\s+by|Credits|Publisher|Release|Source|Transcribed\s+by|Translated\s+by|Synced\s+by)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )
    private val contributorPrefix = Regex(
        """^(?:synced\s+by|lyrics\s+uploaded\s+by|transcribed\s+by)\b""",
        RegexOption.IGNORE_CASE
    )
    private val separatorOnly = Regex("""^[\s\-=_~*#/\\|♪♫♩♬….]+$""")

    // Fan-made KuGou / NetEase LRC annotations for non-Chinese songs:
    //   "印度电影《人生闹剧》插曲"             (header: "song from the Indian film ...")
    //   "女：Pal bhar thahar jaao"            (duet label: female / male / together)
    //   "Agar tum saath ho （若你在我身旁）"    (inline Chinese translation)
    private val cjkChar = Regex("""[぀-ヿ㐀-䶿一-鿿가-힯豈-﫿]""")
    private val cjkDuetLabel = Regex("""^\s*(?:男女|女男|合唱|男声|女声|男|女|合)\s*[：:]\s*""")
    private val cjkGloss = Regex("""\s*[（(][^（）()]*[぀-ヿ㐀-䶿一-鿿가-힯][^（）()]*[）)]\s*$""")

    /**
     * Removes the Chinese annotations above from lyrics whose own language is not
     * CJK. Songs that are mostly CJK are returned untouched. Timing is never changed;
     * a word-timed line is only ever kept or dropped whole.
     */
    fun removeForeignAnnotations(lyrics: com.auralis.music.domain.model.LyricsData): com.auralis.music.domain.model.LyricsData {
        val textLines = lyrics.lines.filter { !it.isInstrumental && it.text.isNotBlank() }
        if (textLines.isEmpty() || lyrics.lines.none { cjkChar.containsMatchIn(it.text) }) return lyrics
        val cjkLines = textLines.count { cjkChar.containsMatchIn(cjkGloss.replace(cjkDuetLabel.replace(it.text, ""), "")) }
        if (cjkLines * 10 >= textLines.size * 3) return lyrics // the song itself is (largely) CJK

        val cleaned = lyrics.lines.mapNotNull { line ->
            if (line.isInstrumental || !cjkChar.containsMatchIn(line.text)) return@mapNotNull line
            val stripped = cjkGloss.replace(cjkDuetLabel.replace(line.text, ""), "").trim()
            when {
                stripped.isBlank() || cjkChar.containsMatchIn(stripped) -> null
                line.hasWordTiming -> line // never rewrite text under genuine word timing
                else -> line.copy(text = stripped)
            }
        }
        return if (cleaned == lyrics.lines) lyrics
        else lyrics.copy(lines = cleaned, plainLyrics = cleaned.joinToString("\n") { it.text })
    }

    // A line with no letters or digits at all: "♪", "♫ ♫", "🎵", "...", "* * *". Sources use these
    // to mark instrumental stretches; shown as text they read as a stray icon, and as a line at
    // 0:00 they stop the intro circle (the app thinks singing starts immediately).
    private val noLyricText = Regex("""^[^\p{L}\p{Nd}]*$""")

    /**
     * The one cleanup every lyrics result goes through before display, from any source, fresh or
     * cached: source annotations, symbol-only marker lines, credit lines, and a leading
     * "Artists - Title" header. Timing of the lines that remain is never changed.
     */
    fun cleanForDisplay(
        lyrics: com.auralis.music.domain.model.LyricsData,
        title: String
    ): com.auralis.music.domain.model.LyricsData {
        val annotated = removeForeignAnnotations(lyrics)
        val kept = annotated.lines.filter { line ->
            line.isInstrumental || (!noLyricText.matches(line.text.trim()) &&
                !metadataPrefix.containsMatchIn(line.text.trim()) &&
                !contributorPrefix.containsMatchIn(line.text.trim()))
        }
        val stripped = if (kept.size == annotated.lines.size) annotated
            else annotated.copy(lines = kept, plainLyrics = kept.joinToString("\n") { it.text })
        return removeTitleHeader(stripped, title)
    }

    /** How deep into the lyrics a "Artists - Title" header can sit. */
    private const val TITLE_HEADER_SCAN_LINES = 3
    private val headerSeparator = Regex("""\s+[-–—]\s+""")

    private fun normalizeForHeader(text: String): String =
        text.lowercase()
            .replace(Regex("""[\(\[（【].*?[\)\]）】]"""), " ")   // "(From ...)", "[Official Audio]"
            .replace(Regex("""[^\p{L}\p{M}\p{Nd}]+"""), " ")      // punctuation incl. 、，· separators
            .trim()

    /**
     * Drops a source-added header like "Labh Janjua、Sonu Kakkar、Neha Kakkar - London Thumakda"
     * (NetEase/KuGou style) from the top of the lyrics. The app already shows title and artists,
     * so the line is noise. Only the first few lines are checked, and a line must be
     * "X - <title>" or "<title> - X": a line that is just the title is kept, because many songs
     * genuinely open by singing their title.
     */
    fun removeTitleHeader(
        lyrics: com.auralis.music.domain.model.LyricsData,
        title: String
    ): com.auralis.music.domain.model.LyricsData {
        val normTitle = normalizeForHeader(title)
        if (normTitle.isBlank()) return lyrics
        var checked = 0
        val dropIndices = mutableSetOf<Int>()
        for ((i, line) in lyrics.lines.withIndex()) {
            if (line.isInstrumental || line.text.isBlank()) continue
            if (++checked > TITLE_HEADER_SCAN_LINES) break
            val parts = line.text.split(headerSeparator)
            if (parts.size < 2) continue
            // "Artists - Title" or "Title - Artists"; the other side must be something else
            // (a chant like "Tum hi ho - tum hi ho" is a lyric, not a header).
            val splits = listOf(
                parts.dropLast(1).joinToString(" ") to parts.last(),
                parts.drop(1).joinToString(" ") to parts.first()
            ).map { (other, maybeTitle) -> normalizeForHeader(other) to normalizeForHeader(maybeTitle) }
            val isHeader = splits.any { (other, maybeTitle) ->
                maybeTitle == normTitle && other.isNotBlank() && other != normTitle
            }
            if (isHeader) dropIndices += i
        }
        if (dropIndices.isEmpty()) return lyrics
        val cleaned = lyrics.lines.filterIndexed { i, _ -> i !in dropIndices }
        return lyrics.copy(lines = cleaned, plainLyrics = cleaned.joinToString("\n") { it.text })
    }

    fun isNonLyricLine(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isBlank() ||
            separatorOnly.matches(trimmed) ||
            metadataPrefix.containsMatchIn(trimmed) ||
            contributorPrefix.containsMatchIn(trimmed) ||
            trimmed.startsWith("by:", ignoreCase = true) ||
            trimmed.startsWith("by :", ignoreCase = true)
    }
}
