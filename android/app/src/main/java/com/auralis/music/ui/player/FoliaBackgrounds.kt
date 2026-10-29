package com.auralis.music.ui.player

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.imageLoader
import coil.request.ImageRequest
import com.auralis.music.ui.theme.ArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.*

/** Independent native background interpretations. No lyric data or audio capture is used.
 * See docs/folia-backgrounds.md for the upstream inventory and implementation boundaries. */
internal fun foliaMotionAllowed(playing: Boolean, visible: Boolean, started: Boolean,
    reducedMotion: Boolean, powerSave: Boolean) = playing && visible && started && !reducedMotion && !powerSave

@Composable
internal fun FoliaBackground(style: PlayerBackgroundStyle, artworkUrl: String?, palette: ArtworkPalette,
    isPlaying: Boolean, isVisible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var powerSave by remember { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { powerSave = power.isPowerSaveMode }
        }
        lifecycle.addObserver(observer)
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(receiver) }
    }
    val moving = foliaMotionAllowed(isPlaying, isVisible, started,
        !ValueAnimator.areAnimatorsEnabled(), powerSave) && style.foliaAnimated
    val seconds = remember(style) { mutableFloatStateOf(0f) }
    LaunchedEffect(moving, style) {
        if (!moving) return@LaunchedEffect
        var previous = 0L
        while (isActive) withFrameNanos { now ->
            if (previous == 0L) previous = now
            if (now - previous >= 33_333_333L) {
                seconds.floatValue += ((now - previous) / 1_000_000_000f).coerceAtMost(.1f)
                previous = now
            }
        }
    }
    val artwork by produceState<ImageBitmap?>(null, artworkUrl, style, palette.primary, palette.secondary) {
        value = null
        if (!style.foliaUsesArtwork || artworkUrl.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.imageLoader.execute(ImageRequest.Builder(context).data(artworkUrl)
                    .size(256).allowHardware(false).build()).drawable ?: return@runCatching null
                val bitmap = drawable.toBitmap(256, 256, Bitmap.Config.ARGB_8888)
                foliaProcessArtwork(bitmap, style, palette.primary.toArgb(), palette.secondary.toArgb()).asImageBitmap()
            }.getOrNull()
        }
    }
    Canvas(modifier) { drawFoliaBackground(style, palette, seconds.floatValue, artwork) }
}

internal val PlayerBackgroundStyle.foliaUsesArtwork: Boolean get() = isFolia &&
    this != PlayerBackgroundStyle.FOLIA_LATENT && this != PlayerBackgroundStyle.FOLIA_SORA
internal val PlayerBackgroundStyle.foliaAnimated: Boolean get() = this in listOf(
    PlayerBackgroundStyle.FOLIA_LATENT, PlayerBackgroundStyle.FOLIA_MONET, PlayerBackgroundStyle.FOLIA_SORA)

/** Small stable seed; geometry never reallocates an RNG or changes between paused frames. */
private fun unit(seed: Int): Float {
    var n = seed * 747796405 + 289133645
    n = (n xor (n ushr 16)) * -2048144789
    return ((n xor (n ushr 13)) ushr 8) / 16777216f
}

