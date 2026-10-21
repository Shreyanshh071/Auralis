package com.auralis.music.windows.dev

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.DesktopStreamResolver
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*

/**
 * How long songs take to start: for each video, a cold stream lookup and then the time from
 * playFrom to libVLC reporting PLAYING, through the real controller and relay at volume 0
 * (silent; no window, no app data written). Finally the same songs as next/previous skips.
 * usage: probeStartup -Pargs="vlcDir::id1::id2::..."
 */
fun main(rawArgs: Array<String>) {
    runBlocking { probe(rawArgs) }
    kotlin.system.exitProcess(0)
}

private suspend fun probe(rawArgs: Array<String>) = coroutineScope {
    val args = rawArgs.flatMap { it.split("::") }.filter { it.isNotBlank() }
    val vlcDir = java.io.File(args.first())
    val ids = args.drop(1)
    val player = checkNotNull(VlcAudioPlayer.createOrNull(vlcDir)) { "no libVLC in $vlcDir" }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val playback = DesktopPlaybackController(player, scope)
    playback.setVolume(0f)
    playback.preloadNext = false
    DesktopStreamResolver.warmUp()
    delay(1500)
    suspend fun timeToPlaying(start: () -> Unit): Long {
        val started = System.nanoTime()
        start()
        return withTimeoutOrNull(30_000) {
            while (playback.state.value != VlcAudioPlayer.State.PLAYING || playback.positionMs.value <= 0L) delay(10)
            (System.nanoTime() - started) / 1_000_000
        } ?: -1L
    }
    val tracks = ids.map { Track(id = it, title = it, artist = "") }
    try {
        println("== cold: lookup + start ==")
        for (track in tracks) {
            DesktopStreamResolver.invalidate(track.id)
            val ms = timeToPlaying { playback.playFrom(track, listOf(track)) }
            println("COLD ${track.id} -> PLAYING in ${ms}ms")
            delay(1500)
        }
        println("== warm (lookup cached): start only ==")
        for (track in tracks) {
            val ms = timeToPlaying { playback.playFrom(track, listOf(track)) }
            println("WARM ${track.id} -> PLAYING in ${ms}ms")
            delay(1500)
        }
        println("== next: skips to a pre-buffered next song ==")
        playback.preloadNext = true
        DesktopStreamResolver.clearCache()
        playback.playFrom(tracks.first(), tracks)
        withTimeoutOrNull(30_000) { while (playback.state.value != VlcAudioPlayer.State.PLAYING) delay(10) }
        repeat(tracks.size - 1) {
            delay(5000) // time for the next song's lookup and first seconds to arrive
            val id = playback.queue.value.tracks.getOrNull(playback.queue.value.index + 1)?.id
            val ms = timeToPlaying { playback.next() }
            println("NEXT $id -> PLAYING in ${ms}ms")
        }
    } finally {
        playback.release()
        scope.cancel()
    }
}
