package com.auralis.music.ui.player

import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.rememberUpdatedState
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.auralis.music.data.network.NetworkClientProvider

/**
 * Spotify Canvas for the full player. The player background finds and plays it on every tab
 * (blurred behind Lyrics and Queue); the cover only fades away while it shows. Motion artwork from
 * other sources stays in the cover and never takes over the background.
 */
internal object CanvasBackdrop {
    private val found = mutableStateMapOf<String, String>()
    private val failed = mutableSetOf<String>()

    /** The song whose Canvas has drawn its first frame behind the player. */
    var showing by mutableStateOf<String?>(null)
        internal set

    /** Lyrics or Queue is open, so the Canvas sits blurred behind them. */
    var panelOpen by mutableStateOf(false)
        internal set

    fun urlFor(trackId: String?): String? = trackId?.let { found[it] }

    fun isShowing(trackId: String?): Boolean = trackId != null && showing == trackId

    internal fun store(trackId: String, url: String) { if (trackId !in failed) found[trackId] = url }

    internal fun fail(trackId: String) { failed += trackId; found.remove(trackId); if (showing == trackId) showing = null }
}

/**
 * Looks up [track]'s Canvas as soon as the song starts. The search does not wait for lyrics:
 * their Spotify id is only used for a second try when the first finds nothing.
 */
@Composable
internal fun rememberCanvasBackdrop(track: Track?, spotifyTrackId: String?): String? {
    val settings = LocalAppearanceSettings.current
    // Set during composition so the lookup launched below already sees it.
    CanvasAccount.enabled = settings.spotifyAccountCanvas
    val accountOn = settings.spotifyAccountCanvas &&
        com.auralis.music.data.network.SpotifySession.signedIn.collectAsState().value
    if (!settings.spotifyCanvas || track == null) return null
    if (!rememberCoverWifiAvailable(settings.animatedCoversWifiOnly)) return null
    val context = LocalContext.current.applicationContext
    val hint by rememberUpdatedState(spotifyTrackId)
    // Turning the account lookup on (or signing in) looks again for the playing song.
    LaunchedEffect(track.id, accountOn) {
        if (CanvasBackdrop.urlFor(track.id) != null) return@LaunchedEffect
        val first = hint
        var canvas = try { PhoneCanvasLookup.find(context, track, first) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        if (canvas == null) {
            val later = snapshotFlow { hint }.filterNotNull().filter { it != first }.first()
            canvas = try { PhoneCanvasLookup.find(context, track, later) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        }
        val found = canvas ?: return@LaunchedEffect
        // A Canvas that is only the album cover on a blur is no Canvas: the animated cover shows instead.
        val verdict = CoverOnlyCanvas.check(context, found.spotifyId, found.path, track.thumbnail)
        // Only a Canvas verified as a real moving video is shown; cover-only and unchecked ones never are.
        if (verdict != false) return@LaunchedEffect
        // Stream the default loop at once; the sharp copy downloads meanwhile and fades in over it.
        CanvasBackdrop.store(track.id, found.path)
        PhoneCanvasLookup.awaitSharp(context, found.spotifyId)?.takeIf { it != found.path }?.let { sharp ->
            // The stream could not be read for the check: judge the downloaded copy instead.
            // A sharp copy only replaces the stream once it is verified too.
            if (CoverOnlyCanvas.check(context, found.spotifyId, sharp, track.thumbnail) == false) {
                CanvasBackdrop.store(track.id, sharp)
            }
        }
    }
    return CanvasBackdrop.urlFor(track.id)
}

/** Length of the cover-to-Canvas move; the cover and the Canvas share it so they read as one. */
private const val CANVAS_ENTER_MS = 900
private val CanvasEnterEasing = androidx.compose.animation.core.CubicBezierEasing(.22f, 1f, .36f, 1f)

/**
 * The cover dissolves into the Canvas: it grows a little, softens and fades while the video comes
 * into focus behind it, both on one eased curve. Coming back (Canvas gone) is a quick plain fade.
 */
@Composable
internal fun Modifier.hiddenWhileCanvasBackdrop(trackId: String?): Modifier {
    val hidden = CanvasBackdrop.isShowing(trackId)
    val progress by animateFloatAsState(if (hidden) 1f else 0f,
        if (hidden) tween(CANVAS_ENTER_MS, easing = CanvasEnterEasing) else tween(320),
        label = "cover under canvas")
    return this.graphicsLayer {
        // Gone a little before the move ends, so no ghost of the cover lingers over the loop.
        alpha = (1f - progress / .8f).coerceIn(0f, 1f)
        val scale = 1f + .1f * progress
        scaleX = scale; scaleY = scale
        val radius = progress * 18.dp.toPx()
        renderEffect = if (radius > .5f && android.os.Build.VERSION.SDK_INT >= 31)
            androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal) else null
    }
}

/**
 * The Canvas full-bleed, with a dark fade top and bottom so the header and controls stay readable.
 * A new [url] for the same song (the sharp copy replacing the streamed one) fades in over the loop
 * on screen, which stays until the new one has drawn, so the swap never blinks.
 */
@Composable
internal fun CanvasBackdropVideo(trackId: String, url: String, isPlaying: Boolean, blurred: Boolean, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var layers by remember(trackId) { mutableStateOf(listOf(url)) }
    LaunchedEffect(url) { if (layers.last() != url) layers = layers + url }
    val blur by animateFloatAsState(if (blurred) 1f else 0f, tween(450), label = "canvas blur")
    DisposableEffect(trackId) { onDispose { if (CanvasBackdrop.showing == trackId) CanvasBackdrop.showing = null } }
    // The entrance: 0 until the first frame has drawn, then one eased move shared with the cover.
    var entered by remember(trackId) { mutableStateOf(CanvasBackdrop.isShowing(trackId)) }
    val entrance by animateFloatAsState(if (entered) 1f else 0f, tween(CANVAS_ENTER_MS, easing = CanvasEnterEasing),
        label = "canvas entrance")
    val firstLayer = remember(trackId) { url }
    Box(modifier.fillMaxSize()) {
        layers.forEach { layerUrl ->
            key(layerUrl) {
                CanvasLoop(layerUrl, isPlaying && foreground, blur,
                    entrance = if (layerUrl == firstLayer) entrance else null,
                    onFirstFrame = {
                        // The cover starts dissolving on the same frame the video starts showing.
                        entered = true
                        CanvasBackdrop.showing = trackId
                    },
                    onShown = {
                        if (layers.last() == layerUrl) layers = listOf(layerUrl)
                    },
                    onFailed = {
                        if (layers.size > 1) layers = layers - layerUrl else CanvasBackdrop.fail(trackId)
                    })
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blur * .32f * entrance)))
        // The shading fades in with the video instead of darkening the player before it shows.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = entrance }.background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .45f),
            .18f to Color.Transparent,
            .52f to Color.Transparent,
            .78f to Color.Black.copy(alpha = .55f),
            1f to Color.Black.copy(alpha = .8f)
        )))
    }
}

