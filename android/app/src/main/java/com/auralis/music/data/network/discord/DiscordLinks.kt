package com.auralis.music.data.network.discord

import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource

/** Link to the recording actually played; local files have no public listening link. */
internal fun discordListenUrl(track: Track): String {
    if (track.source == TrackSource.LOCAL) return ""
    val videoId = RecordingMatches.getMatchedVideoId(track.id) ?: track.id
    return if (videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
        "https://music.youtube.com/watch?v=$videoId"
    } else ""
}