internal fun DrawScope.drawFoliaBackground(style: PlayerBackgroundStyle, palette: ArtworkPalette,
    seconds: Float, artwork: ImageBitmap? = null) {
    val a = palette.primary
    val b = palette.secondary
    val c = palette.tertiary
    drawRect(Color(0xFF090B12))
    when (style) {
        PlayerBackgroundStyle.FOLIA_LATENT -> {
            drawRect(a.copy(alpha = .18f))
            listOf(a,b,c,a).forEachIndexed { i, color ->
                val center = Offset(size.width * (.5f + .48f * sin(seconds * .09f + i * 1.8f)),
                    size.height * (.5f + .45f * cos(seconds * .07f + i * 2.3f)))
                drawRect(Brush.radialGradient(listOf(color.copy(alpha = .78f), Color.Transparent), center,
                    size.maxDimension * .72f))
            }
            // Stable ordered grain, deliberately sparse instead of a full-screen CPU pixel loop.
            repeat(420) { i -> drawCircle(Color.Black.copy(alpha = .12f), 1f,
                Offset(unit(i+600)*size.width,unit(i+1200)*size.height)) }
        }
        PlayerBackgroundStyle.FOLIA_SORA -> {
            repeat(150) { i ->
                val speed = .008f + unit(i+40)*.024f
                val center = Offset(((unit(i+400)+seconds*speed)%1f)*size.width,
                    (.05f+unit(i+800)*.9f+sin(seconds*.3f+i)*.005f)*size.height)
                val radius = size.minDimension * (.0008f + unit(i+23).pow(3.8f)*.004f)
                val alpha = .28f+.6f*((sin(seconds*(1f+unit(i+55)*2.5f)+i)+1f)*.5f)
                val color = if (i%10==0) c else lerp(a,Color.White,.8f)
                drawCircle(color.copy(alpha = alpha*.13f),radius*3f,center)
                drawCircle(color.copy(alpha = alpha),maxOf(1f,radius),center)
            }
        }
        else -> {
            drawRect(Brush.linearGradient(listOf(a.copy(alpha=.5f),b.copy(alpha=.3f),Color.Black)))
            artwork?.let { image ->
                val scale = max(size.width/image.width,size.height/image.height)*1.12f
                val w = image.width*scale
                val h = image.height*scale
                val drift = if(style == PlayerBackgroundStyle.FOLIA_MONET) sin(seconds*.08f)*size.width*.025f else 0f
                drawImage(image,dstOffset=IntOffset(((size.width-w)/2+drift).roundToInt(),((size.height-h)/2).roundToInt()),
                    dstSize=IntSize(w.roundToInt(),h.roundToInt()),filterQuality=FilterQuality.Low)
            }
            if (style == PlayerBackgroundStyle.FOLIA_MONET) {
                drawRect(a.copy(alpha = .18f))
                repeat(7) { i -> drawLine(b.copy(alpha=.045f),Offset(size.width*i/7,0f),
                    Offset(size.width*(i+.15f)/7,size.height),size.width*.012f) }
            }
        }
    }
    // Keep foreground controls legible; the background never installs pointer or gesture handlers.
    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha=.15f), .55f to Color.Black.copy(alpha=.25f), 1f to Color.Black.copy(alpha=.65f)))
    drawRect(Brush.radialGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.38f)),center,size.maxDimension*.7f))
}

/** One bounded 256x256 treatment per artwork/effect; no bitmap work occurs on animation frames. */
internal fun foliaProcessArtwork(source: Bitmap, style: PlayerBackgroundStyle, primary: Int, secondary: Int): Bitmap {
    val w=source.width; val h=source.height
    val pixels=IntArray(w*h); source.getPixels(pixels,0,w,0,0,w,h)
    val output=IntArray(pixels.size)
    fun sample(x: Int,y: Int)=pixels[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)]
    fun channel(color: Int,shift: Int)=(color ushr shift) and 255
    fun mix(x: Int,y: Int,t: Float): Int {
        fun ch(shift: Int)=(channel(x,shift)*(1-t)+channel(y,shift)*t).roundToInt().coerceIn(0,255)
        return android.graphics.Color.rgb(ch(16),ch(8),ch(0))
    }
    for(y in 0 until h) for(x in 0 until w) {
        val color=sample(x,y)
        val l=(channel(color,16)*.2126f+channel(color,8)*.7152f+channel(color,0)*.0722f)/255f
        output[y*w+x]=when(style) {
            PlayerBackgroundStyle.FOLIA_NOMAND_PAPER -> {
                val grain=(unit(x+y*w)-.5f)*.14f+sin(x*.7f+y*.15f)*.025f
                mix(secondary,primary,(l*.75f+.12f+grain).coerceIn(0f,1f))
            }
            PlayerBackgroundStyle.FOLIA_NOMAND_LENS -> {
                val nx=(x-w*.5f)/w; val ny=(y-h*.5f)/h
                val gain=1f+.7f*(nx*nx+ny*ny)
                val sx=(w*.5f+nx*w*gain).roundToInt(); val sy=(h*.5f+ny*h*gain).roundToInt()
                android.graphics.Color.rgb(channel(sample(sx+2,sy),16),channel(sample(sx,sy),8),channel(sample(sx-2,sy),0))
            }
            else -> {
                var r=0; var g=0; var b=0; var count=0
                for(dy in -3..3) for(dx in -3..3) { val p=sample(x+dx,y+dy); r+=channel(p,16); g+=channel(p,8); b+=channel(p,0); count++ }
                mix(android.graphics.Color.rgb(r/count,g/count,b/count),primary,.22f)
            }
        }
    }
    return Bitmap.createBitmap(output,w,h,Bitmap.Config.ARGB_8888)
}
