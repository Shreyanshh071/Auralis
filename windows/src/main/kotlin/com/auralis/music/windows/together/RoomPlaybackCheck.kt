package com.auralis.music.windows.together

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Two muted players and an isolated room: never touches the user's app or music. */
fun main(args: Array<String>) {
    val result = runCatching { runBlocking {
    val bytes = ByteArray(44 + 8000 * 2 * 180)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(bytes.size - 8); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(bytes.size - 44)
    }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    server.createContext("/tone.wav") { exchange ->
        try {
            val range = Regex("bytes=(\\d+)-(\\d*)").find(exchange.requestHeaders.getFirst("Range").orEmpty())
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
            exchange.responseHeaders.set("Content-Type", "audio/wav")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${bytes.size}")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, (end - start + 1).toLong())
            exchange.responseBody.use { it.write(bytes, start, end - start + 1) }
        } finally { exchange.close() }
    }
    server.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hostPlayer = DesktopPlaybackController(checkNotNull(VlcAudioPlayer.createOrNull(null)), scope) { "http://127.0.0.1:${server.address.port}/tone.wav" }
    val guestPlayer = DesktopPlaybackController(checkNotNull(VlcAudioPlayer.createOrNull(null)), scope) { "http://127.0.0.1:${server.address.port}/tone.wav" }
    hostPlayer.setVolume(0f); guestPlayer.setVolume(0f)
    val host = DesktopTogether(hostPlayer, FirestoreRest.fromResource(), scope)
    val guest = DesktopTogether(guestPlayer, FirestoreRest.fromResource(), scope)
    suspend fun await(label: String, condition: () -> Boolean) {
        withTimeout(35_000) { while (!condition()) delay(100) }
        println("PASS $label")
    }
    try {
        host.create("Temporary playback host")
        await("host creates test room") { host.state.value.code.isNotEmpty() }
        guest.join(host.state.value.code, "Temporary playback guest")
        await("guest joins test room") { guest.state.value.code.isNotEmpty() }
        val track = Track(id = "room-playback-check", title = "Muted test audio", artist = "Auralis", duration = 180)
        hostPlayer.playFrom(track, listOf(track))
        await("guest follows host recording and starts audio") { guestPlayer.queue.value.current?.id == track.id && guestPlayer.state.value == VlcAudioPlayer.State.PLAYING }
        host.allowGuestControls(true)
        await("guest receives host permission") { guest.state.value.guestControls }
        guestPlayer.togglePlayPause()
        await("first guest pause command reaches host") { hostPlayer.state.value == VlcAudioPlayer.State.PAUSED }
        await("host pause reaches guest") { guestPlayer.state.value == VlcAudioPlayer.State.PAUSED }
        guestPlayer.seekTo(10_000)
        await("guest seek reaches host") { hostPlayer.clock.rawPositionMs() in 9500..10_500 }
        await("host seek reaches guest") { guestPlayer.clock.rawPositionMs() in 9500..10_500 }
        hostPlayer.setRate(1.5f)
        await("playback speed follows host") { guestPlayer.clock.speed() == 1.5f }
        guestPlayer.addToQueue(track.copy(id = "second-check", title = "Recommended test audio"))
        await("guest song reaches recommendations") { host.state.value.recommendations.any { it.track.id == "second-check" } }
        host.decide(host.state.value.recommendations.first { it.track.id == "second-check" }, true)
        await("host accepts song into queue") { hostPlayer.queue.value.tracks.any { it.id == "second-check" } }
        host.close()
        await("guest sees room end") { guest.state.value.code.isEmpty() }
    } finally {
        runCatching { host.close() }; runCatching { guest.close() }
        scope.cancel(); hostPlayer.release(); guestPlayer.release(); server.stop(0)
    }
    // The relay has a process-lifetime HTTP listener, as in the app.
    } }
    result.exceptionOrNull()?.printStackTrace()
    kotlin.system.exitProcess(if (result.isSuccess) 0 else 1)
}
