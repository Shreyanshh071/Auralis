package com.auralis.music.windows.dev

import com.auralis.music.desktop.playback.*
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.together.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Muted local audio only: verifies the real controller alongside an idle room service. */
fun main() {
    val result = runCatching { runBlocking {
        val file = File("build/controls-check.wav")
        val bytes = ByteArray(44 + 8000 * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(bytes.size - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes.size - 44)
        }
        file.writeBytes(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = checkNotNull(VlcAudioPlayer.createOrNull())
        val player = DesktopPlaybackController(audio, scope, offlineFile = { file })
        DesktopTogether(player, FirestoreRest("unused", "unused") { _, _, _, _ -> error("Unexpected network") }, scope)
        val tracks = (0..9).map { Track(id = "local-$it", title = "Silent check $it", artist = "Test") }
        suspend fun await(message: String, condition: () -> Boolean) {
            withTimeout(10_000) { while (!condition()) delay(20) }
            println("PASS: $message")
        }
        try {
            player.setVolume(0f)
            check(!player.roomActive.value && player.roomControl == null)
            player.autoLoadMore = false
            player.playFrom(tracks[0], tracks, automatic = false)
            await("local playback starts") { player.state.value == VlcAudioPlayer.State.PLAYING }
            player.shuffleUpcoming()
            repeat(5) { if (player.queue.value.tracks == tracks) player.shuffleUpcoming() }
            check(player.queue.value.current == tracks[0])
            check(player.queue.value.tracks != tracks && player.queue.value.tracks.toSet() == tracks.toSet())
            println("PASS: idle room service allows shuffle; current song and queue members preserved")
            player.cycleRepeatMode(); check(player.repeatMode.value == DesktopPlaybackController.RepeatMode.ALL)
            val repeatFetches = java.util.concurrent.atomic.AtomicInteger()
            player.radioCandidates = { repeatFetches.incrementAndGet(); tracks.drop(1) }
            player.autoLoadMore = true
            player.playSingle(tracks[0])
            var session = player.session.value
            await("repeat all restarts at queue end") { player.session.value > session }
            player.cycleRepeatMode(); check(player.repeatMode.value == DesktopPlaybackController.RepeatMode.ONE)
            session = player.session.value
            await("repeat one restarts the same song") { player.session.value > session && player.queue.value.current == tracks[0] }
            check(repeatFetches.get() == 0 && player.queue.value.tracks.size == 1)
            println("PASS: repeat modes do not fetch or extend the queue")
            player.autoLoadMore = false
            player.cycleRepeatMode(); check(player.repeatMode.value == DesktopPlaybackController.RepeatMode.OFF)
            await("repeat off ends playback") { player.state.value == VlcAudioPlayer.State.ENDED }
            player.roomControl = { _, _, _ -> true }
            check(player.roomActive.value)
            player.cycleRepeatMode(); check(player.repeatMode.value == DesktopPlaybackController.RepeatMode.OFF)
            player.roomControl = null
            check(!player.roomActive.value)
            player.cycleRepeatMode(); check(player.repeatMode.value == DesktopPlaybackController.RepeatMode.ALL)
            println("PASS: room connection locks controls and leaving restores them")
            player.cycleRepeatMode(); player.cycleRepeatMode()
            player.radioCandidates = { tracks.drop(1) }
            player.autoLoadMore = true
            player.playSingle(tracks[0])
            await("idle room service allows automatic recommendations") { player.queue.value.tracks.size == tracks.size }
        } finally { scope.cancel(); player.release(); file.delete() }
    } }
    result.exceptionOrNull()?.printStackTrace()
    kotlin.system.exitProcess(if (result.isSuccess) 0 else 1)
}
