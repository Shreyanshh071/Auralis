package com.auralis.music.data.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Settings → Playback → "Prefer audio-only versions" (on by default).
 *
 * A song that reaches the queue as its official music video (from top songs, playlists, radio)
 * plays the video's soundtrack: skits before and after the song, and the video's own mix and
 * loudness. With this on, the song's audio-only release (YouTube Music's "Song" side, ATV) plays
 * in its place.
 *
 * Logged-out YouTube Music doesn't hand out its song/video link (`counterpart`), so the release
 * is found by search and accepted only when it's clearly the same recording: same title, same
 * artist, no version clash (remix, live, acoustic, sped up...), no uncredited feature, and a
 * length that fits inside the video. Anything less keeps the video: a wrong version would be worse.
 *
 * Each decision (swap or keep) is remembered, so a video is looked up once. The swap goes in
 * [RecordingMatches], which playback, the web engine, prefetch, downloads, Connect and lyrics
 * already follow.
 */
object AudioVersionPreference {
    private const val TAG = "AuralisAudioVersion"
    private const val PREFS = "auralis_audio_versions_v1"
    private const val KEY_ENABLED = "prefer_audio_only"
    private const val DECISION_PREFIX = "d:"
    const val MUSIC_VIDEO = "MUSIC_VIDEO_TYPE_OMV"

    /** Longest wait before playing the video as it is; the lookup is cached for next time. */
    private const val LOOKUP_TIMEOUT_MS = 3_500L

    private var prefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    /** videoId -> audio release id, or "" when the video has no safe audio release (or is one). */
    private val decisions = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        _enabled.value = store.getBoolean(KEY_ENABLED, true)
        store.all.forEach { (key, value) ->
            if (key.startsWith(DECISION_PREFIX) && value is String) decisions[key.removePrefix(DECISION_PREFIX)] = value
        }
        if (_enabled.value) applySwaps()
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        // Takes effect from the next song: the current one keeps playing what it started with.
        if (on) applySwaps() else clearSwaps()
    }

    // Stream URLs are cached under the video's own id too, so each switch drops them: otherwise
    // the cache would keep serving whichever sound played last.
    private fun applySwaps() {
        decisions.forEach { (video, audio) ->
            if (audio.isNotEmpty()) {
                AudioStreamResolver.invalidateStream(video)
                RecordingMatches.matchedVideoIds[video] = audio
            }
        }
    }

    private fun clearSwaps() {
        decisions.forEach { (video, audio) ->
            if (audio.isNotEmpty()) {
                AudioStreamResolver.invalidateStream(video)
                if (RecordingMatches.matchedVideoIds.remove(video, audio)) RecordingMatches.matchedDurations.remove(video)
            }
        }
    }

    private fun remember(videoId: String, audioId: String, audioDurationSec: Long = 0L) {
        decisions[videoId] = audioId
        prefs?.edit()?.putString(DECISION_PREFIX + videoId, audioId)?.apply()
        if (audioId.isNotEmpty() && _enabled.value) {
            AudioStreamResolver.invalidateStream(videoId)
            RecordingMatches.matchedVideoIds[videoId] = audioId
            if (audioDurationSec > 0L) RecordingMatches.matchedDurations[videoId] = audioDurationSec
        }
    }

    private fun isYouTubeId(id: String) = Regex("[A-Za-z0-9_-]{11}").matches(id)

    /**
     * The audio-only release to play for [track] instead of its music video, or null to play the
     * track's own id. Never throws; a slow or failed lookup plays the video this time.
     */
    suspend fun audioVersionFor(track: Track, client: InnerTubeClient = InnerTubeClient()): String? {
        if (!_enabled.value || !isYouTubeId(track.id)) return null
        decisions[track.id]?.let { return it.ifEmpty { null } }
        return try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookUp(track, client) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Audio version lookup failed for ${track.id}: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun lookUp(track: Track, client: InnerTubeClient): String? = withContext(Dispatchers.IO) {
        val entry = client.getQueueEntries(listOf(track.id)).firstOrNull()
            ?: return@withContext null // Unknown: ask again next time.
        if (entry.musicVideoType != MUSIC_VIDEO) {
            remember(track.id, "")
            return@withContext null
        }
        // get_queue's title and artist are YouTube Music's own, cleaner than an imported row's.
        val video = entry.track.copy(
            title = entry.track.title.ifBlank { track.title },
            // Every credited artist ("Luis Fonsi & Daddy Yankee"), so the release's credits aren't "uncredited".
            artist = entry.artists.distinct().joinToString(" & ").ifBlank {
                entry.track.artist.takeUnless { it.isBlank() || it == "YouTube Artist" } ?: track.artist
            },
            duration = entry.track.duration.takeIf { it > 0 } ?: track.duration
        )
        val query = "${video.title} ${primaryArtist(video.artist)}".trim()
        val found = client.search(query, InnerTubeClient.FILTER_SONGS).songs
        val audio = pickAudioVersion(video, found)
        if (audio == null) {
            Log.d(TAG, "Keeping the video for '${video.title}' (${track.id}): no safe audio release among ${found.size}")
            remember(track.id, "")
            return@withContext null
        }
        Log.d(TAG, "'${video.title}' plays its audio release ${audio.id} (${audio.duration}s) instead of video ${track.id} (${video.duration}s)")
        remember(track.id, audio.id, audio.duration)
        audio.id
    }

    private fun primaryArtist(artist: String): String =
        artist.split(Regex("[,&/]|\\b(feat|ft|with)\\b", RegexOption.IGNORE_CASE)).firstOrNull()?.trim().orEmpty().ifBlank { artist }

    /**
     * The search result that is the same recording as [video] without its video, or null.
     * Hard gates only, in YouTube Music's order otherwise: a wrong version plays the right words at
     * the wrong times. Length can only rule a candidate out, never excuse a version clash.
     */
    internal fun pickAudioVersion(video: Track, candidates: List<Track>): Track? {
        val artist = primaryArtist(video.artist)
        if (video.title.isBlank() || artist.isBlank() || artist == "YouTube Artist") return null
        // The video's length includes its intro and outro, so the duration gate is one-sided here.
        val versionTarget = video.copy(duration = 0L, album = null)
        return candidates.firstOrNull { c ->
            c.id != video.id && isYouTubeId(c.id) &&
                AudioStreamResolver.isSameSongTitle(video.title, c.title) &&
                SearchQueryMatcher.isAuthorMatch(c.artist, artist) &&
                SearchQueryMatcher.recordingMismatch(versionTarget, c.copy(duration = 0L)) == null &&
                fitsInsideVideo(video.duration, c.duration)
        }
    }

    /** Song length vs video length: the song may be shorter by the video's skits, never longer. */
    internal fun fitsInsideVideo(videoSec: Long, songSec: Long): Boolean {
        if (videoSec <= 0L || songSec <= 0L) return true
        return songSec <= videoSec + 8 && songSec * 2 >= videoSec && videoSec - songSec <= 150
    }
}
