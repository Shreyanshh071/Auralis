package com.auralis.music.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** The activity window: blurred with RenderEffect (Android 12+). */
@Composable
actual fun rememberMenuPage(): MenuPage {
    val pageView = LocalView.current.rootView
    return remember(pageView) {
        MenuPage(
            width = { pageView.width },
            height = { pageView.height },
            blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            setBlur = { radius ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    pageView.setRenderEffect(
                        if (radius < 0.5f) null
                        else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                    )
                }
            }
        )
    }
}

@Composable
actual fun rememberOriginOnScreen(): () -> Offset {
    val view = LocalView.current
    return remember(view) { { IntArray(2).also { view.getLocationOnScreen(it) }.let { Offset(it[0].toFloat(), it[1].toFloat()) } } }
}

@Composable
actual fun ConfigureMenuDialogWindow() {
    val dialogView = LocalView.current
    DisposableEffect(dialogView) {
        (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setDimAmount(0f)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setWindowAnimations(0)
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        onDispose { }
    }
}

actual fun menuDialogProperties() = androidx.compose.ui.window.DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false
)
