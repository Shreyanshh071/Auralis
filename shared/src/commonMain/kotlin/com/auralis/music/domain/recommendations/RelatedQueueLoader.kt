package com.auralis.music.domain.recommendations

import com.auralis.music.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Queue saving and playlist ownership never decide whether autoplay can refill. */
object AutoQueuePolicy {
    fun canExtend(settings: PlayerSettings, state: QueueState, guest: Boolean): Boolean =
        settings.autoLoadMore && state.queue.isNotEmpty() && !guest

    fun needsRefill(state: QueueState): Boolean = state.queue.size - state.currentIndex - 1 < 4
}

/** Song-radio results are the similarity signal: never pad a genre queue with random history. */
object RelatedQueueLoader {
    fun fresh(seed: Track, candidates: List<Track>, existing: List<Track>, limit: Int = 14): List<Track> {
        val ids = (existing.map { it.id } + seed.id).toMutableSet()
        fun title(track: Track) = TrackDeduplicator.extractBaseSongTitle(track.title).lowercase().trim()
        val titles = (existing + seed).map(::title).filter { it.isNotBlank() }.toMutableSet()
        return buildList {
            for (track in candidates) {
                val base = title(track)
                if (track.id.isBlank() || track.id in ids || base.isNotBlank() && base in titles) continue
                add(track)
                ids += track.id
                if (base.isNotBlank()) titles += base
                if (size >= limit) break
            }
        }
    }

    suspend fun load(
        seed: Track,
        existing: List<Track>,
        radio: suspend (Track) -> List<Track>,
        artistSongs: suspend (String) -> List<Track>
    ): List<Track> {
        suspend fun bounded(timeout: Long, request: suspend () -> List<Track>): List<Track> = try {
            withTimeoutOrNull(timeout) { request() } ?: emptyList()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val related = bounded(15_000L) { radio(seed) }
        var batch = fresh(seed, related, existing)
        val artists = SimilarSeedPlanner.splitArtistCredit(seed.artist)
        if (batch.isEmpty() && artists.isNotEmpty()) {
            val sameArtist = bounded(6_000L) { artistSongs(artists.first()) }.filter { candidate ->
                SimilarSeedPlanner.splitArtistCredit(candidate.artist).any { name ->
                    artists.any { SimilarSeedPlanner.isSameArtist(it, name) }
                }
            }
            batch = fresh(seed, batch + sameArtist, existing)
        }
        // A long session may exhaust this song's radio. Explore only seeds its radio related to it.
        if (batch.isEmpty()) {
            for (neighbor in related.filter { it.id != seed.id }.distinctBy { it.id }.take(2)) {
                batch = fresh(seed, bounded(10_000L) { radio(neighbor) }, existing)
                if (batch.isNotEmpty()) break
            }
        }
        return batch
    }
}