/** One looping, silent video layer; remote URLs stream (through the disk cache), files play locally. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun CanvasLoop(url: String, playing: Boolean, blur: Float, entrance: Float?, onFirstFrame: () -> Unit,
    onShown: () -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val player = remember {
        val network = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(NetworkClientProvider.okHttpClient))
        val data = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(AnimatedCoverVideoCache.get(context))
            .setUpstreamDataSourceFactory(network)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(data)).build().apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(1280, 1920).setForceHighestSupportedBitrate(true).build()
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    val shown by rememberUpdatedState(onShown)
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val failedCallback by rememberUpdatedState(onFailed)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { ready = true }
            override fun onPlayerError(error: PlaybackException) { failed = true; ready = false; failedCallback() }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(playing, failed) { player.playWhenReady = playing && !failed }
    // A later layer (the sharp copy) only cross-fades over the loop already on screen.
    val swapOpacity by animateFloatAsState(if (ready && !failed) 1f else 0f, tween(400), label = "canvas layer")
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        firstFrame()
        // Reported once fully faded in, so a layer underneath is only removed when nothing shows through.
        kotlinx.coroutines.delay(if (entrance != null) CANVAS_ENTER_MS + 20L else 420L)
        shown()
    }
    AndroidView(
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val enter = if (failed) 0f else entrance ?: swapOpacity
            // Fades in fast, while settling from a slight zoom and coming into focus.
            alpha = (enter / .55f).coerceIn(0f, 1f)
            val settle = if (entrance != null) .08f * (1f - enter) else 0f
            // Behind Lyrics and Queue the loop keeps playing, softened so the text reads.
            val radius = blur * 28.dp.toPx() + (if (entrance != null) (1f - enter) * 22.dp.toPx() else 0f)
            renderEffect = if (radius > 0.5f && android.os.Build.VERSION.SDK_INT >= 31)
                androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Clamp) else null
            val scale = 1f + blur * .06f + settle
            scaleX = scale; scaleY = scale
        },
        factory = { viewContext ->
            AspectRatioFrameLayout(viewContext).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                addView(TextureView(viewContext).also { player.setVideoTextureView(it) },
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        update = { frame ->
            // Fill the screen at the window's own ratio; the zoom mode crops the loop to it.
            if (frame.width > 0 && frame.height > 0) frame.setAspectRatio(frame.width.toFloat() / frame.height)
        }
    )
}

/** The Appearance switch for Canvas lookups with the signed-in Spotify account; read by the lookup. */
internal object CanvasAccount {
    @Volatile var enabled = false
    val available: Boolean get() = enabled && com.auralis.music.data.network.SpotifySession.signedIn.value
}
