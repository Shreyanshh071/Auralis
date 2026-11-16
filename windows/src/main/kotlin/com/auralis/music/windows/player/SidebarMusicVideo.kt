package com.auralis.music.windows.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.desktop.playback.*
import com.auralis.music.domain.model.Track

/**
 * Optional silent video follows the audio clock; unavailable streams retain the animated cover.
 * Its lookup runs only when videos are on, this cover is on screen (it isn't composed otherwise),
 * and this PC is actually sounding [track] ([established]): never for a restored idle song, a song
 * still resolving, or a song another device plays. Usually the song's audio lookup already brought
 * the video link, so no extra YouTube request is made.
 */
@Composable internal fun SidebarMusicVideo(track: Track, clock: PlaybackClockSource?, modifier: Modifier, aura: MotionAura?,
    established: Boolean = false, fallback: @Composable () -> Unit) {
    val prefs = com.auralis.music.windows.settings.DesktopPreferences.store
    val revision by prefs.changes.collectAsState()
    var url by remember(track.id) { mutableStateOf<String?>(null) }
    val enabled = clock != null && prefs.bool("musicVideos")
    LaunchedEffect(track.id, enabled, established) {
        if (!enabled || !established) { if (!enabled) url = null; return@LaunchedEffect }
        if (url != null) return@LaunchedEffect
        val id = com.auralis.music.data.network.RecordingMatches.getMatchedVideoId(track.id) ?: track.id
        if (!DesktopPlaybackMatch.isVideoId(id)) return@LaunchedEffect
        // Leaving composition (or the song changing) cancels the lookup; shared work is canceled
        // only when nothing else still wants it.
        url = runCatching { DesktopStreamResolver.resolveVideo(id) }.getOrNull()
    }
    Box(modifier) {
        fallback()
        url?.let { video ->
            var frame by remember(video) { mutableStateOf<ImageBitmap?>(null) }
            val ambient = rememberUpdatedState(prefs.bool("ambientVideo",true))
            DisposableEffect(video,clock) {
                val active=java.util.concurrent.atomic.AtomicBoolean(true)
                val stop=CanvasVideoEngine.play(video,clock) { image -> if(active.get()) {frame=image;if(ambient.value) aura?.offer(image)} }
                onDispose { active.set(false);stop?.invoke();aura?.offer(null) }
            }
            // Over a dark backing and faded in, so the cover never shows around a letterboxed video.
            val shown by androidx.compose.animation.core.animateFloatAsState(if (frame != null) 1f else 0f,
                androidx.compose.animation.core.tween(450), label = "musicVideoFadeIn")
            frame?.let {
                Box(Modifier.matchParentSize().alpha(shown).background(androidx.compose.ui.graphics.Color(0xFF0B0A0D))) {
                    Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
                }
            }
        }
    }
}
