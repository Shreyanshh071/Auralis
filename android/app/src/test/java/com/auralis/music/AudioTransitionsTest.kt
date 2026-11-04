package com.auralis.music

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.auralis.music.data.service.AudioTransitionController
import com.auralis.music.data.service.AudioTransitionPolicy
import com.auralis.music.data.service.automix.*
import com.auralis.music.domain.model.PlayerSettings
import com.auralis.music.domain.model.Track
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioTransitionsTest {
    private class Fixture(val scope: TestScope) {
        val primary = mockk<ExoPlayer>(relaxed = true)
        val secondary = mockk<ExoPlayer>(relaxed = true)
        val primaryListener = slot<Player.Listener>()
        val secondaryListener = slot<Player.Listener>()
        val outgoing = Track(id = "a", title = "A", artist = "Artist A", duration = 180)
        val next = Track(id = "b", title = "B", artist = "Artist B", duration = 200)
        val outgoingItem = MediaItem.Builder().setMediaId("a").build()
        val incomingItem = MediaItem.Builder().setMediaId("b").build()
        var currentItem = outgoingItem
        var index = 0
        var position = 174_750L
        var incomingState = Player.STATE_READY
        var volume = 1f
        var secondaryVolume = 0f
        var eligible = true
        val transitions = mutableListOf<Track>()
        val controller: AudioTransitionController

        init {
            every { primary.currentMediaItem } answers { currentItem }
            every { primary.currentMediaItemIndex } answers { index }
            every { primary.mediaItemCount } returns 2
            every { primary.getMediaItemAt(1) } returns incomingItem
            every { primary.duration } returns 180_000L
            every { primary.currentPosition } answers { position }
            every { primary.isPlaying } returns true
            every { primary.playWhenReady } returns true
            every { primary.playbackState } returns Player.STATE_READY
            every { primary.playbackParameters } returns PlaybackParameters.DEFAULT
            every { primary.volume } answers { volume }
            every { primary.volume = any() } answers { volume = firstArg() }
            every { primary.addListener(capture(primaryListener)) } just Runs
            every { primary.seekTo(1, any()) } answers { index = 1; currentItem = incomingItem; position = secondArg() }
            every { primary.seekTo(any<Long>()) } answers { position = firstArg() }
            every { secondary.playbackState } answers { incomingState }
            every { secondary.bufferedPosition } returns 30_000L
            every { secondary.currentPosition } returns 0L
            every { secondary.playWhenReady } returns true
            every { secondary.playbackParameters } returns PlaybackParameters.DEFAULT
            every { secondary.volume = any() } answers { secondaryVolume = firstArg() }
            every { secondary.addListener(capture(secondaryListener)) } just Runs
            controller = AudioTransitionController(primary, AutoMixFilterProcessor(), scope,
                createIncoming = { secondary }, createAnalyzer = { error("Fixed crossfade must not start analysis") },
                eligible = { eligible }, onTransition = { track, _ -> transitions += track },
                nanoTime = { scope.testScheduler.currentTime * 1_000_000L })
        }

        fun tick(settings: PlayerSettings = PlayerSettings(crossfadeEnabled = true), track: Track = next) {
            controller.tick(outgoing, track, 0, settings)
        }
    }

    @Test fun crossfadeOverlapsThenCommitsExactlyOneQueueAdvanceAndRestoresVolume() = runTest {
        val f = Fixture(this)
        f.tick()
        runCurrent()
        advanceTimeBy(2_500)
        runCurrent()
        assertTrue(f.volume in .1f.. .9f)
        assertTrue(f.secondaryVolume in .1f.. .9f)
        assertTrue(f.transitions.isEmpty())
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(f.next), f.transitions)
        assertEquals(1f, f.volume, 0f)
        assertFalse(f.controller.isMixing)
        verify(exactly = 1) { f.secondary.release() }
        f.controller.release()
    }

    @Test fun pauseCancelsBothOverlapAndFutureQueueCommit() = runTest {
        val f = Fixture(this)
        f.tick(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        f.primaryListener.captured.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        advanceTimeBy(8_000); runCurrent()
        assertTrue(f.transitions.isEmpty())
        assertEquals(1f, f.volume, 0f)
        verify(exactly = 1) { f.secondary.release() }
        f.controller.release()
    }

    @Test fun lateIncomingStreamNeverFadesThePlayingSong() = runTest {
        val f = Fixture(this)
        f.incomingState = Player.STATE_BUFFERING
        f.tick(); runCurrent(); advanceTimeBy(6_000); runCurrent()
        assertEquals(1f, f.volume, 0f)
        assertTrue(f.transitions.isEmpty())
        verify(exactly = 0) { f.secondary.play() }
        f.controller.release()
    }

    @Test fun incomingAudioStaysAudibleWhileSessionPlayerBuffersItsHandoff() = runTest {
        val f = Fixture(this)
        var handoffReady = false
        every { f.primary.playbackState } answers {
            if (f.index == 1 && !handoffReady) Player.STATE_BUFFERING else Player.STATE_READY
        }
        f.tick(); runCurrent(); advanceTimeBy(5_100); runCurrent()
        assertTrue(f.controller.isHandoff)
        assertEquals(0f, f.volume, .00001f)
        assertEquals(1f, f.secondaryVolume, .00001f)
        verify(exactly = 0) { f.secondary.release() }
        handoffReady = true
        advanceTimeBy(500); runCurrent()
        assertFalse(f.controller.isMixing)
        assertEquals(1f, f.volume, 0f)
        verify(exactly = 1) { f.secondary.release() }
        f.controller.release()
    }

    @Test fun seekingDuringTheFadeRestoresPrimaryAndDoesNotAdvance() = runTest {
        val f = Fixture(this)
        f.tick(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        f.primaryListener.captured.onPositionDiscontinuity(mockk(relaxed = true), mockk(relaxed = true), Player.DISCONTINUITY_REASON_SEEK)
        advanceTimeBy(8_000); runCurrent()
        assertEquals(1f, f.volume, 0f)
        assertTrue(f.transitions.isEmpty())
        assertFalse(f.controller.isMixing)
        f.controller.release()
    }

    @Test fun secondaryErrorRestoresPrimaryWithoutAdvancingQueue() = runTest {
        val f = Fixture(this)
        f.tick(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        f.secondaryListener.captured.onPlayerError(PlaybackException("failed", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        advanceTimeBy(8_000); runCurrent()
        assertEquals(1f, f.volume, 0f)
        assertTrue(f.transitions.isEmpty())
        verify(exactly = 1) { f.secondary.release() }
        f.controller.release()
    }

    @Test fun enteringSynchronizedPlaybackStopsAnInProgressMix() = runTest {
        val f = Fixture(this)
        f.tick(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        f.eligible = false
        advanceTimeBy(100); runCurrent()
        assertEquals(1f, f.volume, 0f)
        assertFalse(f.controller.isMixing)
        assertTrue(f.transitions.isEmpty())
        f.controller.release()
    }

    @Test fun disabledCrossfadeAndSameAlbumSequenceDoNotStartSecondDecoder() = runTest {
        val f = Fixture(this)
        f.tick(PlayerSettings())
        verify(exactly = 0) { f.secondary.prepare() }
        val albumTrack = f.outgoing.copy(albumId = "album", album = "An Album")
        f.controller.tick(albumTrack, f.next.copy(albumId = "album"), 0, PlayerSettings(crossfadeEnabled = true))
        verify(exactly = 0) { f.secondary.prepare() }
        f.controller.release()
    }

    @Test fun sameAlbumRequiresRealIdentityAndNeverTreatsTwoMissingAlbumsAsEqual() {
        val a = Track(id = "a", artist = "A")
        assertFalse(AudioTransitionPolicy.sameAlbum(a, a.copy(id = "b")))
        assertFalse(AudioTransitionPolicy.sameAlbum(a.copy(album = "Unknown album"), a.copy(album = "Unknown album")))
        assertTrue(AudioTransitionPolicy.sameAlbum(a.copy(albumId = "x"), Track(id = "b", artist = "B", albumId = "x")))
        assertFalse(AudioTransitionPolicy.sameAlbum(a.copy(albumId = "x"), a.copy(albumId = "y")))
        assertTrue(AudioTransitionPolicy.sameAlbum(a.copy(album = "Example"), a.copy(album = " example ")))
        assertFalse(AudioTransitionPolicy.sameAlbum(a.copy(album = "Example"), a.copy(artist = "B", album = "Example")))
    }

    @Test fun equalPowerMixPreservesEnergyAndDoesNotFadePastSongEnds() {
        for (i in 0..100) {
            val (outgoing, incoming) = AudioTransitionPolicy.gains(i / 100f)
            assertEquals(1f, outgoing * outgoing + incoming * incoming, .00001f)
            assertTrue(outgoing in 0f..1f && incoming in 0f..1f)
        }
        assertEquals(0L, AudioTransitionPolicy.fadeDuration(5_000, 100, 30_000))
        assertEquals(1_000L, AudioTransitionPolicy.fadeDuration(12_000, 10_000, 2_000))
    }

    @Test fun automixWithoutAnalysisFallsBackAndConsecutiveAlbumPlanStaysGapless() {
        val current = AutoMixTrackInfo("a", 180_000, "A", "Artist", "Album")
        val next = AutoMixTrackInfo("b", 190_000, "B", "Artist", "Album")
        val fallback = AutoMixPlanner.plan(null, null, current, next, 0, 5_000, 1_000, false)
        assertFalse(fallback.blocked)
        assertEquals(AutoMixStyle.EQUAL_POWER, fallback.style)
        assertTrue(fallback.transitionStartMs in 150_000..179_999)
        assertEquals(0L, fallback.incomingCueMs)
        val gapless = AutoMixPlanner.plan(null, null, current, next, 0, 5_000, 1_000, true)
        assertEquals(AutoMixStyle.GAPLESS, gapless.style)
        assertEquals(0L, gapless.fadeMs)
    }
}
