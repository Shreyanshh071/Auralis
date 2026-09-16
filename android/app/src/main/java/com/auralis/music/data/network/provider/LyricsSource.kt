package com.auralis.music.data.network.provider

import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType

data class LyricsSearchQuery(
    val title: String,
    val artist: String,
    val durationSec: Long? = null,
    val videoId: String? = null,
    val album: String? = null,
    val channelTitle: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val spotifyId: String? = null,
    val appleMusicId: String? = null,
    val audioLeadingSilenceMs: Long? = null
)

data class LyricsCandidate(
    val lyricsData: LyricsData,
    val confidence: Int,
    val syncType: SyncType,
    val provider: LyricsProvider,
    val isExactVideoMatch: Boolean = false,
    val matchedVideoId: String? = null
)

interface LyricsSource {
    val provider: LyricsProvider
    val supportedSyncTypes: Set<SyncType>

    suspend fun search(query: LyricsSearchQuery): LyricsCandidate?
}
