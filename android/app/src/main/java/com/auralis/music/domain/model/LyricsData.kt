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

data class LyricWord(
    val word: String,
    val time: Long,             // Milliseconds offset from song start
    val duration: Long? = null  // Duration of syllable in milliseconds
)

data class LyricLine(
    val time: Long,             // Milliseconds offset for the line start
    val text: String,
    val translatedText: String? = null,
    val words: List<LyricWord>? = null,
    val isInstrumental: Boolean = false
)

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
