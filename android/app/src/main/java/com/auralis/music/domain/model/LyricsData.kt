package com.auralis.music.domain.model

enum class LyricsProvider {
    AMLL,
    BETTER_LYRICS,
    LRCLIB,
    KUGOU,
    JIOSAAVN,
    NETEASE,
    GENIUS,
    MUSIXMATCH,
    YOUTUBE,
    YOUTUBE_CAPTIONS,
    LOCAL
}

/**
 * A single genuinely-timed word (or syllable) inside a lyric line.
 *
 * Timing contract — parsers MUST honour this, the renderer relies on it:
 *  - [time] is the provider's own word start. Never derived, never shifted.
 *  - [duration] is the provider's own measured length (`end - start`). It is
 *    `null` when, and only when, the provider supplied no end timestamp.
 *    A `null` duration must never be replaced with a guess (line length / N,
 *    character count, a fixed default, or the next word's start), because doing
 *    so paints over real vocal rests. The renderer treats `null` as
 *    "start is known, length is unknown" and does not sweep such a word.
 *  - The interval between one word's end (`time + duration`) and the next
 *    word's [time] is a genuine rest and is left empty on purpose.
 *  - [word] carries its own trailing whitespace when the source says the word
 *    is followed by a space. The renderer prints the text verbatim, so
 *    syllables of a single word stay joined.
 */
data class LyricWord(
    val word: String,
    val time: Long,             // Milliseconds offset from song start (provider-supplied)
    val duration: Long? = null  // Provider-supplied length in ms; null = no end timestamp
) {
    /** End of the sung word, or `null` when the provider gave no end timestamp. */
    val endTime: Long?
        get() = duration?.let { time + it }
}

data class LyricLine(
    val time: Long,             // Milliseconds offset for the line start
    val text: String,
    val translatedText: String? = null,
    val words: List<LyricWord>? = null,
    val isInstrumental: Boolean = false
) {
    /** True when the provider gave real per-word timing for this line. */
    val hasWordTiming: Boolean
        get() = !words.isNullOrEmpty()

    /** Last genuine word end in this line, or `null` when unknown. */
    val wordTimingEndMs: Long?
        get() = words?.lastOrNull { it.duration != null }?.endTime
}

data class LyricsData(
    val syncType: SyncType,
    val lines: List<LyricLine>,
    val plainLyrics: String? = null,
    val translatedPlainLyrics: String? = null,
    val translatedLanguage: String? = null,
    val provider: LyricsProvider,
    val trackName: String? = null,
    val artistName: String? = null
)
