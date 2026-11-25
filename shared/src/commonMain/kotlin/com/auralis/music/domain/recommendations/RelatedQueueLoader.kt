package com.auralis.music.domain.recommendations

import com.auralis.music.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /**
     * [anchors] are the songs the listener most recently played through (newest first). Each
     * refill is seeded by the playing song, which usually came from the previous radio, so radio
     * alone drifts a little further from what was picked on every refill. The anchors' radios
     * vote: songs several of them agree on are the closest match in genre and feel, and lead
     * the batch. A song in a different script from the playing one (Hindi vs Korean vs English)
     * sinks a little, never dropped. With no anchors the radio's own order is kept.
     */
    suspend fun load(
        seed: Track,
        existing: List<Track>,
        radio: suspend (Track) -> List<Track>,
        artistSongs: suspend (String) -> List<Track>,
        anchors: List<Track> = emptyList()
    ): List<Track> {
        suspend fun bounded(timeout: Long, request: suspend () -> List<Track>): List<Track> = try {
            withTimeoutOrNull(timeout) { request() } ?: emptyList()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val anchorSeeds = anchors.filter { it.id.isNotBlank() && it.id != seed.id }.distinctBy { it.id }.take(MAX_ANCHORS)
        val (related, anchorRadios) = coroutineScope {
            val cached = anchorSeeds.map { anchorRadioCache[it.id] }
            val anchorJobs = anchorSeeds.mapIndexed { i, anchor ->
                async { cached[i] ?: bounded(8_000L) { radio(anchor) } }
            }
            val seedRadio = async { bounded(15_000L) { radio(seed) } }
            val anchorResults = anchorJobs.awaitAll()
            // Written here, after the parallel requests, so the cache is only touched on one thread.
            anchorSeeds.forEachIndexed { i, anchor -> rememberAnchorRadio(anchor.id, anchorResults[i]) }
            seedRadio.await() to anchorResults.filter { it.isNotEmpty() }
        }
        var batch = fresh(seed, rank(seed, related, anchorRadios), existing)
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

    private const val MAX_ANCHORS = 2

    // Recent anchors' radios, so consecutive refills don't ask YouTube for the same radio again.
    private val anchorRadioCache = LinkedHashMap<String, List<Track>>()

    private fun rememberAnchorRadio(id: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        anchorRadioCache.remove(id)
        anchorRadioCache[id] = tracks
        while (anchorRadioCache.size > 6) anchorRadioCache.remove(anchorRadioCache.keys.first())
    }

    /**
     * Orders the playing song's radio by agreement with the anchors' radios. A song the anchors'
     * radios share but this radio lacks still joins, after the radio's own picks it agrees with.
     */
    fun rank(seed: Track, related: List<Track>, anchorRadios: List<List<Track>>): List<Track> {
        fun key(track: Track) = TrackDeduplicator.extractBaseSongTitle(track.title).lowercase().trim()
        val seedScript = dominantScript(seed.title)
        if (anchorRadios.isEmpty() && seedScript == null) return related
        val votes = HashMap<String, Int>()
        for (list in anchorRadios) {
            for (k in list.map { it.id }.toSet() + list.map(::key).filter { it.isNotBlank() }.toSet()) {
                votes[k] = (votes[k] ?: 0) + 1
            }
        }
        fun agreement(track: Track) = maxOf(votes[track.id] ?: 0, votes[key(track)] ?: 0)
        fun scriptPenalty(track: Track): Float {
            val other = dominantScript(track.title) ?: return 0f
            return if (seedScript != null && other != seedScript) 0.9f else 0f
        }
        val scored = related.mapIndexed { index, track ->
            val position = 1f - index.toFloat() / related.size.coerceAtLeast(1)
            Triple(track, position + agreement(track) * 0.8f - scriptPenalty(track), index)
        }
        // Shared by every anchor radio (or the only one) but missing from this radio.
        val needed = anchorRadios.size.coerceAtMost(2)
        val inRelated = related.map { it.id }.toSet() + related.map(::key).toSet()
        val extras = anchorRadios.flatten().distinctBy { it.id }
            .filter { it.id !in inRelated && key(it) !in inRelated && needed > 0 && agreement(it) >= needed }
            .mapIndexed { index, track -> Triple(track, agreement(track) * 0.8f - 0.5f - scriptPenalty(track), related.size + index) }
        return (scored + extras).sortedWith(compareByDescending<Triple<Track, Float, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    /** The writing system most of a title's letters use, or null for Latin/unknown-only text. */
    fun dominantScript(text: String): String? {
        val counts = HashMap<String, Int>()
        var latin = 0
        for (ch in text) {
            val c = ch.code
            val script = when (c) {
                in 0x0900..0x097F -> "devanagari"
                in 0x0980..0x09FF -> "bengali"
                in 0x0A00..0x0A7F -> "gurmukhi"
                in 0x0A80..0x0AFF -> "gujarati"
                in 0x0B80..0x0BFF -> "tamil"
                in 0x0C00..0x0C7F -> "telugu"
                in 0x0C80..0x0CFF -> "kannada"
                in 0x0D00..0x0D7F -> "malayalam"
                in 0x0600..0x06FF -> "arabic"
                in 0x0400..0x04FF -> "cyrillic"
                in 0x0E00..0x0E7F -> "thai"
                in 0x3040..0x30FF -> "japanese"
                in 0x1100..0x11FF, in 0xAC00..0xD7AF -> "korean"
                in 0x4E00..0x9FFF -> "han"
                else -> {
                    if (ch.isLetter() && c < 0x0250) latin++
                    null
                }
            } ?: continue
            counts[script] = (counts[script] ?: 0) + 1
        }
        val top = counts.maxByOrNull { it.value } ?: return if (latin > 0) "latin" else null
        // Japanese titles mix kana with Han characters; read them as one script.
        val name = if (top.key == "han" && (counts["japanese"] ?: 0) > 0) "japanese" else top.key
        return if (top.value >= latin) name else "latin"
    }
}
