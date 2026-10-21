package com.auralis.music.windows.media

import com.auralis.music.desktop.playback.*
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.*
import kotlin.test.*

class SongNotificationPlaybackTest {
    @Test fun crossfadeNotifiesOnlyWhenTheIncomingStreamActuallyStarts() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("song-notification-playback").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val native = File(System.getProperty("user.home"), "AppData/Local/Auralis/app/resources/vlc")
        val player = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val wave = File(dir, "silent.wav")
        AudioInputStream(java.io.ByteArrayInputStream(ByteArray(30*8000*2)), AudioFormat(8000f,16,1,true,false),240000).use {
            AudioSystem.write(it,AudioFileFormat.Type.WAVE,wave)
        }
        val resolvingSecond = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val pb = DesktopPlaybackController(player, scope, offlineFile = { id ->
            if (id == "second") { resolvingSecond.countDown(); check(releaseSecond.await(10,TimeUnit.SECONDS)) }
            wave
        })
        pb.autoLoadMore = false; pb.preloadNext = false; pb.crossfadeSeconds = 3f; pb.setVolume(0f)
        val notices = java.util.concurrent.CopyOnWriteArrayList<Track>()
        val tracker = SongNotificationTracker(null)
        val collect = scope.launch {
            combine(pb.queue, pb.mediaState) { q, media ->
                tracker.started(q.current, media.state == VlcAudioPlayer.State.PLAYING && media.trackId == q.current?.id, true, true)
            }.collect { if (it != null) notices.add(it) }
        }
        try {
            val one = Track("first", "First", "Local")
            val two = Track("second", "Second", "Local")
            pb.playFrom(one,listOf(one,two))
            withTimeout(15000) { while (notices.size != 1) delay(25) }
            pb.next()
            assertTrue(resolvingSecond.await(3,TimeUnit.SECONDS))
            delay(250)
            assertEquals("second",pb.queue.value.current?.id)
            assertEquals("first",pb.mediaState.value.trackId)
            assertEquals(listOf("first"),notices.map { it.id }, "Old stream still playing must not announce the buffering song")
            releaseSecond.countDown()
            withTimeout(15000) { while (notices.size < 2) delay(25) }
            assertEquals(listOf("first","second"),notices.map { it.id })
            pb.setPaused(true)
            withTimeout(3000) { while (pb.state.value != VlcAudioPlayer.State.PAUSED) delay(25) }
            pb.setPaused(false)
            withTimeout(3000) { while (pb.state.value != VlcAudioPlayer.State.PLAYING) delay(25) }
            delay(100)
            assertEquals(2,notices.size,"Resume must not duplicate the song notification")
        } finally {
            releaseSecond.countDown(); collect.cancelAndJoin(); pb.release()
            scope.coroutineContext[Job]?.cancelAndJoin(); dir.deleteRecursively()
        }
    }
}
