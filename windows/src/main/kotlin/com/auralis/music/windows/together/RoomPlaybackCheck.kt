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
        try { withTimeout(35_000) { while (!condition()) delay(100) } }
        catch (e: TimeoutCancellationException) {
            error("Failed $label; host=${host.state.value.message}, guest=${guest.state.value.message}; players=${hostPlayer.state.value}/${guestPlayer.state.value}; lengths=${hostPlayer.lengthMs.value}/${guestPlayer.lengthMs.value}")
        }
        println("PASS $label")
    }
    try {
        host.create("Temporary playback host")
        await("host creates test room") { host.state.value.code.isNotEmpty() }
        guest.join(host.state.value.code, "Temporary playback guest")
        await("guest joins test room") { guest.state.value.code.isNotEmpty() }
        host.updateHostSettings(com.auralis.music.data.sync.RoomSettings(requireApproval = true))
        await("all Android room rules reach guest") { guest.state.value.settings.requireApproval }
        val track = Track(id = "roomtest001", title = "Muted test audio", artist = "Auralis", duration = 180)
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
        guestPlayer.addToQueue(track.copy(id = "roomtest002", title = "Recommended test audio"))
        await("guest song reaches recommendations") { host.state.value.recommendations.any { it.track.id == "roomtest002" } }
        check(hostPlayer.queue.value.tracks.none { it.id == "roomtest002" })
        println("PASS approval prevents unapproved songs entering the queue")
        await("guest receives its recommendation") { guest.state.value.recommendations.any { it.track.id == "roomtest002" } }
        guest.upvote(guest.state.value.recommendations.first { it.track.id == "roomtest002" })
        await("guest can remove only its own vote") { host.state.value.recommendations.first { it.track.id == "roomtest002" }.upvotes.isEmpty() }
        host.upvote(host.state.value.recommendations.first { it.track.id == "roomtest002" })
        await("host vote preserves shared recommendation") { guest.state.value.recommendations.first { it.track.id == "roomtest002" }.upvotes.size == 1 }
        host.decide(host.state.value.recommendations.first { it.track.id == "roomtest002" }, true)
        await("host accepts song into queue") { hostPlayer.queue.value.tracks.any { it.id == "roomtest002" } }
        host.updateHostSettings(host.state.value.settings.copy(requireApproval = false, guestsCanPlaySongs = true))
        await("changing rules preserves playback permission") { guest.state.value.settings.guestsCanPlaySongs && guest.state.value.guestControls && !guest.state.value.settings.requireApproval }
        guestPlayer.addToQueue(track.copy(id = "roomtest003", title = "Automatically accepted song"))
        await("song adds without approval when Android rules allow") { hostPlayer.queue.value.tracks.any { it.id == "roomtest003" } }
        await("shared queue reaches guest before editing") { guestPlayer.queue.value.tracks.any { it.id == "roomtest003" } }
        guestPlayer.removeAt(guestPlayer.queue.value.tracks.indexOfFirst { it.id == "roomtest003" })
        await("guest queue removal reaches host") { hostPlayer.queue.value.tracks.none { it.id == "roomtest003" } }
        host.updateHostSettings(host.state.value.settings.copy(requireApproval = true, guestsCanPlaySongs = false))
        await("approval allows song requests with direct song control off") { guest.state.value.settings.requireApproval && !guest.state.value.settings.guestsCanPlaySongs }
        guestPlayer.next()
        await("guest skip reaches host as an approval request") { host.state.value.requests.any { it.command != null } }
        check(hostPlayer.queue.value.current?.id == track.id)
        host.decideRequest(host.state.value.requests.first { it.command != null }, true)
        await("approved song loads for host and guest") { hostPlayer.queue.value.current?.id == "roomtest002" && guestPlayer.queue.value.current?.id == "roomtest002" && guestPlayer.state.value == VlcAudioPlayer.State.PLAYING }
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
