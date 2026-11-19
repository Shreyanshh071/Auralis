package com.auralis.music.windows.dev

import com.auralis.music.desktop.playback.CanvasVideoEngine
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.player.CanvasLookup
import com.auralis.music.windows.player.CanvasSource
import com.auralis.music.windows.player.SpotifyCanvasLookup
import com.auralis.music.windows.player.TrackCanvasLookup
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.graphics.toAwtImage

/**
 * Animated covers check, no window or sound: looks a song up on each source, then plays the
 * found video silently for a few seconds and counts the frames that arrive.
 * usage: probeCanvas -Pargs="Title::Artist[::Album]" (spaces as '+'), or -Pargs="spotify:track:<id>";
 * A local video path can also be supplied to check cached-loop playback.
 * -PdataDir=<dir> keeps its cache out of the app's
 * Recording discovery without lyrics: -Pargs="stage::Title::Artist::DurationSeconds::Album[::SpotifyId]"
 */
fun main(rawArgs: Array<String>): Unit = runBlocking {
    val localVideo = rawArgs.singleOrNull()?.let { java.io.File(it) }?.takeIf { it.isFile }
    val args = rawArgs.flatMap { it.split("::") }.map { it.replace('+', ' ') }
    val stage = args.firstOrNull() == "stage"
    val track = if (stage) Track(id = "probe", title = args[1], artist = args[2],
        duration = args[3].toLong(), album = args.getOrNull(4)?.takeIf(String::isNotBlank))
    else Track(id = "probe", title = args.getOrElse(0) { "Blinding Lights" }, artist = args.getOrElse(1) { "The Weeknd" },
        album = args.getOrNull(2)?.takeIf { it.isNotBlank() })
    // libVLC from the installed VLC (the app finds its bundled copy the same way).
    checkNotNull(com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull()) { "No libVLC" }
    var url: String? = null
    val spotifyId = com.auralis.music.data.network.SpicyRecordingMatcher.spotifyId(args.firstOrNull())
    if (localVideo != null) {
        url = localVideo.absolutePath
    } else if (stage) {
        val canvas = TrackCanvasLookup.find(track, args.getOrNull(5))
        println("STAGE recording='${track.title}' spotifyId=${canvas?.spotifyId} canvas=${canvas?.path ?: "unavailable from provider"}")
        url = canvas?.path ?: kotlin.system.exitProcess(0)
    } else if (spotifyId != null) {
        url = SpotifyCanvasLookup.find(spotifyId)
        checkNotNull(url) { "No playable Spotify Canvas" }
        println("Spotify Canvas downloaded: $url")
    } else for (source in listOf(CanvasSource.APPLE, CanvasSource.TIDAL, CanvasSource.CURATED)) {
        val found = CanvasLookup.find(track.copy(id = "probe-${source.name}"), source)
        println("SOURCE ${source.label}: ${found ?: "none"}")
        if (url == null) url = found
    }
    val video = url ?: kotlin.system.exitProcess(0)
    val frames = java.util.concurrent.atomic.AtomicInteger()
    val changes = java.util.concurrent.atomic.AtomicInteger()
    var lastPixels: IntArray? = null
    val firstFrame = java.util.concurrent.CountDownLatch(1)
    val size = java.util.concurrent.atomic.AtomicReference("")
    val playing = java.util.concurrent.atomic.AtomicBoolean(true)
    val clock = object : com.auralis.music.data.service.PlaybackClockSource {
        override fun rawPositionMs() = 0L
        override fun isPlaying() = playing.get()
        override fun speed() = 1f
    }
    val stop = CanvasVideoEngine.play(video, clock = clock, loop = true,
        frameShortSide = if (spotifyId != null || stage) 1440 else 960) {
        frames.incrementAndGet()
        // VLC redisplays an unchanged frame while paused, so callbacks alone aren't motion.
        if (frames.get() % 10 == 1) {
            val image = it.toAwtImage()
            val pixels = IntArray(64) { index ->
                image.getRGB((index % 8 + 1) * image.width / 9, (index / 8 + 1) * image.height / 9)
            }
            if (lastPixels?.contentEquals(pixels) != true) changes.incrementAndGet()
            lastPixels = pixels
        }
        size.set("${it.width}x${it.height}")
        firstFrame.countDown()
    }
    println("ENGINE available=${CanvasVideoEngine.available} started=${stop != null}")
    try {
        check(firstFrame.await(20, java.util.concurrent.TimeUnit.SECONDS)) { "No first video frame" }
        // Exercise multiple complete loops: a four-second check missed videos freezing at EOF.
        repeat(5) { interval ->
            val before = frames.get()
            Thread.sleep(4000)
            val decoded = frames.get() - before
            println("FRAMES $decoded in interval ${interval + 1}, total ${frames.get()}, changes=${changes.get()}, size ${size.get()}")
            check(decoded > 15) { "Video stopped delivering frames during loop playback" }
        }
        playing.set(false)
        Thread.sleep(1000)
        val pausedChanges = changes.get()
        Thread.sleep(1000)
        check(changes.get() - pausedChanges <= 2) { "Canvas kept moving while the song was paused" }
        val pausedFrames = frames.get()
        playing.set(true)
        Thread.sleep(4000)
        println("RESUME callbacks=${frames.get() - pausedFrames} imageChanges=${changes.get() - pausedChanges}")
        check(changes.get() - pausedChanges > 2) { "Canvas did not resume with the song" }
        println("PAUSE/RESUME passed")
    } finally {
        stop?.invoke()
    }
    Thread.sleep(500)
    kotlin.system.exitProcess(0)
}
