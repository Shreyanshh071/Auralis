package com.auralis.music.windows.dev

import com.auralis.music.desktop.playback.CanvasVideoEngine
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.player.CanvasLookup
import com.auralis.music.windows.player.CanvasSource
import kotlinx.coroutines.runBlocking

/**
 * Animated covers check, no window or sound: looks a song up on each source, then plays the
 * found video silently for a few seconds and counts the frames that arrive.
 * usage: probeCanvas -Pargs="Title::Artist[::Album]" (spaces as '+'); -PdataDir=<dir> keeps its cache out of the app's
 */
fun main(rawArgs: Array<String>) = runBlocking {
    val args = rawArgs.flatMap { it.split("::") }.map { it.replace('+', ' ') }
    val track = Track(id = "probe", title = args.getOrElse(0) { "Blinding Lights" }, artist = args.getOrElse(1) { "The Weeknd" },
        album = args.getOrNull(2)?.takeIf { it.isNotBlank() })
    // libVLC from the installed VLC (the app finds its bundled copy the same way).
    checkNotNull(com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull()) { "No libVLC" }
    var url: String? = null
    for (source in listOf(CanvasSource.APPLE, CanvasSource.TIDAL, CanvasSource.CURATED)) {
        val found = CanvasLookup.find(track.copy(id = "probe-${source.name}"), source)
        println("SOURCE ${source.label}: ${found ?: "none"}")
        if (url == null) url = found
    }
    val video = url ?: return@runBlocking
    var frames = 0; var size = ""
    val stop = CanvasVideoEngine.play(video) { frames++; size = "${it.width}x${it.height}" }
    println("ENGINE available=${CanvasVideoEngine.available} started=${stop != null}")
    Thread.sleep(8000)
    stop?.invoke()
    println("FRAMES $frames in 8s, size $size")
    Thread.sleep(500)
    kotlin.system.exitProcess(0)
}
