package com.auralis.music.windows.player

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.SpicyRecordingMatcher
import com.auralis.music.data.network.SpotifyPlaylistImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp

/** The recording's measured loudness, normalized against its own quiet and loud passages. */
internal class ArtworkEnergy private constructor(
    private val times: DoubleArray, private val levels: DoubleArray,
    private val quiet: Double, private val range: Double
) {
    fun at(seconds: Double): Float {
        val found = times.binarySearch(seconds)
        val index = if (found >= 0) found else (-found - 2).coerceIn(0, times.lastIndex)
        val next = (index + 1).coerceAtMost(times.lastIndex)
        val span = times[next] - times[index]
        val mix = if (span > 0) ((seconds - times[index]) / span).coerceIn(0.0, 1.0) else 0.0
        return ((levels[index] + (levels[next] - levels[index]) * mix - quiet) / range).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        fun parse(json: JSONObject, durationSeconds: Long): ArtworkEnergy? {
            val duration = json.optJSONObject("track")?.optDouble("duration") ?: return null
            if (!duration.isFinite() || durationSeconds <= 0 || abs(duration - durationSeconds) > 2.5) return null
            val segments = json.optJSONArray("segments") ?: return null
            val points = buildList {
                for (i in 0 until segments.length()) {
                    val segment = segments.optJSONObject(i) ?: continue
                    val start = segment.optDouble("start")
                    val length = segment.optDouble("duration")
                    val peakOffset = segment.optDouble("loudness_max_time")
                    val initial = segment.optDouble("loudness_start")
                    val peak = segment.optDouble("loudness_max")
                    if (!start.isFinite() || start < 0 || start > duration || !length.isFinite() || length <= 0 ||
                        !peakOffset.isFinite() || !initial.isFinite() || !peak.isFinite()) continue
                    add(start to initial)
                    add((start + peakOffset.coerceIn(0.0, length)).coerceAtMost(duration) to peak)
                }
            }.sortedBy { it.first }
            if (points.size < 4) return null
            val levels = points.map { it.second }.sorted()
            val quiet = levels[(levels.lastIndex * .10).toInt()]
            val loud = levels[(levels.lastIndex * .95).toInt()]
            val range = (loud - quiet).coerceAtLeast(6.0)
            return ArtworkEnergy(points.map { it.first }.toDoubleArray(), points.map { it.second }.toDoubleArray(),
                (quiet + loud - range) / 2, range)
        }
    }
}

internal object DynamicArtworkAnalysis {
    private val http by lazy {
        NetworkClientProvider.okHttpClient.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    }
    private data class Entry(val energy: ArtworkEnergy?, val expires: Long)
    private val cache = linkedMapOf<Pair<String, Long>, Entry>()

    suspend fun find(spotifyId: String?, duration: Long): ArtworkEnergy? = withContext(Dispatchers.IO) {
        val id = SpicyRecordingMatcher.spotifyId(spotifyId) ?: return@withContext null
        if (duration <= 0) return@withContext null
        val key = id to duration
        synchronized(cache) { cache[key] }?.takeIf { it.expires > System.currentTimeMillis() }
            ?.let { return@withContext it.energy }
        val energy = try {
            val embed = http.newCall(Request.Builder().url("https://open.spotify.com/embed/track/$id").build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
            val token = embed?.let(SpotifyPlaylistImporter::extractSessionTokenFromEmbed) ?: return@withContext null
            val request = Request.Builder()
                .url("https://spclient.wg.spotify.com/audio-attributes/v1/audio-analysis/$id?format=json")
                .header("Authorization", "Bearer $token").build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.let { ArtworkEnergy.parse(JSONObject(it), duration) }
                else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        synchronized(cache) {
            if (cache.size >= 32) cache.remove(cache.keys.first())
            cache[key] = Entry(energy, System.currentTimeMillis() + if (energy == null) 300_000L else 86_400_000L)
        }
        energy
    }
}

/** Integrates the motion so playback changes and seeks never jump the warp field. */
internal class ArtworkMotionClock {
    var time = 0f
        private set
    var scale = 1f
        private set
    private var energy = .35f
    private var play = 0f
    private var follow = 0f

    fun step(seconds: Float, playing: Boolean, measuredEnergy: Float?) {
        val dt = seconds.coerceIn(0f, .1f)
        fun ease(current: Float, target: Float, constant: Float) = current + (target - current) * (1f - exp(-dt / constant))
        play = ease(play, if (playing) 1f else 0f, .6f)
        follow = ease(follow, if (measuredEnergy != null) 1f else 0f, .8f)
        val target = measuredEnergy ?: .35f
        energy = ease(energy, target, if (target > energy) 1.1f else 2.8f)
        val analyzedRate = .66f + (2.76f - .66f) * energy
        val playingRate = 1.5f * (1.44f + (analyzedRate - 1.44f) * follow)
        time += dt * (.1f + (playingRate - .1f) * play)
        scale = 1f + .025f * 1.5f * energy * follow * play
    }
}
