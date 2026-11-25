package com.auralis.music.ui.player

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
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

    /**
     * How far the cover pager has moved off the Canvas song's page: 0 on it, 1 a full page away.
     * Read while drawing, so a skip fades the Canvas out under the next cover right away instead
     * of leaving it behind that cover until the next song has loaded.
     */
    var pagerAway by mutableStateOf<(() -> Float)?>(null)
        internal set

    /** [pagerAway] now, or 0 when no pager is following the Canvas. */
    fun away(): Float = pagerAway?.invoke() ?: 0f

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

/** Lets the Canvas follow the cover pager (see [CanvasBackdrop.pagerAway]); one call per player. */
@Composable
internal fun CanvasFollowsPager(pagerState: androidx.compose.foundation.pager.PagerState, queue: List<Track>) {
    val tracks by rememberUpdatedState(queue)
    DisposableEffect(pagerState) {
        val away: () -> Float = away@{
            val id = CanvasBackdrop.showing ?: return@away 0f
            val list = tracks
            val current = pagerState.currentPage
            // The Canvas song's page nearest the cover on screen (a song can be queued twice).
            val page = (current - 2..current + 2).firstOrNull { list.getOrNull(it)?.id == id }
                ?: list.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                ?: return@away 0f
            kotlin.math.abs(current + pagerState.currentPageOffsetFraction - page).coerceIn(0f, 1f)
        }
        CanvasBackdrop.pagerAway = away
        onDispose { if (CanvasBackdrop.pagerAway === away) CanvasBackdrop.pagerAway = null }
    }
}

/** Length of the cover-to-Canvas move; the cover and the Canvas share it so they read as one. */
private const val CANVAS_ENTER_MS = 900
private val CanvasEnterEasing = androidx.compose.animation.core.CubicBezierEasing(.22f, 1f, .36f, 1f)

/**
 * The cover dissolves into the Canvas: it grows a little, softens and fades while the video comes
 * into focus behind it, both on one eased curve. Coming back (Canvas gone) is a quick plain fade.
 *
 * [reveal] (0..1, read while drawing) brings the cover back regardless of the Canvas: the Classic
 * player raises it once the cover has settled in the Lyrics / Queue header (never while it is
 * travelling) and drops it the moment the cover heads back to the player.
 */
@Composable
internal fun Modifier.hiddenWhileCanvasBackdrop(trackId: String?, reveal: (() -> Float)? = null): Modifier {
    val hidden = CanvasBackdrop.isShowing(trackId)
    val progress by animateFloatAsState(if (hidden) 1f else 0f,
        if (hidden) tween(CANVAS_ENTER_MS, easing = CanvasEnterEasing) else tween(320),
        label = "cover under canvas")
    return this.graphicsLayer {
        // Swiped or skipped off its page, the cover comes back as the Canvas fades out.
        val shown = 1f - (reveal?.invoke() ?: 0f).coerceIn(0f, 1f)
        val progress = (if (hidden) progress * (1f - CanvasBackdrop.away()) else progress) * shown
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
    // Fades out as soon as the covers move to another song, not once that song has loaded.
    Box(modifier.fillMaxSize().graphicsLayer { alpha = 1f - CanvasBackdrop.away() }) {
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
    // Canvas loops are 9:16 until the video says otherwise.
    var videoRatio by remember { mutableFloatStateOf(9f / 16f) }
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
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) videoRatio = size.width * size.pixelWidthHeightRatio / size.height
            }
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
    // The loop keeps its own shape: full width, from the top. Whatever the screen has left below it
    // is filled by the same video, zoomed to cover and blurred, which the sharp loop fades into.
    val mirror = rememberGraphicsLayer()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val videoHeight = maxWidth / videoRatio
        val coversScreen = videoHeight >= maxHeight
        val enterAlpha = { if (failed) 0f else ((entrance ?: swapOpacity) / .55f).coerceIn(0f, 1f) }
        if (!coversScreen) {
            Spacer(Modifier.fillMaxSize().graphicsLayer {
                alpha = enterAlpha()
                renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31)
                    androidx.compose.ui.graphics.BlurEffect(48.dp.toPx(), 48.dp.toPx(), androidx.compose.ui.graphics.TileMode.Clamp) else null
                clip = true
            }.drawBehind {
                val src = mirror.size
                if (src.width <= 0 || src.height <= 0) return@drawBehind
                // Cover the whole screen from the middle of the loop.
                val cover = maxOf(size.width / src.width, size.height / src.height) * 1.1f
                translate((size.width - src.width * cover) / 2f, (size.height - src.height * cover) / 2f) {
                    scale(cover, cover, pivot = Offset.Zero) { drawLayer(mirror) }
                }
                drawRect(Color.Black.copy(alpha = .28f))
            })
        }
        AndroidView(
            modifier = Modifier.align(if (coversScreen) Alignment.Center else Alignment.TopCenter)
                .fillMaxWidth().requiredHeight(videoHeight)
                .graphicsLayer {
                    val enter = if (failed) 0f else entrance ?: swapOpacity
                    // Fades in fast, while settling from a slight zoom and coming into focus.
                    alpha = (enter / .55f).coerceIn(0f, 1f)
                    val settle = if (entrance != null) .08f * (1f - enter) else 0f
                    // Behind Lyrics and Queue the loop pauses, softened so the text reads.
                    val radius = blur * 28.dp.toPx() + (if (entrance != null) (1f - enter) * 22.dp.toPx() else 0f)
                    renderEffect = if (radius > 0.5f && android.os.Build.VERSION.SDK_INT >= 31)
                        androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Clamp) else null
                    val scale = 1f + blur * .06f + settle
                    scaleX = scale; scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, if (coversScreen) .5f else 0f)
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // The bottom edge melts into the blurred fill instead of ending on a hard line.
                    if (!coversScreen) drawRect(Brush.verticalGradient(
                        .72f to Color.Black, 1f to Color.Transparent, startY = 0f, endY = size.height),
                        blendMode = BlendMode.DstIn)
                }
                .drawWithContent {
                    // Recorded once, drawn here and again (zoomed, blurred) as the fill behind.
                    mirror.record { this@drawWithContent.drawContent() }
                    drawLayer(mirror)
                },
            factory = { viewContext -> TextureView(viewContext).also { player.setVideoTextureView(it) } }
        )
    }
}

/** The Appearance switch for Canvas lookups with the signed-in Spotify account; read by the lookup. */
internal object CanvasAccount {
    @Volatile var enabled = false
    val available: Boolean get() = enabled && com.auralis.music.data.network.SpotifySession.signedIn.value
}
