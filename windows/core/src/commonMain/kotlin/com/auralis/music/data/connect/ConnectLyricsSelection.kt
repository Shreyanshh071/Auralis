package com.auralis.music.data.connect

import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import kotlinx.coroutines.flow.MutableStateFlow

/** Only selection metadata is shared. Lyrics, credentials and local TTML files stay on-device. */
data class ConnectLyricsSelection(val trackId: String, val provider: LyricsProvider, val spotifyId: String? = null,
    val title: String? = null, val artist: String? = null, val durationMs: Long? = null)

object ConnectLyricsState {
    private val local = MutableStateFlow<ConnectLyricsSelection?>(null)
    fun publish(trackId: String?, lyrics: LyricsData?) {
        local.value = if (trackId == null || lyrics == null) null
            else ConnectLyricsSelection(trackId, lyrics.provider, lyrics.spotifyTrackId, lyrics.trackName, lyrics.artistName, lyrics.durationMs)
    }
    fun current(trackId: String?): ConnectLyricsSelection? = local.value?.takeIf { it.trackId == trackId }
}
