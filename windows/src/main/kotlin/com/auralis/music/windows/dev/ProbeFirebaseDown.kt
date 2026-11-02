package com.auralis.music.windows.dev

import com.auralis.music.data.connect.ConnectBackend
import com.auralis.music.data.connect.ConnectDevice
import com.auralis.music.data.connect.ConnectDeviceType
import com.auralis.music.data.connect.ConnectEngine
import com.auralis.music.data.connect.ConnectPlatform
import com.auralis.music.data.connect.ConnectPlayIntent
import com.auralis.music.data.connect.ConnectSession
import com.auralis.music.data.connect.ConnectSnapshot
import com.auralis.music.data.connect.ConnectTrack
import com.auralis.music.data.connect.ConnectWrite
import com.auralis.music.data.connect.ServerClock
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.DesktopStreamResolver
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.connect.DesktopConnectPlayer
import kotlinx.coroutines.*

/**
 * Local playback with Firebase down: the real controller and libVLC at volume 0 (silent; no window,
 * no app data written), the real Connect engine, and a backend that fails or never answers. Each
 * case is timed against Connect being off, for an online stream and for a downloaded song.
 * usage: probeFirebaseDown -Pargs="vlcDir::videoId"
 */
fun main(rawArgs: Array<String>) {
    runBlocking { probe(rawArgs) }
    kotlin.system.exitProcess(0)
}

private fun silentWav(seconds: Int): ByteArray {
    val rate = 8_000
    val samples = rate * seconds
    return java.nio.ByteBuffer.allocate(44 + samples).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + samples); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(rate); putInt(rate); putShort(1); putShort(8)
        put("data".toByteArray()); putInt(samples); repeat(samples) { put(128.toByte()) }
    }.array()
}

private class DeadBackend(val hang: Boolean) : ConnectBackend {
    private suspend fun dead(): Nothing {
        if (hang) awaitCancellation()
        throw IllegalStateException("RESOURCE_EXHAUSTED")
    }
    override suspend fun fetch(): ConnectSnapshot = dead()
    override suspend fun update(block: (ConnectSnapshot) -> ConnectWrite?): ConnectSnapshot? = dead()
    override suspend fun heartbeat(device: ConnectDevice) = dead()
    override suspend fun removeDevice(id: String) = dead()
    override suspend fun readIntent(): ConnectPlayIntent? = dead()
    override suspend fun writeIntent(intent: ConnectPlayIntent) = dead()
}

private suspend fun probe(rawArgs: Array<String>) = coroutineScope {
    val args = rawArgs.flatMap { it.split("::") }.filter { it.isNotBlank() }
    val player = checkNotNull(VlcAudioPlayer.createOrNull(java.io.File(args[0]))) { "no libVLC in ${args[0]}" }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Stands in for a downloaded song, through the controller's real offline path.
    val offline = java.io.File.createTempFile("auralis-probe", ".wav").apply { deleteOnExit(); writeBytes(silentWav(seconds = 60)) }
    val downloaded = Track(id = "probe-downloaded", title = "Downloaded", artist = "")
    val playback = DesktopPlaybackController(player, scope, offlineFile = { id -> offline.takeIf { id == downloaded.id } })
    playback.setVolume(0f)
    playback.preloadNext = false
    DesktopStreamResolver.warmUp()
    delay(1500)
    val stream = Track(id = args[1], title = args[1], artist = "")

    suspend fun timeToPlaying(start: () -> Unit): Long {
        val started = System.nanoTime()
        start()
        val returned = (System.nanoTime() - started) / 1_000_000
        val playing = withTimeoutOrNull(30_000) {
            while (playback.state.value != VlcAudioPlayer.State.PLAYING || playback.positionMs.value <= 0L) delay(10)
            (System.nanoTime() - started) / 1_000_000
        } ?: -1L
        println("    control returned in ${returned}ms")
        return playing
    }

    suspend fun exercise(label: String) {
        println("== $label ==")
        playback.playFrom(stream, listOf(stream)).also { playback.connectSilence() }
        delay(300)
        println("  STREAM -> PLAYING in ${timeToPlaying { playback.playFrom(stream, listOf(stream)) }}ms")
        delay(1500)
        playback.togglePlayPause()
        delay(500)
        println("  PAUSE -> ${playback.state.value}")
        playback.togglePlayPause()
        delay(1000)
        println("  RESUME -> ${playback.state.value}")
        playback.seekTo(20_000)
        delay(1200)
        println("  SEEK 20s -> ${playback.positionMs.value}ms ${playback.state.value}")
        println("  DOWNLOADED -> PLAYING in ${timeToPlaying { playback.playFrom(downloaded, listOf(downloaded)) }}ms")
        delay(1000)
        println("  still ${playback.state.value} at ${playback.positionMs.value}ms")
        playback.connectSilence()
        delay(500)
    }

    try {
        exercise("Connect off")
        val other = ConnectSession(version = 7, ownerId = "phone", ownerName = "Phone", ownerType = ConnectDeviceType.PHONE, epoch = 3,
            track = ConnectTrack("elsewhere", "Elsewhere", "Artist"), playing = true, positionMs = 1_000L)
        for (hang in listOf(false, true)) for (live in listOf(false, true)) {
            val now = System::currentTimeMillis
            val elapsed = { System.nanoTime() / 1_000_000L }
            val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val engine = ConnectEngine(engineScope, DeadBackend(hang), DesktopConnectPlayer(playback, engineScope),
                ConnectDevice("probe", "Probe", platform = ConnectPlatform.WINDOWS, type = ConnectDeviceType.COMPUTER),
                ServerClock(now, elapsed), now, elapsed) { println("    engine: $it") }
            engine.start()
            engineScope.launch { while (isActive) { delay(1_000); engine.tick() } }
            // What a device knew before Firebase went: another device playing, or its lease lapsed.
            engine.onSnapshot(ConnectSnapshot(other.copy(leaseUntilMs = if (live) now() + 90_000L else now() - 1L)))
            delay(300)
            playback.connectControl = engine::intercept
            exercise("Firestore ${if (hang) "never answers" else "fails"}, other device ${if (live) "shown as playing" else "gone"}")
            playback.connectControl = null
            engine.stop(signOut = false)
            engineScope.cancel()
        }
    } finally {
        playback.release()
        scope.cancel()
    }
}
