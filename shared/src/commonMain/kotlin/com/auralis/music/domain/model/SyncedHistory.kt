package com.auralis.music.domain.model

import com.auralis.music.domain.recommendations.TrackDeduplicator

/** Merge actual listen times, never the time a device downloaded them. */
fun mergeSyncedHistory(existing: List<HistoryEntry>, incoming: List<HistoryEntry>, cap: Int = 100): List<HistoryEntry> {
    val result = mutableListOf<HistoryEntry>()
    val fingerprints = mutableListOf<com.auralis.music.domain.recommendations.SongFingerprint>()
    for (entry in (existing + incoming).filter { it.track.id.isNotBlank() && it.playedAt > 0 }
        .sortedByDescending { it.playedAt }) {
        val fp = TrackDeduplicator.getSongFingerprint(entry.track)
        if (result.any { it.track.id == entry.track.id } || fingerprints.any {
                TrackDeduplicator.isDuplicateSong(it, fp, matchAlternateVersions = true)
            }) continue
        result += entry
        fingerprints += fp
        if (result.size >= cap) break
    }
    return result
}
