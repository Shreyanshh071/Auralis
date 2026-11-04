package com.auralis.music.ui.theme

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale

/**
 * Fades the whole app between two looks that can't blend colour by colour, like liquid glass and
 * the normal theme: the old screen is frozen on top, the change happens under it, and it fades out.
 */
object ThemeCrossfade {
    private var frozenScreen by mutableStateOf<ImageBitmap?>(null)

    fun run(view: View, change: () -> Unit) {
        val window = (view.context as? Activity)?.window
        val decor = window?.decorView
        if (window == null || decor == null || decor.width == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            change()
            return
        }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) frozenScreen = bitmap.asImageBitmap()
            change()
        }, Handler(Looper.getMainLooper()))
    }

    /** Goes last in the app's root, above everything else. */
    @Composable
    fun Overlay() {
        val image = frozenScreen ?: return
        val alpha = remember(image) { Animatable(1f) }
        LaunchedEffect(image) {
            alpha.animateTo(0f, tween(480, delayMillis = 60, easing = FastOutSlowInEasing))
            frozenScreen = null
        }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }
        )
    }
}
