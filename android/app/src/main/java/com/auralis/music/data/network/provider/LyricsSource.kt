package com.auralis.music.data.network.provider

import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType

data class LyricsSearchQuery(
    val title: String,
    val artist: String,
    val durationSec: Long? = null,
    val videoId: String? = null,
    val album: String? = null
)

data class LyricsCandidate(
    val lyricsData: LyricsData,
    val confidence: Int,
    val syncType: SyncType,
    val provider: LyricsProvider
)

interface LyricsSource {
    val provider: LyricsProvider
    val supportedSyncTypes: Set<SyncType>

    suspend fun search(query: LyricsSearchQuery): LyricsCandidate?
}
