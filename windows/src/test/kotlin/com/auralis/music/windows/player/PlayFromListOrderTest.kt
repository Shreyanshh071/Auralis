package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.*
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import java.io.File
import javax.sound.sampled.*
import kotlin.test.*

class PlayFromListOrderTest {
    @Test fun pickingASongFromTheListAlreadyPlayingKeepsTheListOrder() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("play-from-order").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val native = File(System.getProperty("user.home"), "AppData/Local/Auralis/app/resources/vlc")
        val player = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val wave = File(dir, "silent.wav")
        AudioInputStream(java.io.ByteArrayInputStream(ByteArray(8 * 8000 * 2)), AudioFormat(8000f, 16, 1, true, false), 64000)
            .use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, wave) }
        val playback = DesktopPlaybackController(player, scope, offlineFile = { wave })
        playback.autoLoadMore = false; playback.preloadNext = false; playback.setVolume(0f)
        try {
            val playlist = (0 until 6).map { Track("local:$it", "Song $it", "Artist", duration = 8) }
            playback.playFrom(playlist[0], playlist, automatic = false)
            // The same playlist, a song further down: the next song is the one after it, not song 0.
            playback.playFrom(playlist[4], playlist, automatic = false)
            val queue = playback.queue.value
            assertEquals(playlist.map { it.id }, queue.tracks.map { it.id })
            assertEquals("local:4", queue.current?.id)
            assertEquals("local:5", queue.tracks.getOrNull(queue.index + 1)?.id)
            // Picking further down the queue itself still keeps the skipped, unheard songs.
            playback.playFrom(playlist[0], playlist, automatic = false)
            playback.playAt(3)
            val jumped = playback.queue.value
            assertEquals("local:3", jumped.current?.id)
            assertEquals("local:1", jumped.tracks.getOrNull(jumped.index + 1)?.id)
        } finally { playback.release(); scope.coroutineContext[Job]?.cancelAndJoin(); dir.deleteRecursively() }
    }
}
