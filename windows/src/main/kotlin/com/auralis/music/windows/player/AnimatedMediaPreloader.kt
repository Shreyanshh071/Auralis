package com.auralis.music.windows.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resolve/download the current and next artwork before the player needs it. Never run a decoder. */
@Composable
internal fun PrepareQueuedAnimatedMedia(playback: DesktopPlaybackController?, lyrics: DesktopLyricsController?) {
    if (playback == null) return
    val queue by playback.queue.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    val prefs by CanvasSettings.prefs.collectAsState()
    val current = remote?.track ?: queue.current
    val next = if (remote == null && queue.index >= 0) queue.tracks.getOrNull(queue.index + 1) else null
    LaunchedEffect(current, prefs) {
        prepare(current, prefs, lyrics?.state?.value?.takeIf { it.trackId == current?.id }?.lyrics?.spotifyTrackId)
    }
    LaunchedEffect(next, prefs) {
        if (next == null || (!prefs.enabled && !prefs.spotifyCanvas)) return@LaunchedEffect
        delay(2_000L)
        prepare(next, prefs, null)
    }
}

private suspend fun prepare(track: Track?, prefs: CanvasPrefs, hint: String?) {
    if (track == null || (!prefs.enabled && !prefs.spotifyCanvas)) return
    try {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000L) {
                val canvas = if (prefs.spotifyCanvas) TrackCanvasLookup.find(track, hint) else null
                if (prefs.enabled) {
                    CanvasLookup.find(track, prefs.source, preferTall = false)
                    if (canvas == null) CanvasLookup.find(track, prefs.source, preferTall = true)
                }
                // Load native video support off the UI thread. No player/hidden video is started.
                if (canvas != null) com.auralis.music.desktop.playback.CanvasVideoEngine.available
            }
        }
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { Log.w("AnimatedCovers", "Artwork preparation unavailable: ${e.javaClass.simpleName}") }
}
