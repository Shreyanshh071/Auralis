package com.auralis.music.windows.player

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.LocalizedContent
import com.auralis.music.desktop.library.DesktopBlockedArtists
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.HistoryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** The phone's radio provider, with listening history and liked songs when YouTube is unavailable. */
internal class DesktopRadioRecommendations(private val history: HistoryRepository) {
    private val client = InnerTubeClient()

    suspend fun candidates(seed: Track): List<Track> = withContext(Dispatchers.IO) {
        val radio = withTimeoutOrNull(5000) {
            LocalizedContent.run { client.getRadioTracks(seed.id, seed.artist, seed.title) }
        }.orEmpty()
        val known = history.getRecentHeavyRotation() + history.getHistory().first().map { it.track } + history.getLikedSeeds(50)
        val fallback = if (known.isNotEmpty()) known.shuffled() else if (radio.size < 14) {
            withTimeoutOrNull(2500) {
                client.search("${seed.artist} songs", InnerTubeClient.FILTER_SONGS).songs
            }.orEmpty()
        } else emptyList()
        (radio + fallback).filterNot { DesktopBlockedArtists.blocksTrack(it) }.distinctBy { it.id }
    }
}
