package com.auralis.music.domain.model

/** Where tracks land when added to a local playlist. */
enum class AddToPlaylistPosition(val displayName: String, val description: String) {
    BEGINNING("Beginning", "New songs go to the top of the playlist"),
    END("End", "New songs go to the bottom of the playlist")
}

/** What the home screen's Quick picks row is built from. */
enum class QuickPicksMode(val displayName: String) {
    QUICK_PICKS("Quick picks"),
    LAST_LISTEN("Last song listened")
}

enum class ProxyType { HTTP, SOCKS }

/** Lyrics providers the user can switch off (see LyricsClient.isProviderEnabled). */
enum class LyricsProviderId(val displayName: String, val description: String) {
    LRCLIB("LRCLIB", "Open, community-synced lyrics database"),
    KUGOU("KuGou", "Large catalogue with word-timed lyrics"),
    BETTER_LYRICS("Better Lyrics", "Word-synced lyrics from Apple Music"),
    PAXSENIX("Paxsenix", "Word-synced lyrics from several services"),
    LYRICS_PLUS("LyricsPlus", "YouLy+ backend (Apple, QQ, Deezer)"),
    UNISON("Unison", "Community word-synced lyrics"),
    AMLL("AMLL", "Apple Music-style TTML lyrics"),
    MUSIXMATCH("Musixmatch", "Rich-sync word-timed lyrics"),
    NETEASE("NetEase", "NetEase Cloud Music lyrics"),
    JIOSAAVN("JioSaavn", "Indian catalogue lyrics"),
    SIMPMUSIC("SimpMusic", "SimpMusic lyrics database"),
    YOUTUBE_MUSIC("YouTube Music", "Lyrics shown in YouTube Music (unsynced)"),
    YOUTUBE_CAPTIONS("YouTube captions", "Timed captions from the music video"),
    GENIUS("Genius", "Unsynced lyrics fallback")
}

/** Scripts the lyrics view can romanize under the original line. */
enum class RomanizationScript(val displayName: String) {
    JAPANESE("Japanese"),
    KOREAN("Korean"),
    CHINESE("Chinese"),
    HINDI("Hindi (Devanagari)"),
    PUNJABI("Punjabi (Gurmukhi)"),
    CYRILLIC("Cyrillic (Russian, Ukrainian, …)")
}

data class ContentSettings(
    /** BCP-47 language for YouTube metadata (`hl`), or [SYSTEM]. */
    val contentLanguage: String = SYSTEM,
    /** ISO 3166 country for YouTube charts and availability (`gl`), or [SYSTEM]. */
    val contentCountry: String = SYSTEM,
    val addToPlaylistPosition: AddToPlaylistPosition = AddToPlaylistPosition.END,
    val showArtistDescription: Boolean = true,
    val showArtistSubscriberCount: Boolean = true,
    val showMonthlyListeners: Boolean = true,
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.HTTP,
    /** host:port */
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val disabledLyricsProviders: Set<LyricsProviderId> = emptySet(),
    val romanizationEnabled: Boolean = false,
    val romanizedScripts: Set<RomanizationScript> = RomanizationScript.entries.toSet(),
    val showMostStatsPlaylists: Boolean = true,
    val showWrappedCard: Boolean = false,
    val randomizeHomeOrder: Boolean = false,
    /** Length of the "Top" auto playlist. */
    val topLength: Int = 50,
    val quickPicksMode: QuickPicksMode = QuickPicksMode.QUICK_PICKS
) {
    val hasProxyAuth: Boolean get() = proxyUsername.isNotBlank()

    companion object {
        const val SYSTEM = "system"
    }
}
