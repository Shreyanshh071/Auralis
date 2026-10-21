package com.auralis.music.windows.settings

import com.auralis.music.desktop.playback.*
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import java.io.File
import javax.sound.sampled.*
import kotlin.test.*

class CrossfadePlaybackTest {
    @Test fun localTracksOverlapAndPauseStopsTheOutgoingStream() = runBlocking {
        val dir=kotlin.io.path.createTempDirectory("crossfade-playback").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val native=File(System.getProperty("user.home"),"AppData/Local/Auralis/app/resources/vlc")
        val player=assertNotNull(VlcAudioPlayer.createOrNull(native))
        val wave=File(dir,"silent.wav")
        AudioInputStream(java.io.ByteArrayInputStream(ByteArray(8*8000*2)),AudioFormat(8000f,16,1,true,false),64000).use { AudioSystem.write(it,AudioFileFormat.Type.WAVE,wave) }
        val playback=DesktopPlaybackController(player,scope,offlineFile={wave})
        playback.autoLoadMore=false;playback.preloadNext=false;playback.setVolume(0f);playback.crossfadeSeconds=3f
        try {
            val one=Track("local:one","One","Local",duration=8);val two=one.copy(id="local:two",title="Two")
            playback.playFrom(one,listOf(one,two))
            withTimeout(15000) {while(playback.state.value!=VlcAudioPlayer.State.PLAYING) delay(25)}
            playback.next()
            val fading=VlcAudioPlayer::class.java.getDeclaredField("fading").apply {isAccessible=true}
            withTimeout(10000) {while(fading.get(player)==null || playback.state.value!=VlcAudioPlayer.State.PLAYING) delay(25)}
            assertNotNull(fading.get(player),"Outgoing native stream remains alive during the handoff")
            assertEquals("local:two",playback.queue.value.current?.id)
            delay(500)
            playback.setPaused(true)
            withTimeout(3000) {while(playback.state.value!=VlcAudioPlayer.State.PAUSED) delay(25)}
            assertNull(fading.get(player),"Pausing must stop the outgoing stream as well")
            playback.preventDuplicates=true;playback.addToQueue(two);playback.addToQueue(two)
            assertEquals(1,playback.queue.value.tracks.drop(playback.queue.value.index+1).count {it.id==two.id})
        } finally {playback.release();scope.coroutineContext[Job]?.cancelAndJoin();dir.deleteRecursively()}
    }
}
