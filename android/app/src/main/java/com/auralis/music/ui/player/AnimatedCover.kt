package com.auralis.music.ui.player

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.theme.LocalAppearanceSettings

private const val COVER_SETTLE_MS = 2_000L

/**
 * Silent, lifecycle-aware cover loop. The caller keeps the still artwork underneath.
 * With [visible] false the loop stays loaded but fades away, leaving that still artwork.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun AnimatedCover(track: Track?, isPlaying: Boolean, modifier: Modifier = Modifier, visible: Boolean = true) {
    val settings = LocalAppearanceSettings.current
    if (!settings.animatedCovers || track == null) return
    val context = LocalContext.current.applicationContext
    val wifi = rememberCoverWifiAvailable(settings.animatedCoversWifiOnly)
    if (!wifi) return
    // A video player is heavy to start and stop, so skipping through songs never starts one.
    var settled by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id) {
        kotlinx.coroutines.delay(COVER_SETTLE_MS)
        settled = true
    }
    if (!settled) return
    val source = CanvasSource.from(settings.animatedCoverSource)
    var url by remember(track.id, track.title, track.artist, track.album, source) { mutableStateOf<String?>(null) }
    LaunchedEffect(track.id, track.title, track.artist, track.album, source) {
        url = CanvasLookup.find(context, track, source)
    }
    val videoUrl = url ?: return
    key(videoUrl) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, _ ->
                foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        var ready by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var aspect by remember { mutableFloatStateOf(1f) }
        val player = remember {
            val data = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(NetworkClientProvider.okHttpClient))
            ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(data)).build().apply {
                volume = 0f
                // Artwork must never compete with the music service for audio focus or output.
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    // Adaptive HLS opens on a low variant and a short loop never climbs out of it.
                    .setMaxVideoSize(1280, 1280).setForceHighestSupportedBitrate(true).build()
                repeatMode = Player.REPEAT_MODE_ONE
            }
        }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() { ready = true }
                override fun onPlayerError(error: PlaybackException) { failed = true; ready = false }
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(videoUrl))
            player.prepare()
            onDispose { player.removeListener(listener); player.release() }
        }
        LaunchedEffect(isPlaying, foreground, failed) { player.playWhenReady = isPlaying && foreground && !failed }
        val opacity by animateFloatAsState(if (ready && !failed && visible) 1f else 0f, tween(if (visible) 350 else 180), label = "motionCover")
        AndroidView(
            modifier = modifier.alpha(opacity),
            factory = { viewContext ->
                AspectRatioFrameLayout(viewContext).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    addView(TextureView(viewContext).also { player.setVideoTextureView(it) },
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
            update = { it.setAspectRatio(aspect) }
        )
    }
}

@Composable
private fun rememberCoverWifiAvailable(onlyWifi: Boolean): Boolean {
    if (!onlyWifi) return true
    val context = LocalContext.current
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    fun isWifi() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    var allowed by remember(connectivity) { mutableStateOf(isWifi()) }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { allowed = isWifi() }
            override fun onLost(network: Network) { allowed = isWifi() }
            override fun onAvailable(network: Network) { allowed = isWifi() }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    return allowed
}
