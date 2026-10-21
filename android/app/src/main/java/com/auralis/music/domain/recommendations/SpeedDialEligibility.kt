package com.auralis.music.domain.recommendations

/** Artist choices, likes, imports and song starts never count as measured listening. */
object SpeedDialEligibility {
    const val MIN_TRACKS = 6
    const val MIN_LISTEN_MS = 30_000L

    fun qualifiedTracks(times: Map<String, Long>): Set<String> = times.entries
        .mapNotNull { (id, ms) ->
            SpeedDialIdHelper.getCanonicalTrackId(id)?.takeIf { it.isNotBlank() }?.let { it to ms.coerceAtLeast(0) }
        }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.sum() >= MIN_LISTEN_MS }
        .keys

    fun ready(times: Map<String, Long>): Boolean = qualifiedTracks(times).size >= MIN_TRACKS
}
