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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resolve/download the current and next artwork before the player needs it. Never run a decoder. */
@Composable
internal fun PrepareQueuedAnimatedMedia(
    playback: DesktopPlaybackController?, lyrics: DesktopLyricsController?, warmDynamic: Boolean
) {
    if (playback == null) return
    val queue by playback.queue.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    val prefs by CanvasSettings.prefs.collectAsState()
    val songVisualRevision by CanvasSettings.songVisualChanges.collectAsState()
    val current = remote?.track ?: queue.current
    val next = if (remote == null && queue.index >= 0) queue.tracks.getOrNull(queue.index + 1) else null
    val currentVisual = androidx.compose.runtime.remember(current, songVisualRevision) { current?.let(CanvasSettings::songVisual) }
    val nextVisual = androidx.compose.runtime.remember(next, songVisualRevision) { next?.let(CanvasSettings::songVisual) }
    LaunchedEffect(current?.thumbnail, warmDynamic) {
        if (warmDynamic) current?.thumbnail?.takeIf { it.isNotBlank() }?.let { loadAuraTexture(it) }
    }
    LaunchedEffect(next?.thumbnail, warmDynamic) {
        if (warmDynamic && next?.thumbnail?.isNotBlank() == true) {
            delay(2_000L)
            loadAuraTexture(next.thumbnail)
        }
    }
    LaunchedEffect(current, prefs, currentVisual) {
        prepare(current, prefs, currentVisual, lyrics?.state?.value?.takeIf { it.trackId == current?.id }?.lyrics?.spotifyTrackId)
    }
    LaunchedEffect(next, prefs, nextVisual) {
        if (next == null || (!prefs.enabled && !prefs.spotifyCanvas)) return@LaunchedEffect
        delay(2_000L)
        prepare(next, prefs, nextVisual, null)
    }
}

private suspend fun prepare(track: Track?, prefs: CanvasPrefs, songVisual: StageVisual?, hint: String?) {
    if (track == null || (!prefs.enabled && !prefs.spotifyCanvas)) return
    try {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000L) {
                launch { com.auralis.music.desktop.playback.CanvasVideoEngine.available }
                if (prefs.enabled) launch {
                    CanvasLookup.find(track, prefs.source)?.let { CanvasLookup.playable(it) }
                }
                if (prefs.spotifyCanvas && songVisual != StageVisual.ALBUM) launch {
                    TrackCanvasLookup.find(track, hint, matchRelease = (songVisual ?: prefs.stageVisual) != StageVisual.SPOTIFY)
                }
            }
        }
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { Log.w("AnimatedCovers", "Artwork preparation unavailable: ${e.javaClass.simpleName}") }
}
