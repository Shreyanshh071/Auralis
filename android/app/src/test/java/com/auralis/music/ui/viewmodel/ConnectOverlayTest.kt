package com.auralis.music.ui.viewmodel

import com.auralis.music.data.connect.ConnectDeviceType
import com.auralis.music.data.connect.ConnectOutput
import com.auralis.music.data.connect.ConnectRemoteView
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** While another device of the account plays, the player screens mirror it and this phone stays silent. */
class ConnectOverlayTest {
    @Test fun remoteLyricsLoadWithoutReplacingLocalPlayback() {
        val localState = MutableStateFlow(PlayerUiState(currentTrack = local))
        val remoteFlow = MutableStateFlow<ConnectRemoteView?>(remote())
        val lyricsState = MutableStateFlow(PlayerUiState(currentTrack = remoteTrack, isLoadingLyrics = true))
        val shown = ConnectOverlayState(localState, remoteFlow, lyricsState)
        assertTrue(shown.value.isLoadingLyrics)
        val lyrics = com.auralis.music.domain.model.LyricsData(
            syncType = com.auralis.music.domain.model.SyncType.LINE_SYNC,
            lines = emptyList(), provider = com.auralis.music.domain.model.LyricsProvider.LRCLIB)
        lyricsState.value = PlayerUiState(currentTrack = remoteTrack, lyrics = lyrics)
        assertEquals(lyrics, shown.value.lyrics)
        assertFalse(shown.value.isLoadingLyrics)
        lyricsState.value = PlayerUiState(currentTrack = local, lyrics = lyrics)
        assertNull(shown.value.lyrics)
        assertEquals(local, localState.value.currentTrack)
    }

    private val local = Track(id = "local", title = "Local song", artist = "Here", duration = 200)
    private val remoteTrack = Track(id = "remote", title = "Remote song", artist = "There", duration = 180)

    private fun remote(playing: Boolean = true, at: Long = 1_000L) = ConnectRemoteView(
        track = remoteTrack, queue = listOf(remoteTrack, local), index = 0, playing = playing, buffering = false,
        positionMs = 61_000L, anchorLocalMs = at, durationMs = 180_000L, shuffle = true, repeat = RepeatMode.ALL, context = null,
        output = ConnectOutput("laptop", "Laptop", ConnectDeviceType.COMPUTER, isThis = false, active = true, playing = playing)
    )

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun coldSilentPhoneReceivesRemoteTrackWithoutAnyLocalPlayback() = runTest {
        val localState = MutableStateFlow(PlayerUiState())
        val remoteFlow = MutableStateFlow<ConnectRemoteView?>(null)
        val shown = ConnectOverlayState(localState, remoteFlow) { 1_000L }
        val observed = mutableListOf<PlayerUiState>()
        backgroundScope.launch { shown.collect { observed += it } }
        runCurrent()
        assertNull(observed.last().currentTrack)
        remoteFlow.value = remote()
        runCurrent()
        assertEquals(remoteTrack, observed.last().currentTrack)
        assertTrue(observed.last().isPlaying)
        assertNull(localState.value.currentTrack)
        assertFalse(localState.value.isPlaying)
    }

    @Test
    fun remoteSessionReplacesWhatTheScreensShow() {
        var now = 1_000L
        val localState = MutableStateFlow(PlayerUiState(currentTrack = local, isPlaying = false, playbackPositionMs = 5_000L, isFavorite = true))
        val remoteFlow = MutableStateFlow<ConnectRemoteView?>(null)
        val shown = ConnectOverlayState(localState, remoteFlow) { now }
        assertEquals("local", shown.value.currentTrack?.id)

        remoteFlow.value = remote()
        assertEquals("remote", shown.value.currentTrack?.id)
        assertTrue(shown.value.isPlaying)
        assertEquals(61_000L, shown.value.playbackPositionMs)
        assertEquals(listOf("remote", "local"), shown.value.queue.map { it.id })
        assertTrue(shown.value.isShuffled)
        assertEquals(RepeatMode.ALL, shown.value.repeatMode)
        assertFalse("the local song's like doesn't carry over", shown.value.isFavorite)
        assertNull(shown.value.lyrics)

        now = 3_500L
        assertEquals("position keeps moving between updates", 63_500L, shown.value.playbackPositionMs)
        assertEquals("local", localState.value.currentTrack?.id)

        remoteFlow.value = null
        assertEquals("local", shown.value.currentTrack?.id)
    }

    @Test
    fun positionAndLyricsClockFollowTheRemoteOutput() {
        var now = 1_000L
        val remoteFlow = MutableStateFlow<ConnectRemoteView?>(remote())
        val position = ConnectOverlayPosition(MutableStateFlow(0L), remoteFlow) { now }
        val localClock = object : PlaybackClockSource {
            override fun rawPositionMs() = 0L
            override fun isPlaying() = false
            override fun speed() = 1f
        }
        val clock = ConnectOverlayClock(localClock, remoteFlow) { now }
        now = 2_000L
        assertEquals(62_000L, position.value)
        assertEquals(62_000L, clock.rawPositionMs())
        assertTrue(clock.isPlaying())

        remoteFlow.value = remote(playing = false)
        now = 9_000L
        assertEquals("paused: it stays put", 61_000L, position.value)
        assertFalse(clock.isPlaying())
    }
}
