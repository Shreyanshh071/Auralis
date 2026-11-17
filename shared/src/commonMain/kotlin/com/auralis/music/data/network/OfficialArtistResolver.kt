package com.auralis.music.data.network

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackArtist
import com.auralis.music.domain.model.TrackArtists
import com.auralis.music.domain.stats.ArtistCredits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The real artists of a label upload. A music video posted by "T-Series" credits only the label,
 * but the same song almost always exists on YouTube Music as an official song whose subtitle
 * links each artist. This finds that song by search and takes its artists, accepting it only when
 * it is clearly the same recording: same title, and a length that fits the video (or a singer the
 * video's title names).
 *
 * Lookups run one at a time in the background when a song plays (and for label uploads Stats
 * shows), never while Stats is counting. Each answer, found or not, is saved, so a video is looked
 * up once. [revision] ticks when a new answer arrives, so Stats can recount.
 */
object OfficialArtistResolver {
    private const val STORE_KEY = "artist_credits_official_v1"
    private const val MAX_SAVED = 5_000
    private const val NOT_FOUND = ""
    private const val MAX_QUEUE = 200
    private const val SPACING_MS = 1_500L
    private const val RETRY_AFTER_MS = 30 * 60_000L

    private val decisions = java.util.concurrent.ConcurrentHashMap<String, String>() // videoId -> encoded artists, "" = none
    private val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Lookups that failed (offline, YouTube errors) wait before trying again, so Stats recounting
    // every few seconds while music plays doesn't keep hitting the network.
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val queue = Channel<Track>(MAX_QUEUE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var store: com.auralis.music.util.KeyValueStore? = null
    private val workerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Builds the client lookups use; replaceable in tests. */
    @Volatile var clientFactory: () -> InnerTubeClient = { InnerTubeClient() }

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    /** Loads earlier answers and saves new ones to [kv]. Call once at startup. */
    fun persistTo(kv: com.auralis.music.util.KeyValueStore) {
        store = kv
        try {
            val saved = org.json.JSONObject(kv.getString(STORE_KEY, "{}").orEmpty().ifBlank { "{}" })
            for (id in saved.keys()) decisions.putIfAbsent(id, saved.optString(id))
        } catch (_: Exception) {
            // Unreadable answers are looked up again.
        }
    }

    /** The official song's artists for [videoId], or null when not looked up or none was found. */
    fun cached(videoId: String): List<TrackArtist>? {
        val encoded = decisions[videoId] ?: return null
        return TrackArtists.decode(encoded).takeIf { it.isNotEmpty() }
    }

    /** The artists Stats should credit for [track], best source first. */
    fun credits(track: Track): List<String> = ArtistCredits.forTrack(track, cached(track.id))

    /** Whether [track] is a label upload that hasn't been looked up yet. */
    fun needsLookup(track: Track): Boolean {
        if (!YOUTUBE_ID.matches(track.id) || track.title.isBlank()) return false
        if (decisions.containsKey(track.id)) return false
        failedAt[track.id]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MS) return false }
        return ArtistCredits.isUploaderCredit(track)
    }

    /** Queues a background lookup for [track] if it is an unresolved label upload. Never blocks. */
    fun request(track: Track) {
        if (!needsLookup(track) || !queued.add(track.id)) return
        if (queue.trySend(track).isFailure) {
            queued.remove(track.id)
            return
        }
        startWorker()
    }

    private fun startWorker() {
        if (!workerStarted.compareAndSet(false, true)) return
        scope.launch {
            for (track in queue) {
                try {
                    if (!decisions.containsKey(track.id)) {
                        val found = try {
                            lookUp(track)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null // Network trouble: try again on a later play, don't save "not found".
                        }
                        if (found != null) remember(track.id, found) else failedAt[track.id] = System.currentTimeMillis()
                    }
                } finally {
                    queued.remove(track.id)
                }
                delay(SPACING_MS)
            }
        }
    }

    private fun remember(videoId: String, artists: List<TrackArtist>) {
        val encoded = TrackArtists.encode(artists) ?: NOT_FOUND
        decisions[videoId] = encoded
        // Past the cap, drop some other answer (the map has no order); it is just looked up again.
        while (decisions.size > MAX_SAVED) decisions.keys.firstOrNull { it != videoId }?.let { decisions.remove(it) } ?: break
        store?.let { kv ->
            runCatching { kv.putStrings(mapOf(STORE_KEY to org.json.JSONObject(HashMap(decisions)).toString())) }
        }
        if (encoded.isNotEmpty()) _revision.value += 1
    }

    /**
     * Searches YouTube Music's songs for [track]'s title and returns the artists of the first
     * result that is the same recording, an empty list when there's clearly none, or null when
     * the search itself failed (so it's retried later).
     */
    suspend fun lookUp(track: Track, client: InnerTubeClient = clientFactory()): List<TrackArtist>? {
        val title = ArtworkIdentity.normalized(track.title)
        if (title.isBlank()) return emptyList()
        val singers = (track.artists.map { it.name }.filterNot(ArtistCredits::isLabel) + ArtistCredits.fromTitle(track.title))
            .mapNotNull(ArtistCredits::key).toSet()
        val hint = track.artists.firstOrNull { !ArtistCredits.isLabel(it.name) }?.name.orEmpty()
        val results = client.search("${track.title} $hint".trim(), InnerTubeClient.FILTER_SONGS).songs
        if (results.isEmpty()) return null
        val match = results.take(8).firstOrNull { candidate ->
            if (candidate.id == track.id) return@firstOrNull false
            if (ArtworkIdentity.normalized(candidate.title) != title) return@firstOrNull false
            val candidateArtists = candidate.artists.ifEmpty { ArtistCredits.split(candidate.artist).map { TrackArtist(it) } }
                .filterNot { ArtistCredits.isLabel(it.name) }
            if (candidateArtists.isEmpty()) return@firstOrNull false
            val sharesSinger = singers.isNotEmpty() && candidateArtists.any { ArtistCredits.key(it.name) in singers }
            sharesSinger || fitsVideo(track.duration, candidate.duration)
        } ?: return emptyList()
        return match.artists.filterNot { ArtistCredits.isLabel(it.name) }
            .ifEmpty { ArtistCredits.split(match.artist).filterNot(ArtistCredits::isLabel).map { TrackArtist(it) } }
    }

    // A music video often runs longer than the song (an intro, credits), never much shorter.
    private fun fitsVideo(videoSec: Long, songSec: Long): Boolean {
        if (videoSec <= 0 || songSec <= 0) return false
        val diff = videoSec - songSec
        return diff in -12L..90L
    }
}
