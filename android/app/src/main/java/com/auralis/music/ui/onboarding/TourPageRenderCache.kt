package com.auralis.music.ui.onboarding

import android.util.Log
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.auralis.music.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job

/** Freeze each preview's pixels during the actual pager drag, including its settle animation. */
@Stable
internal class TourPageRenderCache(private val page: Int, private val layer: GraphicsLayer) {
    private var image by mutableStateOf<ImageBitmap?>(null)
    val hasImage: Boolean get() = image != null
    private var recorded by mutableStateOf(false)
    private var recordingNeeded by mutableStateOf(true)
    private var captureJob: Job? = null
    private var measuredSize = IntSize.Zero
    private var frozenFrames = 0

    fun modifier(isScrolling: () -> Boolean, isNearby: () -> Boolean): Modifier = Modifier.onSizeChanged { size ->
        // A new callback instance may receive the same size after recomposition. Never
        // discard a ready bitmap just because active changed at the start of a drag.
        if (size != measuredSize) {
            measuredSize = size
            image = null
            recorded = false
            recordingNeeded = true
        }
    }.drawWithContent {
        val scrolling = isScrolling()
        val visible = isNearby()
        if (!visible && (scrolling || !recordingNeeded)) return@drawWithContent
        val frozen = if (scrolling) image else null
        if (frozen != null) {
            if (BuildConfig.DEBUG && frozenFrames++ == 0) Log.d("AuralisTourCache", "Drawing frozen page $page")
            // No live player, blur, glass refraction or lyric renderer is redrawn here.
            drawImage(frozen)
        } else {
            layer.record { this@drawWithContent.drawContent() }
            recorded = true
            recordingNeeded = false
            if (visible) drawLayer(layer)
        }
    }

    suspend fun capture(afterDraw: Boolean = false) {
        val thisJob = currentCoroutineContext().job
        captureJob?.takeIf { it !== thisJob }?.cancel()
        captureJob = thisJob
        try {
            if (afterDraw) recordingNeeded = true
            snapshotFlow { recorded }.first { it }
            if (afterDraw) { withFrameNanos { }; withFrameNanos { } }
            val candidate = layer.toImageBitmap()
            // A render layer can be empty after an offscreen re-record. Never replace
            // known-good pixels with that image, or show an empty incoming page.
            val valid = validationMutex.withLock {
                withContext(Dispatchers.Default) {
                    val readable = candidate.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                        ?: return@withContext false
                    try {
                        var ink = 0
                        val stepX = (readable.width / 48).coerceAtLeast(1)
                        val stepY = (readable.height / 48).coerceAtLeast(1)
                        for (x in 0 until readable.width step stepX) {
                            for (y in 0 until readable.height step stepY) {
                                if ((readable.getPixel(x, y) ushr 24) > 32) ink++
                            }
                        }
                        ink >= 4
                    } finally { readable.recycle() }
                }
            }
            if (valid) {
                image = candidate
                frozenFrames = 0
                if (BuildConfig.DEBUG) Log.d("AuralisTourCache", "Prepared page $page (${layer.size})")
            } else if (BuildConfig.DEBUG) Log.d("AuralisTourCache", "Kept previous page $page; rejected empty capture")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Native paging remains usable if a device cannot capture a graphics layer.
            Log.w("AuralisTourCache", "Unable to prepare page $page", error)
        } finally {
            if (captureJob === thisJob) captureJob = null
        }
    }

    fun clear(keepImage: Boolean = false) {
        captureJob?.cancel()
        captureJob = null
        if (!keepImage) image = null
    }

    private companion object {
        // Keep background readbacks bounded while the first tour pages are prepared.
        val validationMutex = Mutex()
    }
}
