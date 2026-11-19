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

// Only the video player waits this long after a song change, so skipping through songs never
// starts one; the cover lookup itself starts at once.
private const val COVER_SETTLE_MS = 350L

/** Animated cover videos kept on disk, so a replayed song's loop shows without downloading again. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object AnimatedCoverVideoCache {
    private const val MAX_BYTES = 200L * 1024 * 1024
    @Volatile private var cache: androidx.media3.datasource.cache.SimpleCache? = null

    fun get(context: android.content.Context): androidx.media3.datasource.cache.SimpleCache = cache ?: synchronized(this) {
        cache ?: androidx.media3.datasource.cache.SimpleCache(
            java.io.File(context.applicationContext.cacheDir, "animated-covers"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            androidx.media3.database.StandaloneDatabaseProvider(context.applicationContext)
        ).also { cache = it }
    }
}

/**
 * Silent, lifecycle-aware cover loop. The caller keeps the still artwork underneath.
 * With [visible] false the loop stays loaded but fades away, leaving that still artwork.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun AnimatedCover(track: Track?, isPlaying: Boolean, modifier: Modifier = Modifier, visible: Boolean = true,
    spotifyTrackId: String? = null) {
    val settings = LocalAppearanceSettings.current
    if (!settings.animatedCovers || track == null) return
    val context = LocalContext.current.applicationContext
    val wifi = rememberCoverWifiAvailable(settings.animatedCoversWifiOnly)
    if (!wifi) return
    // The lookup starts as soon as the song does.
    val source = CanvasSource.from(settings.animatedCoverSource)
    var video by remember(track.id, track.title, track.artist, track.album, source) { mutableStateOf<CoverVideo?>(null) }
    LaunchedEffect(track.id, track.title, track.artist, track.album, source) {
        // Spotify Canvas belongs to the player background (CanvasBackdrop); the cover shows motion
        // artwork only, so it never waits for a Canvas search that may take seconds to give up.
        video = try { CanvasLookup.find(context, track, source)?.let { CoverVideo(it) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }
    // A video player is heavy to start and stop, so skipping through songs never starts one.
    var settled by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id) {
        kotlinx.coroutines.delay(COVER_SETTLE_MS)
        settled = true
    }
    if (!settled) return
    val selectedVideo = video ?: return
    val videoUrl = selectedVideo.url
    key(selectedVideo) {
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
            val network = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(NetworkClientProvider.okHttpClient))
            // Through the disk cache: a loop already watched starts from local storage.
            val data = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(AnimatedCoverVideoCache.get(context))
                .setUpstreamDataSourceFactory(network)
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
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
        // A Canvas that cannot be decoded tries the manifest copy, then motion artwork. If that
        // also fails, the still artwork underneath remains visible; no failed video hides it.
        LaunchedEffect(failed) {
            if (failed && selectedVideo.spotifyId != null) {
                video = resolveCoverVideo(
                    canvas = {
                        if (selectedVideo.attempt < 2)
                            PhoneCanvasLookup.fallback(context, ResolvedCanvas(selectedVideo.spotifyId, videoUrl))
                        else null
                    },
                    animatedCover = { CanvasLookup.find(context, track, source) }
                )?.copy(attempt = selectedVideo.attempt + 1)
            }
        }
        // While the Canvas plays behind the player, this loop pauses under the faded cover.
        val backdrop = CanvasBackdrop.isShowing(track.id)
        LaunchedEffect(isPlaying, foreground, failed, backdrop) { player.playWhenReady = isPlaying && foreground && !failed && !backdrop }
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
internal fun rememberCoverWifiAvailable(onlyWifi: Boolean): Boolean {
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
