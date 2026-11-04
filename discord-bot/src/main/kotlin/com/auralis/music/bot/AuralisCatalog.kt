package com.auralis.music.bot

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.UserSearchRequest
import com.auralis.music.data.network.YouTubePlaylistImporter
import com.auralis.music.desktop.playback.DesktopPlaybackMatch
import com.auralis.music.desktop.playback.DesktopStreamResolver
import com.auralis.music.desktop.playback.LocalStreamRelay
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.Track
import java.io.IOException

/** A playlist or album in a link, and the song the link pointed at, if any. */
data class PlaylistLink(val id: String, val startVideoId: String?)

/** What a request came to: one song, or a playlist's songs. */
data class Selection(val tracks: List<Track>, val playlistTitle: String? = null, val shuffled: Boolean = false)

/** The link's playlist can't be read: private, deleted, or not a playlist at all. */
class PlaylistUnavailableException : Exception()

/** What the bot asks of Auralis: the same search, stream lookup, radio and lyrics the apps use. */
class AuralisCatalog {
    private val innerTube = InnerTubeClient()
    private val lyricsClient by lazy { LyricsClient() }
    private val playlists by lazy { YouTubePlaylistImporter() }

    /**
     * YouTube Music's Songs results, as the apps search, or null when it couldn't be reached.
     * Without the filter its top results can be music videos. A song only out as a video still plays.
     */
    suspend fun search(query: String): List<Track>? {
        val songs = UserSearchRequest.run { innerTube.search(query, InnerTubeClient.FILTER_SONGS) }
        if (songs.requestFailed) return null
        if (songs.songs.isNotEmpty()) return songs.songs.preferSongs()
        val general = UserSearchRequest.run { innerTube.search(query) }
        return general.songs.preferSongs().takeUnless { general.requestFailed }
    }

    private fun List<Track>.preferSongs(): List<Track> = filterNot { it.isMusicVideo }.ifEmpty { this }

    /**
     * What `auplay` text means: a playlist link, a song link, or a search. Null when a search found
     * nothing; throws when YouTube Music is unreachable or a playlist link can't be read.
     */
    suspend fun select(text: String): Selection? {
        val target = withoutShuffle(text)
        val link = playlistLink(target)
        if (link != null) {
            playlist(link, shuffled = asksToShuffle(text))?.let { return it }
            return fromLink(target)?.let { Selection(listOf(it)) } ?: throw PlaylistUnavailableException()
        }
        fromLink(target)?.let { return Selection(listOf(it)) }
        val songs = search(target) ?: throw IOException("YouTube Music couldn't be reached")
        return songs.firstOrNull()?.let { Selection(listOf(it)) }
    }

    /** The song behind a YouTube or YouTube Music link, or null when [text] isn't one. */
    suspend fun fromLink(text: String): Track? {
        val id = VIDEO_LINK.find(text)?.groupValues?.get(1) ?: return null
        return innerTube.getQueue(listOf(id)).firstOrNull()
    }

    /** A playlist's songs from its linked song onwards, wrapping round, or all of them [shuffled]. */
    suspend fun playlist(link: PlaylistLink, shuffled: Boolean = false): Selection? {
        val playlist = playlists.importPlaylistById(link.id) ?: return null
        val start = playlist.tracks.indexOfFirst { it.id == link.startVideoId }.coerceAtLeast(0)
        val ordered = if (shuffled) playlist.tracks.shuffled() else playlist.tracks.drop(start) + playlist.tracks.take(start)
        return Selection(ordered, playlist.title, shuffled)
    }

    /** The top YouTube Music playlist for [name]; null when nothing readable matches. */
    suspend fun findPlaylist(name: String): Selection? {
        val results = UserSearchRequest.run { innerTube.search(name, InnerTubeClient.FILTER_PLAYLISTS) }
        if (results.requestFailed) throw IOException("YouTube Music couldn't be reached")
        return results.playlists.firstOrNull()?.let { playlist(PlaylistLink(it.id, null)) }
    }

    suspend fun radio(seed: Track): List<Track> = innerTube.getRadioTracks(seed.id, seed.artist, seed.title).preferSongs()

    suspend fun lyrics(track: Track): LyricsData? = lyricsClient.getLyrics(
        title = track.title, artist = track.artist, durationSec = track.duration.takeIf { it > 0 }, videoId = track.id)

    /**
     * A loopback URL for the song's audio, trying another upload of the recording when this one
     * can't be played. The relay is what keeps YouTube serving a whole song, as it does for libVLC.
     */
    suspend fun streamUrl(track: Track): String? {
        val direct = DesktopStreamResolver.resolve(track.id)
        val (id, url) = if (direct != null) track.id to direct
        else DesktopPlaybackMatch.alternative(track, track.id) { DesktopStreamResolver.resolve(it) } ?: return null
        return LocalStreamRelay.register(url) { DesktopStreamResolver.refreshBlocking(id) }
    }

    companion object {
        private val VIDEO_LINK = Regex("""(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})""")
        private val YOUTUBE_LINK = Regex("""(?:https?://)?(?:[\w-]+\.)?(?:youtube\.com|youtu\.be)/\S+""", RegexOption.IGNORE_CASE)

        /** `shuffle <anything>`. The word alone isn't a request, so a song called Shuffle still plays. */
        fun asksToShuffle(text: String): Boolean = text.trimStart().startsWith("shuffle ", ignoreCase = true)

        /** The request itself: a single song after `shuffle` plays like any other. */
        fun withoutShuffle(text: String): String =
            if (asksToShuffle(text)) text.trimStart().drop("shuffle".length).trim() else text

        /**
         * The playlist in a YouTube link, or null. Only a real link is handed to the importer,
         * which reads any text starting with PL, FL or LM as an ID ("FLOWERS" would be a playlist).
         * Mixes (RD...) are generated per listener, so those links stay plain song links; curated
         * YouTube Music lists (RDCLAK...) are real playlists.
         */
        fun playlistLink(text: String): PlaylistLink? {
            val url = YOUTUBE_LINK.find(text)?.value ?: return null
            val id = YouTubePlaylistImporter.extractPlaylistId(url) ?: return null
            if (id.startsWith("RD") && !id.startsWith("RDCLAK")) return null
            return PlaylistLink(id, VIDEO_LINK.find(url)?.groupValues?.get(1))
        }
    }
}
