package com.auralis.music.windows.dev

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.DesktopStreamResolver
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*

/**
 * The stall watchdog: the first stream handed to the player is a local server that accepts the
 * connection and never sends a byte (what a stalled YouTube stream looks like to libVLC); the
 * song must recover by itself on a fresh lookup. Silent (volume 0), no window, no app data.
 * usage: probeStall -Pargs="vlcDir::videoId"
 */
fun main(rawArgs: Array<String>) {
    runBlocking { probeStall(rawArgs) }
    kotlin.system.exitProcess(0)
}

private suspend fun probeStall(rawArgs: Array<String>) = coroutineScope {
    val args = rawArgs.flatMap { it.split("::") }.filter { it.isNotBlank() }
    val (vlcDir, id) = args
    val silent = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        createContext("/") { Thread.sleep(120_000) }
        start()
    }
    val blackhole = "http://127.0.0.1:${silent.address.port}/stall?clen=4000000&mime=audio%2Fwebm"
    var lookups = 0
    val player = checkNotNull(VlcAudioPlayer.createOrNull(java.io.File(vlcDir)))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val playback = DesktopPlaybackController(player, scope, resolveStream = { videoId ->
        lookups++
        if (lookups == 1) blackhole else DesktopStreamResolver.resolve(videoId)
    })
    playback.setVolume(0f)
    playback.preloadNext = false
    try {
        val started = System.nanoTime()
        playback.playFrom(Track(id = id, title = id, artist = ""), emptyList())
        val ms = withTimeoutOrNull(40_000) {
            while (playback.state.value != VlcAudioPlayer.State.PLAYING || playback.positionMs.value <= 0L) delay(20)
            (System.nanoTime() - started) / 1_000_000
        }
        println(if (ms != null) "STALL PASS: recovered and playing after ${ms}ms ($lookups lookups)"
            else "STALL FAIL: still ${playback.state.value} after 40s ($lookups lookups)")
    } finally {
        playback.release()
        scope.cancel()
        silent.stop(0)
    }
}
