package com.auralis.music.windows.dev

import com.auralis.music.desktop.playback.CanvasVideoEngine
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.player.CanvasLookup
import com.auralis.music.windows.player.CanvasSource
import com.auralis.music.windows.player.SpotifyCanvasLookup
import kotlinx.coroutines.runBlocking

/**
 * Animated covers check, no window or sound: looks a song up on each source, then plays the
 * found video silently for a few seconds and counts the frames that arrive.
 * usage: probeCanvas -Pargs="Title::Artist[::Album]" (spaces as '+'), or -Pargs="spotify:track:<id>";
 * -PdataDir=<dir> keeps its cache out of the app's
 */
fun main(rawArgs: Array<String>) = runBlocking {
    val args = rawArgs.flatMap { it.split("::") }.map { it.replace('+', ' ') }
    val track = Track(id = "probe", title = args.getOrElse(0) { "Blinding Lights" }, artist = args.getOrElse(1) { "The Weeknd" },
        album = args.getOrNull(2)?.takeIf { it.isNotBlank() })
    // libVLC from the installed VLC (the app finds its bundled copy the same way).
    checkNotNull(com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull()) { "No libVLC" }
    var url: String? = null
    val spotifyId = com.auralis.music.data.network.SpicyRecordingMatcher.spotifyId(args.firstOrNull())
    if (spotifyId != null) {
        url = SpotifyCanvasLookup.find(spotifyId)
        checkNotNull(url) { "No playable Spotify Canvas" }
        println("Spotify Canvas downloaded: $url")
    } else for (source in listOf(CanvasSource.APPLE, CanvasSource.TIDAL, CanvasSource.CURATED)) {
        val found = CanvasLookup.find(track.copy(id = "probe-${source.name}"), source)
        println("SOURCE ${source.label}: ${found ?: "none"}")
        if (url == null) url = found
    }
    val video = url ?: return@runBlocking
    val frames = java.util.concurrent.atomic.AtomicInteger()
    val firstFrame = java.util.concurrent.CountDownLatch(1)
    val size = java.util.concurrent.atomic.AtomicReference("")
    val stop = CanvasVideoEngine.play(video, frameShortSide = if (spotifyId != null) 1440 else 960) {
        frames.incrementAndGet()
        size.set("${it.width}x${it.height}")
        firstFrame.countDown()
    }
    println("ENGINE available=${CanvasVideoEngine.available} started=${stop != null}")
    try {
        check(firstFrame.await(20, java.util.concurrent.TimeUnit.SECONDS)) { "No first video frame" }
        Thread.sleep(4000)
        println("FRAMES ${frames.get()} in 4s after first frame, size ${size.get()}")
        check(frames.get() > 30) { "Video did not decode smoothly" }
    } finally {
        stop?.invoke()
    }
    Thread.sleep(500)
    kotlin.system.exitProcess(0)
}
