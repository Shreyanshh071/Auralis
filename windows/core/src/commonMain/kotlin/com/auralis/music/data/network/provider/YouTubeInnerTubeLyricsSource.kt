package com.auralis.music.data.network.provider

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeInnerTubeLyricsSource(
    private val innerTubeClient: InnerTubeClient = InnerTubeClient()
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.YOUTUBE
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.PLAIN)

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val videoId = query.videoId ?: return@withContext null
        if (videoId.isBlank() || videoId.startsWith("sp_")) return@withContext null

        try {
            val lyrics = innerTubeClient.getYouTubeMusicLyrics(videoId) ?: return@withContext null
            if (lyrics.lines.isEmpty()) return@withContext null

            LyricsCandidate(
                lyricsData = lyrics,
                confidence = 80,
                syncType = SyncType.PLAIN,
                provider = LyricsProvider.YOUTUBE
            )
        } catch (_: Exception) {
            null
        }
    }
}
