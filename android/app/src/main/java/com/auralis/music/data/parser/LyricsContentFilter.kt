package com.auralis.music.data.parser

/**
 * Existing LRC/cache credit policy. Do not broaden this to standalone words or
 * other formats: timed vocal text can itself say "Credits" or "Personnel".
 */
object LyricsContentFilter {
    private val metadataPrefix = Regex(
        """^(作词|作曲|编曲|制作人|制作|监制|混音|母带|吉他|贝斯|鼓|键盘|录音|和音|合音|企划|统筹|出品|发行|封面|弦乐|长笛|萨克斯|演唱|原唱|词|曲|OP|SP|Written\s+by|Composed\s+by|Produced\s+by|Lyrics\s+by|Music\s+by|Arranged\s+by|Mixed\s+by|Mastered\s+by|Recorded\s+by|Vocals\s+by|Vocal\s+by|Performed\s+by|Credits|Publisher|Release|Source|Transcribed\s+by|Translated\s+by|Synced\s+by)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )
    private val contributorPrefix = Regex(
        """^(?:synced\s+by|lyrics\s+uploaded\s+by|transcribed\s+by)\b""",
        RegexOption.IGNORE_CASE
    )
    private val separatorOnly = Regex("""^[\s\-=_~*#/\\|♪♫♩♬….]+$""")

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
